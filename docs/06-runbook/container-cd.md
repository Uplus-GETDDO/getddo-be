# 컨테이너 CD 실행 준비

- 상태: 사용자 검토 완료 · Codex 작성 · 2026-10-15 사용자 요청으로 상태 갱신
- 작성일: 2026-10-13 · 갱신일: 2026-10-15
- 현재 단계: main CI 성공 → 동일 이미지 전달 → 서버 교체·상태 확인·실패 복구 코드를 연결했다. 실제 GitHub Actions와 AWS 배포는 미검증이며 서버·권한·설정 준비가 필요하다.

## Parameter Store 설정 자동 반영

GD-112의 기존 9개에 GD-111의 Redis 설정 4개와 GD-208의 RATE_LIMIT_STORE를 추가해 서버가 배포 직전에 총 14개를 10개·4개로 나눠 조회·검증하고, 실패 시 이전 이미지와 설정을 함께 복구한다. 기존 준비 과정과 변경 전후·등록·반영 절차는 [Parameter Store 런북](parameter-store.md)을 참고한다. 이 코드가 main에 반영돼 배포되기 전에는 기존 서버 동작이 바뀌지 않는다.

## 파일이 하는 일

[build.yml](../../.github/workflows/build.yml)은 앱 빌드·테스트를, [image.yml](../../.github/workflows/image.yml)은 이미지 생성·보관을, [cd.yml](../../.github/workflows/cd.yml)은 서버 배포를 담당한다. **main 반영 → 테스트 성공 → 이미지 생성·보관 성공 → 서버 교체·응답 확인** 순서로 실행하며 설정 교체 이후 실패 시 배포 직전 설정과 이전 이미지·Compose를 함께 복구한다.

1. PR과 main/dev push에서 `Build` CI가 PR 제목 규칙 검사·앱 빌드·단위 테스트·MySQL 통합 테스트를 수행한다.
2. 테스트가 성공하면 `needs: build`로 연결한 `image.yml`을 재사용 워크플로로 호출해 프로젝트 루트의 [Dockerfile](../../Dockerfile)로 이미지를 만든다. 이 파일은 `:api:bootJar`로 백엔드 실행 JAR를 만들어 Java 21로 실행하는 프로젝트용 구성이다. 이미지가 만들어지지 않으면 CI도 실패한다.
3. main push일 때만 생성한 이미지를 압축 파일로 보관한다. 이미지 ID와 파일 체크섬도 함께 저장한다. PR/dev는 이미지 빌드 검증까지만 수행한다.
4. 같은 저장소의 main push로 실행된 `Build` 전체가 성공한 경우 CD를 시작한다. main 병합도 push에 포함된다.
5. CD는 그 CI의 실행 ID·커밋 SHA·재시도 번호를 지정해 파일을 받고, 체크섬·이미지 ID·커밋 라벨을 확인한다. 테스트나 이미지 빌드를 다시 수행하지 않는다.
6. OIDC 임시 권한으로 배포 파일을 전용 S3 버킷에 올리고 SSM으로 서버에 명령을 보낸다. 서버는 Parameter Store의 필수 설정을 조회·검증하고 같은 이미지를 불러와 설정과 앱을 교체한 뒤 HTTP 응답을 확인한다. 설정 교체 후 앱 교체나 응답 확인에 실패하면 배포 직전 app.env를 먼저 복원하고 이전 이미지·Compose로 앱을 재생성한다.
7. SSM의 최종 상태와 종료 코드가 성공이어야 CD도 성공한다. 복구에 성공했더라도 새 배포는 실패로 표시한다.

`image.yml`은 독립 push 트리거 없이 `workflow_call`로 호출한다. 호출한 `Build` 실행의 커밋·이벤트·실행 ID·재시도 번호를 사용하므로 기존 CD의 다운로드 기준을 유지한다. 이미지 작업까지 끝나야 `Build` 전체가 성공한다. CI 동시 실행 제한은 워크플로 전체에 적용해 테스트와 이미지 작업을 함께 취소한다.

브랜치 보호에서 상태 검사를 등록할 때는 기존 `빌드·테스트` 외 분리된 이미지 작업도 필수로 등록해야 이미지 실패 상태로 병합되는 것을 막을 수 있다. 재사용 작업의 실제 표시 이름은 Actions 실행 후 확인하며 이번 작업에서는 GitHub 보호 설정을 변경하지 않았다.

CI 실패·취소, dev의 CI, PR의 CI, 다른 저장소의 CI로는 CD 작업을 시작하지 않는다. `workflow_run`은 CI가 종료되면 발생하므로 main CI가 실패한 경우 CD 실행 항목이 표시돼도 작업은 건너뛴다.

이미지에는 CI가 검사한 커밋 SHA를 이름과 `org.opencontainers.image.revision` 라벨로 붙인다. CD에서는 `workflow_run.head_sha`로 이를 확인한다. `workflow_run`의 기본 `GITHUB_SHA`는 기본 브랜치의 커밋을 가리킬 수 있으므로 사용하지 않는다.

main의 이미지 파일은 `container-image-<커밋 SHA>-<CI 재시도 번호>` 아티팩트로 7일간 보관한다. 같은 커밋의 CI를 재실행해도 각 시도의 파일을 구분한다. CD는 자신을 시작하게 한 CI의 실행 ID로 다운로드하며, 파일이 없거나 만료됐거나 검증에 실패하면 중단한다. 만료된 결과가 필요하면 CI부터 다시 실행한다. 실패한 테스트 보고서도 선행 CI에서 7일간 보관한다.

## 실행 방법

main의 `Build` CI가 이미지 파일 보관까지 성공하면 `Container CD`가 자동으로 이어진다. 수동 실행으로 CI 성공 조건을 우회하는 경로는 두지 않는다. 아래 배포 연결 변수와 서버 준비가 필요하다. CI 아티팩트 조회에는 자동 제공되는 `GITHUB_TOKEN`과 CD의 `actions: read` 권한을 사용한다.

`workflow_run`을 받으려면 CD 파일이 저장소의 기본 브랜치에도 있어야 한다. 2026-10-14 GitHub 조회에서 기본 브랜치는 `dev`이므로 dev에 CD 파일을 반영하고 main에도 CI와 실행 코드가 반영돼 있어야 한다. 실제 GitHub 설정이 바뀌면 기본 브랜치를 다시 확인한다.

main의 CD는 한 번에 하나씩 실행하며 진행 중인 작업은 자동 취소하지 않는다. 선행 CI의 이름 `Build`를 바꿀 때는 CD의 `workflows` 목록도 함께 수정한다.

## 자동 배포 연결 설정

GitHub **Settings → Environments → production → Variables**에 다음 네 값을 등록한다. 파일과 설정이 공유된 뒤 main CI 성공을 기점으로 자동 실행한다. 환경에 필수 승인자를 설정하면 그 승인도 기다리므로 완전 자동 실행 여부는 환경 규칙과 함께 확인한다.

| 변수 | 값 |
| --- | --- |
| `AWS_REGION` | 대상 리전, 예: `ap-northeast-2` |
| `AWS_ROLE_ARN` | GitHub OIDC로 맡는 배포 역할 ARN |
| `DEPLOY_BUCKET` | 배포 파일 전용 S3 버킷 이름. URL이 아닌 이름만 입력 |
| `EC2_INSTANCE_ID` | SSM에서 관리하는 대상 EC2 한 대의 ID |

값이 없거나 형식이 잘못되면 AWS 인증 전에 실패한다. `--check` 단계는 설정과 main만 확인하며 파일 업로드나 SSM 실행을 하지 않는다. 배포 직전 현재 main의 커밋과 CI의 커밋도 다시 비교해 이미 main이 갱신된 이전 실행을 차단한다.

- **OIDC 역할:** 해당 저장소의 `production` 환경에 한정해 신뢰하고 `aud`를 `sts.amazonaws.com`으로 제한한다. 실제 OIDC subject는 저장소 설정에 따라 ID를 포함할 수 있으므로 실제 형식을 확인해 제한한다. `workflow_run`의 실행 ref는 기본 브랜치일 수 있어 main 선택은 워크플로의 선행 CI 조건과 API 확인으로 판단한다. 환경의 배포 브랜치 제한도 실제 실행 ref와 맞춰야 한다.
- **Actions 역할:** 전용 버킷의 `releases/*`에 대한 `s3:PutObject`, 대상 EC2와 `AWS-RunShellScript`에 대한 `ssm:SendCommand`, 결과 확인용 `ssm:GetCommandInvocation` 권한이 필요하다. 권한과 역할은 이번 작업에서 생성하거나 연결하지 않았다.
- **전용 S3:** 같은 계정·리전의 비공개 일반 버킷을 준비한다. 퍼블릭 접근 차단·ACL 비활성화·TLS만 허용을 적용하고, 코드에서는 AES256 암호화·버킷 소유 계정 확인·기존 키 덮어쓰기 금지를 지정한다. State 버킷과 분리하며 `tfstate`나 `terraform`을 포함하는 이름은 코드에서 거부한다. 키는 `releases/<커밋>/<CD 실행 ID>-<시도>/release.tar`다.
- **서버 역할·네트워크:** SSM Agent가 Online이어야 한다. EC2 역할에는 SSM 연결 권한과 전용 버킷 `releases/*`의 `s3:GetObject`가 필요하며, EC2에서 SSM·S3와 비공개 RDS로 연결할 수 있어야 한다. root 프로필이나 고정 Access Key를 서버에 저장하지 않는다.
- **서버 도구:** Linux x86_64, Docker Engine, Compose 2.30 이상, AWS CLI, Python 3.8 이상, Bash, curl, flock, timeout, tar, sha256sum을 준비한다. SSM은 root로 실행하며 `/opt/getddo`는 root만 쓸 수 있어야 한다. 기존 JAR 서비스가 8080 포트를 사용 중이면 자동 전환 전 별도로 확인한다.
- **앱 설정:** [Parameter Store 런북](parameter-store.md)에 따라 Redis 설정을 포함한 필수 설정 14개를 등록하고 EC2 역할의 조회·복호화 권한을 준비한다. 배포 스크립트가 검증 후 `/etc/getddo/app.env`를 root 소유·권한 600으로 생성·교체한다. 수동 편집한 값은 다음 배포에서 덮어쓰므로 설정 변경은 Parameter Store에서 관리한다. 요청 빈도 제한의 저장소를 고르는 `RATE_LIMIT_STORE`(`redis` 또는 `memory`)도 같은 방식으로 등록한다([요청 빈도 제한](../02-api/rate-limit.md#redis-사용-서버-여러-대)). 배포 스크립트의 조회 목록에 포함돼 있어 등록하지 않으면 설정 누락으로 실패한다. raw 형식이므로 값에 바깥 따옴표를 붙이지 않는다. 로컬 DB를 가리키는 `local` 프로필을 사용하지 않는다.
- **DB TLS:** `/etc/getddo/rds-truststore.p12`에 공개 RDS CA를 준비하고 JDBC URL에 `sslMode=VERIFY_IDENTITY`와 해당 truststore의 경로·형식 등을 지정한다. DB 호스트에는 인증서 이름과 일치하는 RDS 엔드포인트를 사용한다.

이미지 보관·전송에는 S3 저장 공간과 요청 요금 등이 발생한다. 전용 버킷의 `releases/`에 만료 정책을 적용해 누적을 제한하고, 접근 로그·CloudTrail 데이터 이벤트·CloudWatch 메트릭으로 운영 상태를 확인하는 것을 권장한다. 이 설정은 코드에서 자동 생성하지 않는다. [S3 요금 기준](https://aws.amazon.com/s3/pricing/)과 [보안·운영 권고](https://docs.aws.amazon.com/AmazonS3/latest/userguide/security-best-practices.html)를 따른다.

## 애플리케이션 로그 수집 유지

[compose.deploy.yaml](../../compose.deploy.yaml)의 `app.logging`에 `awslogs`를 포함해 자동 배포에서도 서울(`ap-northeast-2`)의 `/getddo/dev/api`로 stdout·stderr를 전송한다. 배포 스크립트는 release의 기본 Compose만 사용하므로 별도 수동 override 파일에 의존하지 않는다. 앱 환경변수나 Parameter Store 항목을 추가할 필요는 없다.

- 배포 전에 Standard 로그 그룹 `/getddo/dev/api`와 보관 기간 14일을 준비한다. `awslogs-create-group: "false"`이므로 컨테이너가 로그 그룹을 만들지 않는다. 이 Compose는 그룹·보관 기간을 관리하지 않는다.
- Docker 데몬이 사용하는 EC2 역할에 해당 그룹의 `logs:CreateLogStream`·`logs:PutLogEvents` 권한과 CloudWatch Logs 엔드포인트 연결이 필요하다. 앱 로그는 Docker가 직접 전송하며 CloudWatch Agent의 메모리·디스크 지표 수집과 구분한다.
- stream 이름을 지정하지 않아 새 컨테이너 ID별로 생성된다. `mode: non-blocking`, `max-buffer-size: 4m`으로 전송 지연 시 앱의 로그 쓰기 대기를 피하지만 버퍼가 차면 로그가 유실될 수 있다. `json-file` 전용 `max-size`·`max-file` 옵션은 사용하지 않는다.
- 배포 후 새 컨테이너 ID의 stream에 기동 로그와 이후 요청 로그가 들어오는지 확인한다. HTTP 기동 확인만으로 로그 수집 성공을 판단하지 않는다. 실패 시 로그 그룹·리전·역할 권한·네트워크를 확인한다.
- 실패 복구는 이전 release의 Compose를 사용한다. 이전 파일이 `json-file`이면 복구 시 로그 드라이버도 되돌아가므로 CloudWatch 수집 유지 여부를 별도로 확인한다.

오프라인에서는 비밀값 없는 임시 env 파일을 `APP_ENV_FILE`로 지정하고 `APP_IMAGE`를 설정한 뒤 `docker compose --project-name getddo --env-file /dev/null --file compose.deploy.yaml config --format json`으로 드라이버·옵션·raw env 보존을 검사한다. 이 검사는 AWS 권한·네트워크·실제 로그 도착을 검증하지 않는다. 실제 재배포 후 수집 확인은 별도 운영 검증으로 남는다.

설정 근거: [Docker awslogs 드라이버](https://docs.docker.com/engine/logging/drivers/awslogs/), [non-blocking 버퍼 동작](https://docs.docker.com/engine/logging/configure/#configure-the-delivery-mode-of-log-messages-from-container-to-log-driver).

## 서버 교체와 실패 복구

[deploy-ssm.py](../../infra/scripts/deploy-ssm.py)는 이미지 파일·체크섬·이미지 ID·Compose·교체 스크립트·설정 조회 스크립트만 묶어서 전달한다. `.env`, State, DB 비밀번호와 인증 파일은 포함하지 않는다. 서버는 전달 파일의 체크섬을 검증한 뒤 [deploy-container.sh](../../infra/scripts/deploy-container.sh)를 실행한다.

서버에서도 `flock`으로 동시 교체를 막는다. 교체 전에는 마지막 성공 배포의 Compose와 이미지, 새 파일의 체크섬·이미지 ID·커밋 라벨·Compose 설정을 검사한다. 이 단계의 실패는 기존 앱을 교체하지 않는다. 실행 이미지는 로컬 이미지 ID로 고정하고 외부 다운로드 없이 앱 컨테이너 하나를 교체한다. 이때 짧은 중단이 있다.

요청한 이미지가 실제로 실행 중인지와 `http://127.0.0.1:8080/v3/api-docs`의 HTTP 200 응답을 함께 확인한다. 이는 앱의 HTTP 기동 확인이며 DB·업무 기능의 종합 점검은 아니다. 정상 응답을 확인한 배포만 `/opt/getddo/current` 링크에 기록한다. 각 배포의 파일과 이미지 ID는 `/opt/getddo/releases/`에 보관한다.

설정 교체 후 앱 교체·응답 확인에 실패하면 배포 직전 `app.env`를 복원한 뒤 이전 배포의 Compose와 이미지로 앱을 재생성하고 다시 상태를 확인한다. 복구 실패는 `ROLLBACK_FAILED`로 알리며 첫 배포 실패는 실패한 앱을 중지하고 기존 설정 파일 상태를 복원한다. 복구 기록이 없는 기존 `getddo` 프로젝트 컨테이너는 자동으로 덮어쓰지 않는다.

복구는 배포 직전 `app.env`와 이전 이미지·Compose를 대상으로 하며 DB 마이그레이션·데이터·Parameter Store의 원격 값을 되돌리지 않는다. 이전 앱과 호환되는 Flyway 변경을 준비해야 한다. 현재와 직전 성공 이미지·배포 폴더를 보존하고, 오래된 파일의 정리는 별도 운영 절차로 수행한다. 이 코드는 이미지나 배포 폴더를 자동 삭제하지 않는다.

미완료된 `/opt/getddo/deploy-in-progress/` 기록이 있으면 다음 배포는 설정 조회·교체 전에 중단한다. 강제 종료 후의 기록 확인·수동 복구와 기록 제거 조건은 [Parameter Store 런북](parameter-store.md#실패-시-복구와-한계)을 따른다.

수동 취소·통신 단절·대기 시간 초과는 원격 명령 중단을 보장하지 않는다. Actions 요약의 SSM Command ID로 최종 상태를 확인하고 서버의 현재 이미지·HTTP 응답을 확인한 뒤 재배포한다. 원격 앱 로그에는 비밀값이 섞일 수 있어 Actions로 자동 복사하지 않는다.

## 함께 준비한 파일과 검증 범위

[compose.deploy.yaml](../../compose.deploy.yaml)은 서버에서 앱 컨테이너 하나를 실행하기 위한 설정이다. 자동 배포에서 이 파일을 사용한다. Parameter Store 설정·조회 권한·DB 연결·인증서를 사전에 준비해야 한다. 로컬 개발에는 기존 `compose.yaml`을 사용한다.

CI 성공은 테스트·이미지 빌드·파일 보관 성공을 뜻한다. CD 성공은 원격 교체 스크립트가 상태 확인까지 성공하고 SSM 결과도 성공했을 때만 표시한다. 실제 서버 배포는 미검증이다.

2026-10-14 로컬에서 전체 테스트·빌드를 캐시 없이 실행해 단위 테스트 898개와 MySQL 통합 테스트 808개가 모두 통과했다. Docker 이미지 빌드·저장·복원·이미지 ID/커밋 라벨 확인 및 별도 MySQL과 앱 컨테이너의 `/v3/api-docs` HTTP 200도 확인했다. 이는 로컬 검증이며 실제 GitHub Actions·OIDC·S3 전달·SSM·RDS 배포 성공을 뜻하지 않는다. 배포 회귀 테스트 코드는 이번 공유 대상에서 제외하며 CI에서도 해당 테스트를 실행하지 않는다.

참고: [GitHub workflow_run 실행 조건과 기본 브랜치·커밋 기준](https://docs.github.com/en/actions/reference/workflows-and-actions/events-that-trigger-workflows#workflow_run).

워크플로 분리 참고: [GitHub 재사용 워크플로](https://docs.github.com/en/actions/how-tos/reuse-automations/reuse-workflows).

이미지 전달 방식 참고: [다른 실행의 아티팩트 다운로드](https://github.com/actions/download-artifact/tree/v5#download-artifacts-from-other-workflow-runs-or-repositories), [Docker 이미지 저장](https://docs.docker.com/reference/cli/docker/image/save/), [Docker 이미지 불러오기](https://docs.docker.com/reference/cli/docker/image/load/).

## 서버 배포 성공과 버전 릴리스

서버 교체·응답 검증이 성공한 뒤 별도 publish 작업이 실제 배포 SOURCE_SHA에 버전 태그와 GitHub Release를 생성한다. 첫 버전은 v0.1.0이며 이후 패치를 증가시키고 같은 커밋의 재실행은 재사용한다. 이미지 자체는 SHA로 식별한다. 정기·수동 main 배포 모두 적용하며 자세한 정책과 설정은 [정기 릴리스 런북](daily-release.md#배포-성공-후-버전-발행)을 참고한다.

publish 작업 실패는 이미 성공한 서버 배포를 롤백하지 않는다. 이때 CD 전체는 실패로 표시되므로 서버 교체 실패와 버전 발행 실패를 작업별로 구분한다. 서버가 실행하는 SHA와 기존 태그·Release를 확인한 뒤 실패 작업을 재실행한다.
