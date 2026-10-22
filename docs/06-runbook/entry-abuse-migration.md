# GD-142 차단 이력 스키마 전환

- 상태: 검토 대기 — Codex 작성, 담당자 확인 전
- 대상: `V030__convert_abuse_cases_to_entry_blocks.sql`과 AR01. 이 문서 작성으로 실제 DB 전환·배포가 완료된 것은 아니다.
- 정책·공개 계약 원본: [응모 차단 이력 API](https://github.com/Uplus-GETDDO/getddo-spec/blob/main/05-api/abuse.md)

## 적용 전

1. 대상 DB와 Flyway 적용 이력을 확인한다. V029까지의 기존 스키마와 일치해야 한다.
2. 기존 `abuse_cases` 생산자의 쓰기를 중지하고 `SELECT COUNT(*) FROM abuse_cases`가 0인지 확인한다. 0이 아니면 중단한다. 자동 DELETE·TRUNCATE·과거 행 변환은 하지 않는다.
3. 백업과 복구 방법을 확인한 뒤 배포 절차에서 Flyway를 실행한다. 이번 작업에서는 DB·배포에 접속하지 않았다.

## 최초 도입 범위와 자동 배포의 차단 경계

2026-10-22 사용자가 확인한 [GD-141 최초 도입 범위](https://github.com/Uplus-GETDDO/getddo-be/blob/ce40782dab68d332f3740aded0f7b8cd2caa8ff5/docs/03-database/entry-abuse-blocking.md#최초-도입과-향후-운영-전환의-구분)는 실제 서비스 어뷰징 데이터가 없는 최초 도입이며 기존 CD 워크플로 변경을 포함하지 않는다. 이 전제는 실제 배포 DB가 비어 있음을 대신 증명하지 않는다. 배포 담당자는 위 사전 점검으로 전제를 확인해야 하고, 실제 트래픽·기존 데이터가 있는 환경이면 이번 전환을 적용하지 않는다.

현재 코드의 abuse 경계에는 조회만 있고 INSERT·저장 작업자·스케줄러가 없다. GD-144·GD-291의 신규 생산자는 후속이다. 외부 도구·별도 버전의 쓰기가 실제로 없는지는 배포 환경에서 확인해야 한다. V030은 수동 COUNT 결과만 신뢰하지 않고 첫 ALTER의 검증과 임시 CHECK로 DB 내부에서 0행 및 전환 중 쓰기 금지를 강제한다. 행이 있으면 Flyway가 실패하므로 자동 CD에서도 조건을 무시하고 새 앱 기동을 완료할 수 없다. 기존 `deploy-container.sh`는 앱 건강 확인 실패를 배포 실패로 처리하고 복구를 시도한다. 앱 복구와 MySQL DDL 복구는 별개이므로 아래 부분 실패 절차는 여전히 필요하다.

운영 트래픽이 있는 환경을 위한 무중단 사전 점검·별도 승인 게이트는 이번 합의 범위를 넘어선다. 새 CD·인프라 정책을 이 PR에서 임의로 추가하지 않으며, 그 환경으로 범위를 확대할 때 인프라 담당자와 별도 설계한다.

## 마이그레이션 보호 장치

첫 ALTER는 `CHECK (id IS NULL)` 임시 제약을 추가한다. id는 NOT NULL이므로 기존 행이 하나라도 있으면 검증에 실패한다. 성공한 경우에도 두 번째 ALTER가 끝날 때까지 새 INSERT를 거절한다. 두 번째 ALTER는 같은 DDL에서 임시 제약을 제거하고 새 필수 컬럼·UNIQUE·CHECK·조회 인덱스를 적용한다. 기존 Flyway 파일은 수정하지 않는다.

MySQL DDL은 전체 마이그레이션 트랜잭션으로 롤백되지 않는다. 두 번째 ALTER가 실패하면 첫 ALTER의 임시 제약과 Flyway 실패 상태가 남을 수 있다. 쓰기를 중지한 채 실제 스키마·제약·실패 이력을 확인하고 원인을 해결한다. 임시 제약 삭제나 Flyway repair는 상태를 확인한 담당자가 복구 방식을 결정한 뒤 수행한다. 자동으로 guard를 제거하거나 재시도하지 않는다. 첫 ALTER부터 다시 실행하면 이미 존재하는 제약 이름과 충돌할 수 있으므로 실패 원인 해결 없이 단순 재실행하지 않는다.

## 적용 후

- 제거 대상은 detection_key·request_id·review_* 및 관련 UNIQUE·검토 CHECK·reviewed_by FK다. event_id와 기존 출석·미션·게임 FK는 보존한다.
- source_type·blocked_at·blocked_until·evidence 필수값, `(user_id, source_type, blocked_at)` 중복 방지, 차단 종료 > 시작, ENTRY의 원본 FK NULL·고정 탐지 유형/사유를 확인한다.
- [수동 데모 시드](../03-database/seeds/entry-abuse-demo.sql)는 Flyway 밖에 있다. 대상 격리 DB에서 기존 데모 사용자 UUID와 선택적인 이벤트 UUID를 지정한 뒤 별도로 실행한다. 미지정 사용자·없는 FK·이미 사용한 시드 ID는 실패하며 기존 행을 덮어쓰지 않는다. 이번 작업에서는 시드를 실행하지 않았다.
- AR01은 사용자 닉네임·이벤트 제목을 현재 데이터에서 조인하며, 이벤트 없는 행도 보존하고 만료 이력도 반환한다. 시드 조회는 GD-144·GD-291의 실제 자동 차단·저장 완료 검증을 대신하지 않는다.
- GD-143은 기존 V030 스키마를 재사용해 AR02 상세를 추가한다([조회 구현](../02-api/abuse-cases.md)). GD-144 Redis 생산자·GD-291 저장 작업자는 후속이다. 기존 테스트 시드는 새 스키마로 수정했으며 이번 사용자 요청으로 통합 테스트와 실제 마이그레이션 검증은 실행하지 않는다.

## 이번 변경의 검증 상태 — 2026-10-22

- 새 어뷰징 단위 테스트 25건 통과. 전체 단위 테스트 1,608건 통과(실패·오류·건너뜀 0).
- `./gradlew test build -x :api:integrationTest -x :storage:db:integrationTest --no-daemon --max-workers=1 --no-parallel` 성공. Java 21로 실행했다.
- 통합 테스트는 사용자 요청으로 실행하지 않았다. 실제 MySQL에서의 V030 성공·비어 있지 않은 테이블 거절·DDL 실패 복구·JPQL 조인·UTC 왕복, 데모 시드 적용과 실제 HTTP·배포 실측은 미검증이다. 기존 추첨·통계 통합 테스트의 입력은 새 스키마에 맞췄으나 해당 테스트를 실행한 것은 아니다.

CodeRabbit 후속으로 실제 MySQL 조회·매핑·기간 경계·동시각 ID 정렬·제약과 비어 있지 않은 테이블의 V030 무삭제 거절 테스트를 추가했다. 로컬에서는 해당 통합 테스트를 실행하지 않았으며 `./gradlew :storage:db:compileIntegrationTestJava --no-daemon --max-workers=1 --no-parallel` 컴파일에 성공했다. 컴파일 성공을 DB 동작 검증 완료로 표시하지 않는다.
