# 매일 오전 7시 dev → main 자동 릴리스

- 상태: 검토 대기 · Codex 작성 · 담당자 확인 전
- 관련: GD-115, GD-240
- 실행: 한국 시간 매일 오전 7시(UTC 22:00). GitHub 예약 실행은 지연되거나 누락될 수 있어 정각 실행을 보장하지 않는다. 실제 배포 완료 시각에는 후속 Build·Container CD 시간도 포함된다.

## 동작

기본 브랜치인 dev에 [예약 워크플로](../../.github/workflows/daily-release.yml)가 반영되고 활성화되면 [daily-release.py](../../infra/scripts/daily-release.py)가 다음 순서로 처리한다.

1. 최신 dev·main 커밋을 조회한다. main에 이미 포함된 dev라면 건너뛴다.
2. 그 dev 커밋의 가장 최근 push Build가 완료·성공이고, `빌드·테스트`, `이미지 / 이미지 빌드 검증`, `이미지 / Docker 이미지` 작업도 모두 완료·성공인지 확인한다. dev에서 정상적으로 생략되는 `main 이미지` 게시 작업은 이 필수 검증에서 제외한다. 필수 작업의 누락·실패·취소·생략은 허용하지 않는다. CI가 없거나 실패·진행 중이면 오늘 배포를 건너뛴다. 과거 커밋의 성공이나 PR CI 성공으로 대체하지 않는다.
3. 같은 저장소의 dev → main PR을 재사용하거나 생성한다. 새 PR은 `[chore] GD-115 매일 dev 변경 main 반영` 제목을 사용한다.
4. 초안·종료된 PR, 다른 head·base·저장소·커밋, 충돌 또는 병합 가능 여부 미확인은 건너뛴다. GitHub의 병합 가능 여부 계산은 최대 1분만 기다린다.
5. dev·main이 조회한 상태 그대로인지, dev CI가 여전히 성공인지 다시 확인한다. merge API에도 검증한 dev SHA를 전달해 다른 head 커밋의 병합을 막는다.
6. 검증한 main 릴리스 PR에 `skip-review` 라벨을 적용하고 전용 GitHub App으로 merge commit을 만든다. 해당 main push가 기존 Build를 실행하고, 성공하면 [Container CD](container-cd.md)가 서버에 배포한다.

PR CI·봇 리뷰 완료를 기다리는 흐름은 아니다. 사용자가 승인한 정기 릴리스는 검증된 dev를 리뷰 승인 없이 main에 반영하며, main의 새 병합 커밋은 다시 Build를 통과해야 배포된다. 새 필수 Parameter Store 값과 호환되는 DB 마이그레이션은 dev 병합 전에 준비해야 한다. API 병합 직전 최종 조회와 병합 사이의 main 변경을 원자적으로 잠그지는 못하므로 main의 Build와 CD는 계속 최종 배포 관문으로 유지한다.

## 사용자 승인 범위와 병합 방식

2026-10-16 사용자가 “매일 오전 8시에 dev ci 성공하면 main에 바로 합쳐지고 바로 배포”하는 방식을 요청하고, main PR의 자동 릴리스 예외를 안내받은 뒤 구현을 지시했다. 이 운영 지시는 **dev → main 정기 릴리스**에만 적용한다. 일반 기능 PR → dev는 공용 workflow.md의 리뷰·Squash and Merge 흐름을 유지한다.

2026-10-19 사용자는 정각 배포 완료가 필수는 아님을 확인하고, GitHub 예약 실행 지연을 고려해 예약 시각을 오전 7시로 한 시간 앞당기도록 요청했다(GD-240). dev CI 확인·병합·후속 배포 조건은 유지한다. 기본 브랜치 dev에 변경이 병합된 뒤부터 새 예약이 적용된다.

장기 브랜치 dev → main은 merge commit으로 조상을 보존한다. 여기까지 squash하면 이미 배포한 dev 커밋도 다음 비교에서 미반영으로 남아 변경 없는 재배포를 만들 수 있다. main PR의 리뷰 예외는 사용자 요청으로 적용한 저장소 운영 예외이며 공용 문서 전체의 정책을 변경하지 않는다.

## 배포 성공 후 버전 발행

Container CD의 서버 교체·응답 검증이 성공하면 별도 `publish` 작업에서 [publish-release.py](../../infra/scripts/publish-release.py)를 실행한다. main의 수동 병합 배포도 같은 방식으로 버전을 발행한다.

- 첫 버전은 `v0.1.0`, 이후 가장 높은 정식 `v숫자.숫자.숫자` 태그·Release의 패치 번호를 1 올린다. 예: v0.1.9 → v0.1.10. 사람이 v0.2.0 또는 v1.0.0을 만들면 그 버전부터 패치를 증가시킨다.
- 이미 배포한 **같은 커밋**에 정식 버전·Release가 있으면 재사용한다. 배포 재실행만으로 새 버전을 만들지 않는다.
- 실제 서버에 배포한 SOURCE_SHA에 태그를 먼저 생성하고 GitHub Release를 발행한다. 컨테이너 이미지는 계속 SHA로 식별하며 태그가 그 커밋을 가리킨다.
- Release 본문에 배포 커밋과 GitHub가 생성한 PR·변경 노트를 기록한다. 첫 릴리스에는 이전 정식 릴리스 이후라는 비교 기준이 없다.
- 태그 생성 이후 Release 발행에 실패하면 태그를 유지하며 같은 커밋 재실행에서 같은 태그로 다시 발행한다. 기존 태그를 삭제·이동하거나 초안·사전 릴리스를 임의로 공개하지 않는다.
- 동명 태그 선점은 API 오류로 중단하여 다른 커밋을 버전으로 발행하지 않는다. CD 전체 실행이 직렬화되어 자동 작업 사이의 버전 경쟁을 막는다.
- Contents 쓰기 권한은 `publish` 작업의 GITHUB_TOKEN에만 부여한다. 서버 배포 작업에는 기존 읽기·OIDC 권한을 유지한다. App 키를 production 환경에 중복 등록할 필요가 없다.
- 변경 없음 또는 서버 배포 실패 시 버전을 만들지 않는다. **서버 배포 성공 후 버전 발행만 실패하면 CD는 실패로 표시되지만 서버 앱은 이미 새 버전이다.** 자동 롤백하지 않으며 publish 작업을 재실행하거나 같은 SHA의 배포를 재실행한다. 해당 SHA의 CD 실행을 기록으로 확인한 뒤에만 수동 버전 발행을 진행한다.

## 한 번 필요한 GitHub 설정

기존 GitHub 규칙 `protect-main-dev`는 main·dev에 리뷰 2개, 대화 해결, 삭제·강제 push 방지 등을 함께 적용했다. 이 작업에서 `protect-dev`·`protect-main-base`·`protect-main-review`로 분리하고 App ID `5345517`에 main PR 리뷰 예외만 적용했다. dev의 리뷰 2개와 main의 삭제·강제 push 방지는 유지한다. 일반 GITHUB_TOKEN으로 병합하면 후속 main push Build가 실행되지 않으므로 전용 App을 사용한다.

### 전용 GitHub App

조직 관리자 권한으로 App을 생성하고 `Uplus-GETDDO/getddo-be` 저장소 하나에만 설치한다. 서버·AWS 권한은 필요 없다.

- 이름 예: `getddo-daily-release`
- Homepage URL: 저장소 주소
- Webhook: 비활성화. 예약 워크플로가 호출하므로 별도 서버는 필요 없다.
- Repository permissions: Contents **Read and write**, Pull requests **Read and write**, Actions **Read-only**, Workflows **Read and write**. Metadata는 기본 읽기 권한이다.
- Workflows 쓰기 권한은 workflow 파일이 포함된 dev 변경도 main에 병합하기 위해 필요하다.
- App ID를 확인하고 private key를 생성한다. 키 원문은 채팅·Git·PR에 올리지 않는다.

### Environment와 변수

전용 `daily-release` Environment를 사용한다. Deployment branches and tags는 **Selected branches and tags → Branch: dev**만 허용한다. 자동 실행을 위해 Required reviewers나 Wait timer를 설정하지 않는다. production 환경의 기존 설정은 유지한다.

| 위치 | 이름 | 값 |
| --- | --- | --- |
| Repository Actions Variable | DAILY_RELEASE_ENABLED | 준비 중 `false`, 모든 설정을 마치고 검사한 뒤 `true` |
| daily-release Environment Variable | DAILY_RELEASE_APP_ID | 전용 App의 숫자 App ID |
| daily-release Environment Secret | DAILY_RELEASE_APP_PRIVATE_KEY | 생성한 PEM private key 전체 |

토큰 발급은 현재 저장소로 제한하고 작업 종료 시 토큰을 폐기한다. `daily-release` 환경의 dev 제한으로 다른 ref에서 이 키를 사용하는 실행을 막는다. 워크플로도 dev ref에서만 실행한다.

### 보호 규칙 분리

기존 규칙 전체에 App을 예외로 추가하면 dev의 리뷰와 삭제·강제 push 방지까지 우회하게 된다. 다음처럼 분리한다. 기존 보호를 먼저 제거하지 않고 추가 규칙부터 만든 뒤 기존 규칙을 조정한다.

1. **dev 보호:** 기존과 같은 규칙을 dev에 유지한다. 리뷰 2개·대화 해결·삭제·강제 push 방지와 상태 검사 설정을 그대로 복사하며 App 예외를 두지 않는다.
2. **main 공통 보호:** 기존 규칙 중 pull_request 외의 규칙을 main에 적용한다. App 예외를 두지 않아 삭제·강제 push 방지와 설정된 상태 검사 조건을 계속 적용한다.
3. **main PR 리뷰:** 기존 pull_request 규칙을 main에 적용하고 전용 App만 **For pull requests only** 예외로 지정한다. 사람의 PR은 기존 리뷰 2개·대화 해결 요구를 유지한다.

GitHub 규칙의 App 예외는 PR의 head=dev 조건까지 제한하지 못한다. 전용 App을 다른 자동화에 재사용하지 않고 키를 위 환경에만 보관한다. 코드의 대상은 dev → main으로 고정한다. 현재 필수 상태 검사 목록은 비어 있으며, 예약 스크립트가 dev Build의 실제 작업 성공을 검사한다. 필수 상태 검사를 추가하면 App에도 적용되므로 해당 PR의 검사가 완료될 때까지 merge API가 거절할 수 있다.

## 검증과 활성화

1. 기능 PR을 리뷰·CI 통과 후 dev에 병합한다. 예약 실행은 기본 브랜치에 파일이 있어야 시작한다.
2. App 설치·환경 변수·Secret·보호 규칙 분리를 준비한다.
3. `DAILY_RELEASE_ENABLED=false`를 유지한다.
4. Actions → Daily Release → Run workflow → **dev**, `dry_run=true`로 실행한다. 수동 dry-run은 자동 실행이 꺼져 있어도 허용한다. PR 생성·병합 없이 조회만 한다. 수동 실행은 기본값이 dry-run이다.
5. App 토큰 발급과 dry-run 성공을 확인한 뒤 `DAILY_RELEASE_ENABLED=true`로 바꾼다. 준비된 dev 변경이 있을 때 수동 실행의 dry_run을 false로 바꾸거나 다음 오전 7시 실행을 확인한다. dev 변경이 없으면 정상적으로 건너뛴다.
6. `RELEASE_MERGED`에 기록된 main 커밋의 Build·Container CD와 서버 실행 이미지·HTTPS 응답, 버전 태그·GitHub Release가 같은 커밋을 가리키는지 확인한다. 예약 워크플로 성공 자체가 서버 배포 성공을 의미하지 않는다.

`RELEASE_SKIPPED`는 변경 없음·CI 미완료·충돌·브랜치 변경 등에 따른 건너뛰기다. `RELEASE_FAILED`는 API·인증·보호 규칙 등의 점검이 필요한 실패이며 작업을 실패로 표시한다. GitHub Actions 알림 설정에서 이 저장소의 실패 실행을 구독한다. Slack·메일 전송이나 CloudWatch 알람은 이 작업에 추가하지 않는다.

중지할 때는 `DAILY_RELEASE_ENABLED=false`로 바꾼다. 진행 중 실행은 Actions에서 별도로 취소해야 한다. 이미 병합된 main의 Build·Container CD를 자동 취소하는 스위치는 아니다. main 수동 병합은 기존처럼 즉시 배포를 시작한다. 배포 실패 복구는 기존 CD 절차를 따르며 main 커밋·DB·Parameter Store 원격 값의 자동 롤백을 뜻하지 않는다.

## 참고

- [GitHub 예약 실행과 기본 브랜치](https://docs.github.com/en/actions/reference/workflows-and-actions/events-that-trigger-workflows#schedule)
- [GITHUB_TOKEN의 후속 실행 제한](https://docs.github.com/en/actions/concepts/security/github_token)
- [공식 GitHub App 토큰 액션](https://github.com/actions/create-github-app-token)
