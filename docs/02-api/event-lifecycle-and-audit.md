# 이벤트 모집 상태 전환·시각 응답·감사 기록 — GD-203

- 상태: 검토 대기 — 구현 설명, 담당자 확인 전
- 공용 계약: [이벤트 API](https://github.com/Uplus-GETDDO/getddo-spec/blob/main/05-api/event.md), [추첨·수동 공개](https://github.com/Uplus-GETDDO/getddo-spec/blob/main/03-decisions/022-manual-initial-publication.md)

## 모집 상태 전환

`EventLifecycleWorker`가 `event-lifecycle-` 전용 스레드 하나에서 `EventLifecycleService.advanceDue(20)`을 호출한다. 워커 내부의 스케줄러를 직접 생성·종료하고 공용 TaskScheduler Bean으로 노출하지 않아 알림·추첨의 스케줄러를 대체하지 않는다. 첫 폴링은 즉시 시작하고 이전 폴링 종료 후 설정된 간격을 기다린다. 서비스가 현재 시각을 마이크로초로 정규화하고 미삭제 이벤트의 시작·마감 경계를 포함해 검사한다.

| 저장 상태·시각 | 변경 상태 |
| --- | --- |
| SCHEDULED, 시작 시각 도달·마감 전 | OPEN |
| SCHEDULED, 서버 중단 등으로 시작·마감 모두 경과 | CLOSED |
| OPEN, 마감 시각 도달 | CLOSED |

미래 이벤트·삭제 이벤트와 취소·추첨·공개 상태는 변경하지 않는다. GD-86의 자동 추첨은 상태 반영이 늦어져도 마감 +1분이 지난 SCHEDULED/OPEN/CLOSED를 대상으로 한다. 모집 상태 전환 자체는 추첨을 직접 실행하거나 결과를 공개하지 않는다. 실제 공개 후 `PUBLISHED` 연결은 GD-199의 추첨 담당 범위다.

`EventLifecycleService.advanceDue`는 외부 트랜잭션을 거절하는 `NEVER` 메서드다. 후보 ID는 잠금 없이 읽고, 별도 Spring Bean인 `EventLifecycleTransitionService.advance`가 이벤트 한 건마다 `READ COMMITTED` 트랜잭션을 연다. JDBC `FOR UPDATE SKIP LOCKED`로 해당 이벤트만 선점하고 최신 상태·시각을 다시 읽는다. 다른 워커·취소·수정·추첨이 잠근 이벤트는 즉시 건너뛴다. 상태와 `EVENT_STATUS_CHANGE` 감사 기록은 이벤트별로 함께 커밋하며 시스템 작업의 처리자 ID는 null이다.

한 이벤트의 감사 실패는 그 이벤트만 롤백한다. 순회 서비스가 실패를 기록하고 다음 후보를 계속 처리하므로 이미 성공한 이벤트와 후속 이벤트는 독립 커밋된다. 후보는 직전 마지막 ID 뒤부터 순환 조회한다. 실패한 후보도 커서를 전진시켜 첫 묶음이 반복 실패해도 다음 묶음을 처리한다. 커서는 인스턴스 메모리에만 있으며 재시작 시 초기화된다. 중복 처리는 커서가 아닌 이벤트 잠금·최신 상태 재검증으로 막는다.

후보 SQL은 SCHEDULED의 시작 기한과 OPEN의 마감 기한을 `UNION ALL`로 나눠 조회한다. [V027](../../storage/db/src/main/resources/db/migration/event/V027__add_event_lifecycle_due_indexes.sql)은 각각 `(status, deleted_at, starts_at, id)`와 `(status, deleted_at, ends_at, id)` 인덱스를 추가한다. 시간 범위로 후보를 줄인 뒤 due ID만 순환 정렬하며 기존 목록 인덱스는 유지한다.

## 응답 필드 변경

- 사용자 목록·상세: `publicationScheduledAt` 제거. 추첨·발표 예정 시각을 제공하지 않는다.
- 관리자 등록·수정·목록·상세: 기존 필드를 `drawScheduledAt`으로 교체한다. 값은 `endsAt +1분`의 최초 자동 추첨 기준이며 실제 실행·승인·발표 완료 시각이 아니다.
- 실제 공개 여부·`publishedAt`은 기존 추첨 결과 조회 계약을 사용한다. 관리자 확인과 수동 공개는 별도 동작이다.

프론트는 기존 필드 참조와 발표 카운트다운을 제거하고 관리자에서만 새 필드를 사용해야 한다. DB 컬럼이나 새로운 HTTP API는 추가하지 않는다.

## 등록·수정 감사 기록

기존 `AuditLogService.record(...)`를 업무 트랜잭션 안에서 호출한다.

| 처리 | action | 변경 정보 |
| --- | --- | --- |
| 등록 | EVENT_CREATE | 저장 후 설정·경품 ID를 afterData에 기록 |
| 수정 | EVENT_UPDATE | 변경 전후 설정·경품을 beforeData·afterData에 기록 |

대상은 `EVENT`와 이벤트 ID, 처리자는 요청 관리자 ID다. 시각·UUID·enum은 공용 감사 JSON 계약에 맞춰 문자열로 변환한다. 동일한 설정·기존 경품 ID를 재전송하면 기존 조기 반환을 유지해 이력을 추가하지 않는다. 감사 저장 실패는 이벤트·경품과 일정 변경 안내 등록까지 함께 롤백한다.

## 검증

고정 Clock을 사용한 서비스·응답 계약 테스트와 실제 MySQL 통합 테스트로 정각 경계, 서버 중단 후 복구, 취소 경합, 두 워커의 동시 선점, 반복 감사 실패 중 다른 이벤트·다음 묶음의 커밋을 검증한다. 등록 감사 명령과 수정 거절의 감사 무호출은 기존 단위 테스트에서 확인한다. 후보 SQL의 인덱스 선택과 순환은 대부분 미래인 데이터를 넣은 MySQL EXPLAIN·실제 조회로 확인한다. 실행 결과는 당일 공용 워크로그에 기록한다.

스케줄러 검증은 DB 없이 실제 Spring Boot 스케줄링 자동 설정을 사용한다. 공용 스레드가 막힌 동안에도 전용 스레드의 반복 폴링이 진행되고 공용 Bean이 유지되는지, 종료가 진행 중인 폴링을 기다리고 전용 스레드를 정리하는지 확인한다. 비활성화 설정과 잘못된 간격도 검증한다.
