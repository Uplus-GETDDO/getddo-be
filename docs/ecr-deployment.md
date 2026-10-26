# 운영 ECR 이미지 배포

운영 main CI는 테스트가 끝난 이미지 한 개를 서울 리전의 비공개 ECR에 게시한다. PR·dev 검증은 AWS 쓰기 권한을 갖지 않으며 동일 저장소 PR의 Stage tar 산출물을 유지한다. CD는 이미지를 다시 빌드하지 않는다.

## 이미지 식별과 출처

저장소는 `942466928325.dkr.ecr.ap-northeast-2.amazonaws.com/getddo-be`, 플랫폼은 `linux/amd64`로 제한한다. 태그는 `<source_sha>-<build_run_id>-<build_run_attempt>`이며 재실행은 새 차수의 불변 태그를 사용한다. 동일 태그의 push가 실패하면 성공으로 간주하지 않는다.

`image-metadata.json`은 다음 필드를 정확히 갖는다.

| 필드 | 의미 |
| --- | --- |
| schema_version | 정수 1 |
| source_sha | CI가 검사한 40자리 소스 SHA |
| build_run_id / build_run_attempt | 선행 Build 실행과 차수의 양의 정수 문자열 |
| repository_uri / image_tag | 허용 ECR 저장소와 실행별 태그 |
| image_digest | ECR manifest의 sha256 digest |
| image_id | Docker config의 로컬 sha256 ID |
| platform | linux/amd64 |

manifest digest와 로컬 image ID는 서로 다른 값이다. Artifact 이름은 `image-metadata-<SHA>-<run ID>-<attempt>`이고 CD는 선행 Build의 정확한 실행에서 다운로드한다. CD는 태그의 ECR manifest와 config ID를 대조하며 서버는 RepoDigests·로컬 ID·revision 라벨·플랫폼까지 대조한다. 실행 참조는 `<repository>@<digest>`다.

## 권한과 전달

main 전용 `image-publish.yml`의 호출 job에만 `id-token: write`를 부여한다. 게시 job에는 Environment를 지정하지 않으며 immutable main-ref OIDC subject로 게시 전용 역할을 사용한다. 기존 CD는 `production` Environment와 배포 역할을 유지한다.

repository Variables는 `ECR_REPOSITORY_URI`, `ECR_PUBLISH_ROLE_ARN`, `ECR_AWS_REGION`이며 Terraform의 실제 출력과 대조한다. 게시 역할은 `arn:aws:iam::942466928325:role/getddo-dev-github-image-publish`다. 장기 키나 Docker 암호를 Variables/Artifact에 저장하지 않는다.

CD의 metadata 검증은 지정 저장소의 `ecr:BatchGetImage` 읽기 권한을 사용한다. 서버 역할은 지정 저장소의 `ecr:BatchGetImage`·`ecr:GetDownloadUrlForLayer`·`ecr:BatchCheckLayerAvailability`만 허용하며 push·삭제 권한은 부여하지 않는다. `ecr:GetAuthorizationToken`은 별도 `Resource: *` statement에 서울 리전 조건으로 제한한다. 서버에서 ECR API·dkr 엔드포인트와 레이어용 S3 경로에 도달할 수 있어야 한다. 기존 SSM·S3 배포 파일·Parameter Store 권한과 경로도 유지한다.

S3 `release.tar`는 Compose·공개 배포 스크립트·metadata만 담는다. checksum·버킷 소유자 검사와 고유 경로의 덮어쓰기 거부를 유지한다. 운영 경로에는 이미지 tar와 S3 이미지 복귀 코드가 없다. Stage만 `prepare-staging-image.sh`로 tar를 불러온 뒤 같은 교체·복구 엔진을 실행한다.

## 사전 준비와 교체

1. 기존 S3 전역 배포 잠금, 서버별 flock, 미완료 마커, 상대 서버 healthy, 최신 main을 검사한다.
2. ALB 제외 전에 A/C 각각의 후보와 이전 ECR 복구 이미지를 준비하고 ID·revision·플랫폼을 검증한다. 준비 실패 시 env/current/컨테이너/ALB는 유지한다.
3. 두 서버의 준비 완료 출력이 같은 후보 digest와 ID를 나타낼 때 A→C 순서로 진행한다. 각 교체 직전 상대 서버 healthy를 다시 확인한다. 최신 main 검사는 두 서버 준비 뒤 첫 A 교체 직전에 수행하고, A 교체가 시작되면 C도 같은 산출물로 완료하며 새 main은 다음 직렬 CD가 처리한다.
4. ALB 제외·연결 정리 뒤 로컬 이미지를 재검증하고 설정을 원자 교체한다. `pull_policy: never`와 `up --pull never`로 실행한다.
5. 컨테이너 `.Config.Image`는 ECR digest 참조, `.Image`는 로컬 ID와 일치해야 한다. HTTP와 필수 지표를 확인한 뒤 current를 갱신하고 ALB에 재등록한다.

로그인 30초, 개별 pull 최대 300초, 개별 검사는 30초다. 호스트 이미지 준비에는 총 예산을 적용하며 SSM 실행 상한은 600초, 결과 확인은 660초다. 전체 CD 상한은 기존 120분·rolling 역할 세션 7200초 안에서 유지한다. 상한은 반드시 기다리는 시간이 아니다.

Docker 인증은 root 전용 `/run` 임시 `DOCKER_CONFIG`와 `--password-stdin`을 사용하며 종료 시 정리한다. 인증 토큰과 앱 설정값은 SSM 본문·Artifact·출력에 포함하지 않는다.

## 실패와 복구

새 앱이 실패하면 이전 ECR 참조·이전 Compose·직전 env를 사용한다. `old_ref`는 Compose 실행용 참조, `old_image_id`는 실제 ID 검증과 `ROLLBACK_CONFIRMED_HEALTHY` 출력용이다. 복구 시 ECR 접근 없이 로컬 복구 이미지를 사용할 수 있어야 한다.

`DEPLOY_OK: <source SHA> <local ID>`와 `ROLLBACK_CONFIRMED_HEALTHY: <attempted source SHA> <old local ID>`의 기존 ID 의미를 유지한다. 복구 성공에도 이번 배포는 실패이며 다음 서버를 배포하지 않는다. 후순위 실패 시 앞서 성공한 서버까지 전체 원복하지 않으므로 실제 혼합 여부를 확인한다.

SSM 결과 불명확, env 복원 실패, 마커 정리 실패 시 잠금·비공개 복구 기록을 보존한다. 확인 없이 SSM을 재발행하거나 잠금·마커를 제거하지 않는다.

## 기존 운영 이미지 기준 전환

이 절차는 운영자와 인프라 담당자가 승인된 변경 구간에서 수행한다. 현재 이미지는 재빌드하지 않고 기존 검증 산출물 또는 서버의 `docker save`로 보존해 ECR에 이전한다. A/C의 이미지가 다르면 각자의 기준 이미지를 별도로 보존한다.

1. 호출자는 기존 S3 `locks/container-deploy.json` 전역 잠금을 고유 소유자로 확보하고 최신 main과 진행 중 CD/SSM 없음, 양쪽 ALB healthy를 확인한다. 서버별 잠금만으로 전역 잠금을 대신하지 않는다.
2. `baseline-<현재 SHA>-<고유 식별자>` 태그로 이전 이미지를 게시한다. metadata의 run ID/attempt는 원본 CI 출처를 기록하고 임의로 생성하지 않는다.
3. 서버 역할로 baseline digest를 pull한다. RepoDigests·config ID·revision·플랫폼이 실행 중 앱과 같아야 한다. 컨테이너를 새로 만들지 않는다.
4. 동일 fixed SHA의 `convert-ecr-baseline.py`, `prepare-image.py`, `image-metadata.py`를 root 전용 도구 경로에 준비하고 metadata를 별도 root 전용 파일로 전달한다.

```bash
sudo python3 /root/ecr-tools/convert-ecr-baseline.py \
  --metadata /root/ecr-baseline-metadata.json \
  --release-name baseline-<고유-식별자>
```

도구는 서버의 orchestration/deploy flock와 마커를 검사한다. 기존 release의 공개 Compose·설정 조회 스크립트를 새 기준 release로 복사하고 `image-metadata.json`, `image-ref.txt`, `image-id.txt`, `source-sha.txt`, `baseline-evidence.json`을 기록한다. 기존 release와 env는 수정하지 않는다. 변환 전 실행 `.Config.Image`가 로컬 ID여도 실행 `.Image`와 revision의 동일성을 검증한다.

컨테이너 ID·재시작 횟수·이미지 ID·revision·env SHA256·HTTP가 유지되는지 전후 비교한 뒤 current만 원자 전환한다. current 전환 전 기존 `deploy-in-progress` 마커를 영속화한다. 후속 검사가 실패하면 이전 current 복원을 시도하고, 링크·앱 상태의 원복까지 확인됐을 때만 마커를 해제한다. 원복이나 확인이 불명확하면 마커와 양쪽 release를 보존해 후속 배포를 차단한다. 전환 전 실패에서 current가 이전 release를 계속 가리키는 것이 확인되면 이번에 만든 불완전 release만 정리하며, 정리 실패는 진단과 함께 보존한다. baseline 마커의 `context`는 operation·previous·destination을 담은 공개 JSON이며 env 백업을 뜻하지 않는다. 남아 있으면 이 기록과 실제 current·컨테이너·env 해시를 대조해 원인을 확인한 뒤 처리한다. 이 전환을 앱 배포나 재시작 성공으로 보고하지 않는다. 호출자는 A/C 양쪽 증거와 ALB healthy, SSM 종료를 확인한 뒤 자신이 소유한 전역 잠금만 해제한다. 결과가 모호하면 잠금을 보존하고 조정자에게 확인한다.

## 운영 검증

실제 검증은 배포 인계와 운영 시험 결과에 케이스별로 기록한다. 정상 CI 산출물 배포 뒤 A/C 동일 digest·ID·revision·플랫폼·HTTP·지표·ALB를 확인한다. 실패 시험에는 A 전용 비공개 Compose의 `entrypoint`에 존재하지 않는 Java 옵션을 넣고 정상 후보 이미지는 변조하지 않는다. 기존 rolling 잠금과 ALB 제외를 거쳐 실제 기동 실패·이전 ECR 이미지와 env 자동 복구·C 배포 중단을 확인한다. 시험은 승인 후 한 번만 실행하고 정상 CI 산출물로 재배포해 최종 A/C 동일 버전을 복원한다.

## 배포 회귀 검증

Python 3.12의 `.github/tests` 회귀 124개에서 metadata 출처·허용 저장소·manifest/config 구분·로컬 revision/플랫폼 거부, 양쪽 사전 준비 순서와 실패 보존, baseline current 원자 전환과 후속 변경 감지 복원, 기존 env 복구·SSM 불명확·Stage tar 경계를 확인했다. Compose v2.40.3 실제 config와 actionlint v1.7.12, Bash 구문 검사를 함께 통과했다. AWS 응답과 Docker 실행은 대역을 사용한 케이스가 포함되며 이 결과를 실제 ECR 게시·운영 배포·자동 복구 성공으로 해석하지 않는다.
