# GD-308: PR 이미지의 Staging 배포

- 상태: 검토 대기. 실제 배포 성공 기록은 후속 실환경 검증 후 작성한다.
- 범위: 같은 저장소 PR의 고정 head 이미지·Stage 설정/로그 분리. 운영 main CD 조건·잠금·기본값은 유지한다.

## 사전 조건과 산출물

같은 저장소 PR의 Build가 성공하면 Container Image 작업이 PR head SHA의 이미지를 빌드하고 `staging-image-<head SHA>-<run attempt>` 아티팩트를 보관한다. fork PR은 Stage 아티팩트를 게시하지 않는다. 이미지 작업에 AWS 권한을 추가하지 않는다. main push의 기존 운영 아티팩트와 CD 조건은 유지한다.

정확한 PR head SHA와 그 실행의 `image.tar.gz`, `image.tar.gz.sha256`, `image-id.txt`를 내려받는다. image ID는 Docker config digest이며 registry manifest digest와 구분한다. 배포 스크립트는 archive SHA256, Docker config digest, OCI revision, SHA 이미지 태그를 각각 확인한다. 소스 경로는 동일 SHA의 깨끗한 checkout이어야 한다.

## 호출 계약

```bash
python3 infra/scripts/deploy-staging.py \
  --manifest stage-deploy.json \
  --source-sha <40자리-PR-head-SHA> \
  --image-id sha256:<Docker-config-digest> \
  --archive-sha256 <image.tar.gz-SHA256> \
  --artifact-dir <검증한-이미지-파일-디렉터리> \
  --check
```

`--check`는 읽기 전용 경계 검증이다. 제거하면 두 Stage 서버에 순차 배포한다. JSON manifest는 다음 필드만 허용한다.

- `account_id`, `region`(`ap-northeast-2`), `vpc_id`, `run_id`
- `instances`: `a`, `c` 각각의 정확한 EC2 ID
- `expected_names`: `a=getddo-staging-api-a`, `c=getddo-staging-api-c`
- `target_group_arn`: Stage internal HTTP ALB의 instance:8080 Target Group
- `deploy_bucket`: `getddo-deploy-<account_id>-ap-northeast-2`

대상 EC2의 상태·VPC·이름·Environment=staging, SSM Online, ALB의 internal·VPC·Stage 태그, TG 포트·태그·기존 대상과 버킷 소유 계정·리전을 확인한다. 공유 배포 버킷의 환경 태그는 변경하지 않으며 운영 잠금을 사용하지 않는다.

## 설정과 배포 경계

Stage 앱은 `/getddo/staging/app/`의 기존 필수 14개 항목을 읽는다. DB/Redis 비밀번호는 SecureString이어야 하며 RDS `VERIFY_IDENTITY`, Redis TLS·AUTH와 기존 값 검증을 유지한다. 로그 그룹은 `/getddo/staging/api`, metric environment는 `staging`, server는 `a/c`다. 설정값을 shell source/eval로 실행하지 않는다.

Stage 드라이버가 `GETDDO_DEPLOY_ENVIRONMENT=staging`을 원격 배포에 전달한다. 기존 운영 호출은 명시하지 않으면 dev 설정·로그·라벨을 유지한다. production deploy-ssm.py의 main-only 가드는 변경하지 않는다.

S3 경로는 `staging/<run_id>/releases/<source_sha>/<attempt>/`와 `staging/locks/app.json`으로 제한한다. 재배포 attempt는 고유하며 기존 원격 release 디렉터리를 재사용하지 않는다. 서버 실패/원격 결과 불명/최종 두 대상 정상 실패에서는 다음 배포를 중단하고 Stage 잠금을 유지한다. 기존 배포 엔진의 서버별 rollback을 사용하며 데이터 rollback을 수행했다고 해석하지 않는다.

두 서버의 최종 healthy 상태와 Stage 잠금 삭제까지 성공한 뒤에만 `STAGING_DEPLOY_OK`를 출력한다. 잠금이 남으면 SSM 결과·현재 버전·대상 상태를 확인하고 임의 재시도하지 않는다. Stage 환경 자체의 실패 정리·최대 유지시간·결과 보관은 Infra의 GD-306/GD-311 수명주기에서 수행한다.

## 검증 범위

회귀 테스트는 잘못된 계정/대상/설정·archive/config digest·revision·운영 기본값 보존·실패 시 다음 서버 중단·잠금 보존·반복 시도 경로 분리·최종 두 서버 상태·잠금 삭제 실패의 성공 오보고 방지를 검증한다. 대역 검증이며 실환경 배포 증거는 아니다.

PR 리뷰 수정을 완료한 최종 SHA에서 실제 Stage 기동·작은 부하·관측·결과 보관·환경 삭제/잔존 확인 결과를 PR에 남기고 팀원 1명 승인 대기로 인계한다. 병합을 우회하지 않는다.

Stage 배포 잠금은 실행 ID와 무관한 `staging/locks/app.json`을 공유하며 `run-id` 메타데이터로 소유 실행을 기록한다. 대상 EC2·ALB·TG는 모두 같은 RunId 태그여야 한다. 재배포는 각 대상을 먼저 제외하고 드레이닝 완료 후 교체한다. 실패 시 잠금을 유지하며, 인프라 삭제에서 해당 RunId 소유 확인 후 잠금을 정리한다.
