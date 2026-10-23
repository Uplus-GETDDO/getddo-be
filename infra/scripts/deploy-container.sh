#!/usr/bin/env bash
# SSM이 /opt/getddo/releases/<실행 ID>/에서 root로 실행한다.
# 사용: bash deploy-container.sh <CI가 검사한 커밋 SHA> <AWS 리전>
set -Eeuo pipefail
umask 077
source_sha=${1:?커밋 SHA가 필요합니다}
region=${2:?AWS 리전이 필요합니다}
[[ $source_sha =~ ^[a-f0-9]{40}$ && $region == ap-northeast-2 ]] || exit 1
release=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
root=$(dirname -- "$(dirname -- "$release")")
app_env=/etc/getddo/app.env
env_tmp=''
env_changed=0
committed=0
had_env=0
previous=''
old_image=''
image=''
marker="$root/deploy-in-progress"
transaction_started=0
cleanup_complete=0
api_private_ip=''

# Actions와 별개로 서버에서도 한 번에 한 배포만 허용한다.
exec 9>"$root/deploy.lock"
flock -n 9 || { echo '다른 배포가 실행 중입니다.' >&2; exit 1; }
# 강제 종료는 EXIT trap을 실행하지 않는다. 남은 기록이 있으면 설정을 조회·교체하기 전에 멈춘다.
if [[ -e $marker || -L $marker ]]; then
  echo "DEPLOY_INCOMPLETE: 이전 배포 기록을 확인하고 수동 복구하세요: $marker" >&2
  exit 1
fi
# 마커 삭제 후 디스크 반영이 실패한 경우에도 비공개 설정 디렉터리의 복구 기록으로 차단한다.
for recovery_context in /etc/getddo/.deploy-env.*/context; do
  if [[ -e $recovery_context || -L $recovery_context ]]; then
    echo "DEPLOY_INCOMPLETE: 남은 복구 기록을 확인하세요: $recovery_context" >&2
    exit 1
  fi
done

clear_marker() {
  # 설정과 current 링크를 먼저 디스크에 반영한다. 복구 중 삭제한 env도 디렉터리 기준으로 동기화한다.
  sync -f /etc/getddo || return 1
  sync -f "$root" || return 1
  rm -f -- "$marker/context" || return 1
  rmdir -- "$marker" || return 1
  sync -f "$root" || return 1
  cleanup_complete=1
}

compose() {
  local limit=$1 directory=$2 selected_image=$3 selected_env=$4
  shift 4
  # 임시 설정으로 사전 검증하고 실제 실행·복구 때는 정식 app.env를 읽는다.
  # 설정값 자체를 셸 변수로 가져오거나 source로 실행하지 않는다.
  API_PRIVATE_IP="$api_private_ip" APP_IMAGE="$selected_image" APP_ENV_FILE="$selected_env" timeout "$limit" docker compose --project-name getddo \
    --env-file /dev/null --file "$directory/compose.deploy.yaml" "$@"
}

healthy() {
  local directory=$1 selected_image=$2 container state status attempt
  local deadline=$((SECONDS + 180))
  for ((attempt = 0; attempt < 30 && SECONDS < deadline; attempt++)); do
    container=$(compose 10 "$directory" "$selected_image" "$app_env" ps -q app) || container=''
    state=''
    if [[ -n $container ]]; then
      state=$(timeout 10 docker inspect --format '{{.State.Running}} {{.Config.Image}}' "$container") || state=''
    fi
    if [[ $state == "true $selected_image" ]]; then
      # 기존 HTTP API로 기동을 확인한다. DB·업무 기능 전체 검증은 아니다.
      status=$(curl --silent --output /dev/null --write-out '%{http_code}' \
        --max-time 5 http://127.0.0.1:8080/v3/api-docs) || status=''
      [[ $status == 200 ]] && return 0
    fi
    sleep 4
  done
  return 1
}

metrics_ready() {
  # 이전 산출물/롤백에는 Prometheus endpoint를 요구하지 않는다.
  grep -q 'API_PRIVATE_IP' "$release/compose.deploy.yaml" || return 0
  local attempt
  for ((attempt = 0; attempt < 15; attempt++)); do
    if curl --disable --fail --silent --noproxy '*' --max-time 5 "http://$api_private_ip:9091/actuator/prometheus" | python3 -c '
import math
import re
import sys
required = {"http_server_requests_seconds_bucket", "jvm_memory_used_bytes",
            "tomcat_threads_busy_threads", "hikaricp_connections_max"}
found = set()
for line in sys.stdin:
    sample = re.fullmatch(r"([a-zA-Z_:][a-zA-Z0-9_:]*)(?:\{.*\})?\s+([-+]?(?:[0-9]+\.?[0-9]*|\.[0-9]+)(?:[eE][-+]?[0-9]+)?)(?:\s+[0-9]+)?", line.strip())
    if sample and sample[1] in required and math.isfinite(float(sample[2])):
        found.add(sample[1])
sys.exit(0 if found == required else 1)
'; then
      return 0
    fi
    sleep 4
  done
  echo 'Prometheus 필수 지표 확인 실패' >&2
  return 1
}

record_current() {
  ln -sfn "$1" "$root/current.next"
  mv -Tf "$root/current.next" "$root/current"
}

rollback() {
  # 첫 배포에는 복구할 앱이 없다. env 파일을 제거하기 전에 Compose가 설정을 읽고 중지한다.
  if [[ -z $previous ]]; then
    compose 60 "$release" "$image" "$app_env" stop app || return 1
  fi
  # 이미지보다 설정을 먼저 복원한다. 복원 실패 시 새 설정으로 이전 앱을 실행하지 않는다.
  if ((had_env)); then
    # 진행 기록 정리가 끝날 때까지 원본 백업은 보존하고 복사본을 원자적으로 복원한다.
    if ! install -m 0600 "$env_tmp/previous.env" "$env_tmp/restore.env" ||
       ! mv -f -- "$env_tmp/restore.env" "$app_env"; then
      echo 'ROLLBACK_FAILED: 이전 설정을 복원하지 못했습니다.' >&2
      return 1
    fi
  else
    rm -f -- "$app_env" || return 1
  fi
  env_changed=0
  if [[ -n $previous ]]; then
    # 이전 Compose·이미지와 복원한 env를 함께 사용한다. 같은 이미지여도 설정을 다시 주입한다.
    if compose 120 "$previous" "$old_image" "$app_env" up -d --force-recreate --no-build --pull never app &&
       healthy "$previous" "$old_image" && record_current "$previous"; then
      echo 'ROLLBACK_OK: 이전 이미지와 설정을 복구했습니다. 이번 배포는 실패입니다.'
    else
      echo 'ROLLBACK_FAILED: 이전 앱의 정상 응답을 확인하지 못했습니다.' >&2
      return 1
    fi
  else
    if [[ -L $root/current && $(readlink -f "$root/current") == "$release" ]]; then
      unlink "$root/current"
    fi
    echo '첫 배포 실패: 이전 설정 상태를 복원하고 실패한 앱을 중지했습니다.' >&2
  fi
}

finish() {
  local status=$?
  trap - EXIT INT TERM
  # 설정 교체 이후의 오류·중단도 복구한다. 조회·검증 실패는 기존 앱을 건드리지 않는다.
  if ((env_changed && !committed)); then
    if rollback; then
      if clear_marker && [[ -n $previous ]]; then
        echo "ROLLBACK_CONFIRMED_HEALTHY: $source_sha $old_image"
      fi
    fi
    ((status != 0)) || status=1
  fi
  if ((transaction_started && !cleanup_complete)); then
    # 복구·기록 정리가 끝나지 않으면 백업과 마커를 보존하고 다음 배포도 차단한다.
    echo "DEPLOY_INCOMPLETE: 진행 기록과 임시 설정을 보존했습니다: $marker $env_tmp" >&2
    ((status != 0)) || status=1
  elif [[ -n $env_tmp ]]; then
    # 비밀값이 있는 새 임시 파일·백업만 정리한다. 배포 S3·release.tar에는 넣지 않는다.
    rm -f -- "$env_tmp/next.env" "$env_tmp/previous.env" "$env_tmp/restore.env" "$env_tmp/context"
    rmdir -- "$env_tmp"
  fi
  exit "$status"
}
trap finish EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

# IMDSv2에서 이 호스트의 기본 private IP만 읽는다. 프록시·리디렉션·IMDSv1 fallback은 쓰지 않는다.
# 새 Compose가 필요로 할 때만 조회하여 관리 포트가 없는 승인된 이전 산출물도 배포할 수 있다.
if grep -q 'API_PRIVATE_IP' "$release/compose.deploy.yaml" ||
   { [[ -L $root/current ]] && grep -q 'API_PRIVATE_IP' "$root/current/compose.deploy.yaml"; }; then
  metadata_token=$(curl --disable --fail --silent --show-error --noproxy '*' --connect-timeout 2 --max-time 5 \
    --request PUT --header 'X-aws-ec2-metadata-token-ttl-seconds: 60' \
    http://169.254.169.254/latest/api/token) || { echo 'IMDSv2 token 조회 실패' >&2; exit 1; }
  [[ -n $metadata_token && $metadata_token != *$'\r'* && $metadata_token != *$'\n'* ]] || exit 1
  # 헤더는 stdin으로 전달해 프로세스 명령행에 token을 남기지 않는다.
  api_private_ip=$(printf 'X-aws-ec2-metadata-token: %s\n' "$metadata_token" | \
    curl --disable --fail --silent --show-error --noproxy '*' --connect-timeout 2 --max-time 5 \
    --header @- http://169.254.169.254/latest/meta-data/local-ipv4) || { echo 'IMDSv2 private IP 조회 실패' >&2; exit 1; }
  unset metadata_token
  python3 - "$api_private_ip" <<'PYIP'
import ipaddress
import sys
try:
    address = ipaddress.IPv4Address(sys.argv[1])
    if not any(address in ipaddress.IPv4Network(network) for network in ("10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16")):
        raise ValueError("not RFC1918")
except ValueError:
    raise SystemExit("IMDSv2 private IPv4 검증 실패") from None
PYIP
fi

# 설정 디렉터리는 초기 설치에서 준비한다. 기존 env의 링크·소유권·권한도 검사한다.
[[ -d /etc/getddo && ! -L /etc/getddo ]]
if [[ -e $app_env || -L $app_env ]]; then
  [[ -f $app_env && ! -L $app_env && $(stat -c '%a:%u' "$app_env") == "600:$EUID" ]]
fi
if [[ -L $root/current ]]; then
  previous=$(readlink -f "$root/current")
  old_image=$(cat "$previous/image-id.txt")
  [[ $old_image =~ ^sha256:[a-f0-9]{64}$ ]]
  compose 20 "$previous" "$old_image" "$app_env" config --quiet
  timeout 10 docker image inspect "$old_image" >/dev/null
elif [[ -e $root/current ]]; then
  echo 'current 경로가 배포용 링크가 아닙니다.' >&2
  exit 1
fi

cd "$release"
sha256sum --check image.tar.gz.sha256
image=$(cat image-id.txt)
[[ $image =~ ^sha256:[a-f0-9]{64}$ ]]

# app.env와 같은 파일시스템의 root 전용 임시 디렉터리를 써서 rename을 원자적으로 수행한다.
# 파라미터가 없거나 타입·형식·복호화가 틀리면 이 단계에서 실패하고 기존 앱은 유지된다.
env_tmp=$(mktemp -d /etc/getddo/.deploy-env.XXXXXX)
python3 "$release/fetch-app-env.py" --region "$region" --output "$env_tmp/next.env"
if [[ -n ${GETDDO_DEPLOY_SERVER:-} ]]; then
  python3 "$release/inject-host-env.py" --file "$env_tmp/next.env" --server "$GETDDO_DEPLOY_SERVER" --enabled "${GETDDO_DEPLOY_METRICS_ENABLED:?}"
fi
compose 20 "$release" "$image" "$env_tmp/next.env" config --quiet
if [[ -z $previous && -n $(compose 10 "$release" "$image" "$env_tmp/next.env" ps --all --quiet app) ]]; then
  echo '복구 기록이 없는 기존 앱이 있습니다. 기존 배포를 먼저 확인하세요.' >&2
  exit 1
fi
# 새 이미지까지 검증한 뒤에만 실제 설정과 컨테이너를 교체한다.
timeout 300 docker image load --input image.tar.gz
[[ $(timeout 10 docker image inspect --format '{{.Id}}' "getddo-be:$source_sha") == "$image" ]]
revision=$(timeout 10 docker image inspect --format '{{ index .Config.Labels "org.opencontainers.image.revision" }}' "$image")
[[ $revision == "$source_sha" ]]
if [[ -f $app_env ]]; then
  install -m 0600 "$app_env" "$env_tmp/previous.env"
  had_env=1
fi
# 설정 교체 전에 비밀값 없는 진행 기록과 비공개 백업을 영속화한다.
# 기록이 비어 있더라도 다음 배포는 차단한다. 내용은 배포·임시 설정 디렉터리 두 줄이다.
# 복구 경로는 별도 디렉터리에도 보존한다. clear_marker의 어느 단계가 실패해도 이 기록은 남는다.
printf '%s\n' "$release" "$env_tmp" > "$env_tmp/context"
mkdir -m 0700 -- "$marker"
transaction_started=1
install -m 0600 "$env_tmp/context" "$marker/context"
sync -f "$env_tmp"
sync -f "$root"
# 플래그를 먼저 설정해 rename 직후 중단돼도 EXIT에서 이전 상태를 복구한다.
env_changed=1
mv -f -- "$env_tmp/next.env" "$app_env"

# 짧은 중단을 동반하는 교체다. 환경변수 변경도 반영되도록 강제 재생성한다.
if compose 120 "$release" "$image" "$app_env" up -d --force-recreate --no-build --pull never app &&
   healthy "$release" "$image" && metrics_ready && record_current "$release"; then
  committed=1
  clear_marker
  echo "DEPLOY_OK: $source_sha $image"
else
  exit 1
fi
