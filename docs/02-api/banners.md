# GD-209 배너 구현·FE 연결 안내

- 상태: 검토 대기 — 사용자 단위 테스트·빌드 성공 보고, 배너·DB 이전 테스트 XML 성공 확인
- 공용 계약: [배너 API](https://github.com/Uplus-GETDDO/getddo-spec/blob/main/05-api/banner.md), [배너 규칙](https://github.com/Uplus-GETDDO/getddo-spec/blob/main/02-domain/banner.md)
- 2026-10-16 사용자가 선택한 기존 URL 행 보존·미연결 관리자 전용 예외를 공용 배너 계약에 함께 반영한다.

## 구조와 이유

Controller는 기존 사용자 문맥·검증·봉투를 사용한다. Service는 활성 권한, 기존 이벤트 상태 잠금 재사용, 최대 개수와 순서, 감사 기록을 처리한다. Entity 변환은 MapStruct를 사용하고, 트랜잭션은 Service에 둔다. 이벤트 담당자의 등록·수정·삭제·취소 코드는 변경하지 않았다.

`BannerOrder`가 ID 순서를 계산하고 저장소가 이를 실제 순서로 반영한다. 등록·수정은 이벤트→카탈로그, 삭제·순서 변경은 카탈로그만 잠근다. JPA는 UUIDv7·Auditing·조회/설정을 담당한다. 다섯 자리가 찬 순서 교환은 기존 CHECK·UNIQUE를 유지하는 JDBC 재삽입으로 처리한다([ADR-0008](../04-decisions/0008-banner-catalog-order-storage.md)).

이미지 URL은 기존 `getddo.images.base-url`에서 만든다. `bucket`이나 비공개 접근 URL을 응답하지 않는다. 설정 누락/형식 오류는 변경 저장 전에 실패하며 가짜 URL로 대체하지 않는다. 실제 업로드 API·파일 형식·용량 규칙을 새로 추가하지 않았다.

## FE에 전달할 사항

- `/api/v1/banners`, `/api/v1/admin/banners`와 `/order`를 사용한다. 목록은 배열이며 기존 봉투를 유지한다.
- 순서는 1부터 시작한다. 등록·이동·삭제 뒤 서버 목록을 다시 받아 표시한다.
- 관리자 목록의 미연결 이전 배너는 `eventId=null`일 수 있다. 신규 등록·수정은 이벤트를 필수 선택한다. 기존 URL은 클릭 연결로 재사용하지 않는다.
- 이벤트 삭제·취소는 공개 목록에서만 숨긴다. FE 목 데이터의 배너 자동 삭제는 새 계약과 맞춰야 한다.
- 이미지 파일 업로드와 실제 CDN 키·주소 검증은 담당자 후속이며 이번 BE 코드 작성으로 완료되지 않는다.

## 검증 상태와 실행 명령

작성: 입력·순서 단위 테스트, Service 권한·감사·업무 오류, 공개 이미지 주소, 실제 MVC/MySQL CRUD·다섯 자리 교환·이전 행·동시 등록·감사 실패 롤백, V027→V028 이전 테스트. 기존 마이그레이션 검증의 테이블 수를 신규 잠금 테이블에 맞춰 46개로 갱신했다.

이번에는 테스트·빌드·컴파일을 실행하지 않았다. 정적 문법 검사와 diff 검사는 실행 검증을 대신하지 않는다.

저장소 루트에서 실행한다.

```bash
./gradlew :core:test --tests 'com.getddo.core.banner.*' :api:test --tests 'com.getddo.api.banner.*'
./gradlew :api:integrationTest --tests 'com.getddo.api.banner.BannerApiIntegrationTest' :storage:db:integrationTest --tests 'com.getddo.db.banner.BannerMigrationIntegrationTest' --tests 'com.getddo.db.migration.MySqlMigrationTest'
./gradlew test build --init-script gradle/test-limits.init.gradle --max-workers=1 --no-parallel
```

MySQL 통합 테스트에는 Docker가 필요하다. 마이그레이션 버전 충돌은 PR 준비 직전 최신 dev와 다시 확인한다. 커밋·푸시·PR은 사용자 테스트 성공 확인 뒤 진행한다.

## 사용자 실행 실패 확인과 수정 — 2026-10-16

사용자가 실행한 결과 파일을 읽었다. core 단위855개와 api 단위331개 XML은 실패·오류0이었다. 진행 중 API 통합 결과의 실제 실패 정보에서는 배너 순서 교환 후 생성 시각의9시간 이동, 이전 배너 시각의 반복 이동과 감사 실패 테스트의 예외 타입 불일치를 확인했다. 정상적인 실패 경로 테스트가 출력한 ERROR 로그만으로 전체 실패를 판정하지 않았다.

운영 저장소의 Timestamp 전달을 UTC LocalDateTime으로 바꿨다. MySQL DATETIME 재삽입에서 JDBC/JVM 기본 시간대로 변환되는 것을 막는다. 배너 테스트 시드도 같은 UTC 기준으로 변경했다. 감사 저장 실패는 Spring의 DataAccessException 변환 후 원래 IllegalStateException과 메시지까지 단언한다. 반복 순서 교환의 생성 시각 불변·수정 시각 UTC 범위 회귀 테스트를 추가했다. 수정 후 정적 문법·diff 검사만 했으며 에이전트가 테스트·빌드를 실행하지 않았다. 사용자 재실행 대기다.

## 수정 후 사용자 재실행 결과 — 2026-10-16

사용자가 통과를 알려준 뒤 실제 XML을 확인했다. BannerApiIntegrationTest는12개·실패0·오류0·건너뜀0으로 성공했다(실행54.815초, 결과시각11:08 KST). 반복 순서 교환 UTC 회귀와 감사 실패 롤백을 포함한다. 이 실행의 API 통합 결과는 해당 클래스1개뿐이며 전체 통합 성공으로 표현하지 않는다. V027→V028 이전 테스트와 전체 test/build는 아직 확인되지 않았다. 에이전트는 테스트를 실행하지 않았다. 전체 검증 후 커밋·푸시·PR을 진행한다.

## PR 게시 전 검증 상태 — 2026-10-16

사용자가 통합 테스트를 제외한 `test build` 성공을 보고했다. 보존된 XML은 단위 core855/api331/DB50 총1,236개, API 통합228개, DB 통합794개가 각각 실패·오류·건너뜀0이었다. 배너 API12개·V027→V028 이전1개·MySqlMigrationTest5개도 포함돼 있다. 이 집계는 작업 폴더에 남은 결과 파일을 읽은 것이며, 이번 통합 제외 명령이 모든 통합 테스트를 다시 실행했다는 의미가 아니다.

이 로컬 검증의 기반 dev는15f2d95c다. 게시 시 최신 dev의 게임 결과·배포 변경을 병합하며, 병합 후 전체 테스트는 재실행하지 않고 새 PR CI에서 확인한다. 실제 FE·이미지 업로드·운영 DB 적용은 미확인이다.

## PR #110 리뷰 반영 — 2026-10-17 KST

리뷰 수정 커밋 b1b223a8의 기록 시각은 `2026-10-17T08:52:22+09:00`이다. UTC로는 2026-10-16이며, 이 문서의 날짜는 프로젝트 표시 기준인 KST를 사용한다.

등록·수정 시 공개 이미지 기준 경로 밖으로 이동시키는 `.`·`..` 세그먼트를 거절하고 정상 파일명의 점은 허용한다. 같은 ID 목록이라도 이전 순서4번을1번으로 정리하는 등 실제 저장 순서가 바뀌면 전후 감사 기록을 남긴다. 변경 없는 비어 있지 않은 목록은 감사 기록을 추가하지 않는다. 두 동작의 회귀 테스트를 추가했다.

개인 작업 경로를 검증 명령에서 제거하고 ADR에 기존 사용자 검증과 PR 상태를 반영했다. 기존 head28e4ed05의 CI 빌드·테스트·이미지 빌드 성공 기록은 유지한다. 이후 b1b223a8의 [빌드·테스트](https://github.com/Uplus-GETDDO/getddo-be/actions/runs/38983696182/job/117250146682)와 [이미지 빌드](https://github.com/Uplus-GETDDO/getddo-be/actions/runs/38983696182/job/117252419811)도 각각 SUCCESS로 확인했다. 로컬 테스트·컴파일·빌드는 에이전트가 재실행하지 않았다. 이 날짜·검증 기록 정정 커밋의 CI는 별도로 확인한다. 이벤트→카탈로그 잠금 순서 개선은 사용자 결정대로 후속 검토로 남긴다.
