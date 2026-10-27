# ADR-0001: 공통 Entity의 UUID와 시간 필드

- 상태: 채택
- 작성자 / 날짜: 이경주 / 2026-09-24
- 검토자: 이경주
- 관련 작업 기록 / PR: [GD-22](https://ureca4.atlassian.net/browse/GD-22)
- 대체한 ADR / 대체된 ADR: 해당 없음

## 배경

Entity의 식별자와 생성·수정 시각을 공통화한다. UUID 사용과 MySQL의 `BINARY(16)` 저장을 전제로 UUID 버전과 수정 시각의 공통화 범위를 결정한다. 시간 정책은 [getddo-spec](https://github.com/GETDDO/getddo-spec/blob/main/00-requirements/functional-requirements.md#공통-시간-기준과-화면-표시)을 따른다.

## 결정과 이유

### UUID v7을 선택한 이유

UUID v4도 인덱스를 사용할 수 있지만 무작위 값이라 새 키의 삽입 위치가 인덱스 전반에 흩어진다. v7은 앞부분에 밀리초 단위 시각을 담아 시간순에 가까운 정렬이 가능하다. UUID를 유지하면서 삽입 위치의 분산을 줄이는 효과를 기대해 v7을 선택한다. [RFC 9562](https://www.rfc-editor.org/rfc/rfc9562.html#section-2.1)

MySQL InnoDB는 PK를 기준으로 행 데이터를 구성하므로 PK의 삽입 특성이 중요하다. [InnoDB 인덱스](https://dev.mysql.com/doc/refman/8.4/en/innodb-index-types.html)

ID는 JPA 영속화 시 Hibernate로 생성하고 MySQL 기본 매핑으로 `BINARY(16)`에 저장한다. 저장 전에는 null이며 이후 변경하지 않는다.

### updated_at을 분리한 이유

생성 시각만 필요한 기록에 수정 시각 컬럼과 갱신 규칙까지 강제하지 않기 위해 두 클래스로 나눈다.

| 클래스 | 공통 필드 | 사용 기준 |
| --- | --- | --- |
| `BaseEntity` | `id`, `createdAt` | 생성 시각만 필요한 Entity |
| `BaseUpdatableEntity` | 상속한 필드 + `updatedAt` | 마지막 수정 시각도 필요한 Entity |

두 클래스는 `storage:db`의 `com.getddo.db.common.entity`에 둔다. 시간 타입은 `Instant`이며 `JpaAuditingConfig`가 기존 공통 `Clock`을 연결한다. 수정 시각이 있는 Entity는 최초 저장 시 `createdAt`과 `updatedAt`이 같다.

## 검토한 대안

UUID 사용을 전제로 v4와 v7을 비교했다.

| 선택지 | 특징과 판단 |
| --- | --- |
| UUID v4 | 무작위 UUID. 인덱스 사용은 가능하지만 삽입 위치가 분산되어 선택하지 않았다. |
| UUID v7 | 시간순에 가까운 UUID. UUID 사용 조건을 유지하면서 인덱스 삽입 위치의 분산을 줄이기 위해 선택했다. |

`updated_at`을 `BaseEntity` 하나에 모두 넣는 대안은 구조가 단순하지만, 생성 시각만 필요한 Entity에도 불필요한 컬럼이 생겨 선택하지 않았다.

## 영향과 재검토 조건

- UUID v7은 업무 처리 순서를 보장하지 않는다. 업무 시각은 별도의 `createdAt`·`updatedAt`으로 관리한다.
- `BaseEntity` 상속만으로 수정·삭제가 금지되지는 않는다. 이력의 변경 제한은 각 도메인에서 처리한다. bulk JPQL·native SQL에는 자동 시간 기록이 적용되지 않는다.
- 결정 당시 JPA 영속화·Auditing 테스트는 H2 기반이었다. 2026-09-29 초기 세팅 보완(`f48ecc9`)에서 H2 의존성을 제거하고 Testcontainers MySQL 8.4로 전환했다. 현재 공통 Entity 테스트는 UUID v7·`BINARY(16)` 저장과 생성·수정 시각 동작을 확인한다. 도메인별 FK 매핑과 시각 정밀도의 세부 검증은 해당 기능 구현 범위에서 다룬다.
- 예외: 응모권 Entity(`TicketEntity`, `TicketHistoryEntity`)는 `BaseEntity`를 상속하지 않고 ID(UUID v7)만 공통 방식으로 생성한다. `@CreatedDate`는 영속화 시점에 시계를 다시 읽어 서비스가 구한 지급 시각을 덮어쓰는데, 응모권의 `created_at`은 만료 시각을 계산한 지급 시각이자 지급 이력의 처리 시각과 같아야 하기 때문이다. 그래서 지급 시각을 서비스가 한 번 구해 마이크로초로 잘라 응모권·이력의 시각 필드에 직접 넣는다. 응모권의 지급 시각 처리 예외는 위 두 Entity에 한정한다.
- 예외: 응모 Entity(`EventEntryEntity`, `EventParticipantEntity`)도 `BaseEntity`를 상속하지 않는다. 응모의 ID는 서버가 만들지 않고 클라이언트가 요청 전에 정해 `Idempotency-Key` 헤더로 보낸 UUID를 그대로 PK로 쓴다. 같은 키의 재요청을 새 응모로 처리하지 않으려면 저장 전에 ID가 필요하고, 위 "저장 전 ID가 필요한 요구사항"에 해당한다. 그래서 `EventEntryEntity`는 `Persistable`로 새 행 여부를 알려 INSERT를 강제하고 ID를 Hibernate로 생성하지 않는다. `EventParticipantEntity`의 ID는 UUID v7을 공통 방식으로 생성한다. 두 Entity의 `created_at`은 응모 접수 시각과 같아야 하므로, 응모권 Entity와 같은 이유로 `@CreatedDate`에 맡기지 않고 서비스가 구한 접수 시각(마이크로초로 자른 값)을 직접 넣는다. 이 예외는 응모 두 Entity에 한정한다.
- 예외 추가(2026-10-13, GD-175 사용자 승인): 미션 제출 Entity(`MissionSubmissionEntity`)도 `BaseEntity`를 상속하지 않는다. 제출 ID는 서버가 만들지 않고 클라이언트가 요청 전에 정해 `Idempotency-Key` 헤더로 보낸 UUID를 그대로 PK로 쓴다(사용자가 "`Idempotency-Key`를 제출 ID로 쓴다"를 선택). 같은 키의 재요청을 새 제출로 처리하지 않으려면 저장 전에 ID가 필요해 "저장 전 ID가 필요한 요구사항"에 해당한다. 그래서 응모 Entity와 같은 방식으로 `Persistable`로 새 행 여부를 알려 INSERT를 강제하고 ID를 Hibernate로 생성하지 않는다. `created_at`은 서버가 제출을 받은 시각이어야 하므로 `@CreatedDate`에 맡기지 않고 서비스가 구한 접수 시각(마이크로초로 자른 값)을 직접 넣는다. 이 예외는 `MissionSubmissionEntity`에 한정하며 같은 미션 테이블의 다른 Entity(문항·선택지·답변·정답 진행·보상 청구)는 공통 Entity를 따른다.
- 예외 추가(2026-10-12, GD-137 사용자 승인): `DrawCheckEntity`는 `BaseEntity`를 상속하지 않고 ID(UUID v7)만 공통 방식으로 생성한다. 기존 `draw_checks`에는 `created_at`이 없고 검증 시각인 `checked_at`을 기록한다. 검증 Service가 정한 시각을 그대로 저장하며 공통 생성 시각을 추가하기 위한 스키마 변경은 하지 않는다. 필수값은 생성자에서 검사한다. 이 예외는 `DrawCheckEntity`에 한정하며, 여기에 명시하지 않은 Entity는 공통 Entity를 따른다.
- 예외 추가(2026-10-13, GD-169 사용자 승인 기록·검토 대기): `DrawPublicationEntity`와 `DrawResultConfirmationEntity`는 기존 UUID v7과 업무 시각 `published_at`·`confirmed_at`을 유지한다. 기존 V007에는 `created_at`이 없으며 상속만 추가해 없는 컬럼을 매핑하지 않는다. 승인 범위와 공용 문서 반영 상태는 [PR #69](https://github.com/Uplus-GETDDO/getddo-be/pull/69), [공개 저장 문서](../03-database/draw-publication-storage.md#gd-169-연결과-조회-계약)에서 검토한다. BaseEntity로 전환할 때는 새 마이그레이션의 생성 시각 추가·기존 데이터 채움 근거·정밀도·NOT NULL 전환과 Entity 매핑을 함께 검토한다. 업무 시각을 생성 시각으로 임의 치환하거나 기존 Flyway를 수정하지 않는다. 새 공개 실행 연결은 기존 공개 결과 연결과 같은 복합 식별자를 사용하며 독립 생성 시각을 중복 저장하지 않는다.
- MySQL 버전·연결 설정과 Flyway SQL은 이번 작업 범위에서 제외한다. DB 변경이나 저장 전 ID가 필요한 요구사항이 생기면 재검토한다.

### GD-291 응모 차단 이력 예외 — 2026-10-23 사용자 승인

- 예외: `AbuseCaseEntity`는 `BaseEntity`를 상속하지 않고, Redis 생산자가 최초 발급한 UUID v4 blockId를 PK로 유지한다. DB 삽입·재시도 때 새 ID를 생성하지 않으며 최초 차단 시각·근거를 그대로 저장한다. 같은 PK의 전체 저장값 비교로 재전달을 확인하기 위한 예외다. 사용자가 2026-10-23 대화에서 명시 승인했고 [ADR-0009](0009-entry-abuse-history-identifiers.md)에 근거와 대안을 기록했다. 적용 범위는 이 Entity이며 다른 Entity의 UUID v7 규칙은 유지한다.
