# ADR-0009: 응모 차단 이력의 저장 전 식별자 유지

- 상태: 제안 — Codex 작성, 담당자 확인 전
- 작성자 / 날짜: Codex / 2026-10-23
- 검토자: 담당자 확인 전
- 관련 작업 기록 / PR: [GD-291](https://ureca4.atlassian.net/browse/GD-291), [PR #172](https://github.com/Uplus-GETDDO/getddo-be/pull/172)
- 대체한 ADR / 대체된 ADR: 해당 없음. ADR-0001의 AbuseCaseEntity 예외 보완 제안

## 배경

[ADR-0001](0001-common-entity-uuid-auditing.md)은 영속화 시 Hibernate가 ID를 생성하는 것을 기본으로 정하고 저장 전 식별자가 필요하면 재검토하도록 한다. GD-144는 DB 저장 전에 Redis에서 자동 차단과 전체 저널을 확정하며, GD-291은 같은 저널을 DB 저장 실패·커밋 응답 유실 이후에도 재전달한다. 이때 최초 blockId를 바꾸면 동일 차단의 중복 저장 확인 기준을 유지할 수 없다.

## 결정과 이유

`AbuseCaseEntity`에 한정해 Redis 생산자가 최초 생성한 blockId를 PK로 유지하는 예외를 제안한다. 이 문서가 기존 채택 ADR의 예외 승인이나 새 공용 정책의 확정을 뜻하지 않는다.

- `BaseEntity`를 상속하거나 `@GeneratedValue`로 DB 삽입 때 새 ID를 만들지 않는다. 생성자·MapStruct가 최초 ID와 차단 시각·근거를 전달한다.
- 재전달은 같은 PK와 모든 저장 필드·JSON 근거가 일치해야 성공이다. `(user_id, source_type, blocked_at)`만 같은 다른 ID는 같은 전달로 취급하지 않는다.
- 생성·갱신 시각을 추가로 읽어 최초 `occurredAt`, `detectedAt`, `blockedAt`, `blockedUntil`을 덮어쓰지 않는다. 저장 이후 이력은 갱신하지 않는다.
- 현재 생산자는 UUID v4를 생성한다. 공통 UUID v7 생성 방식과의 차이를 숨기지 않으며, 다른 Entity에 이 예외를 일반화하지 않는다.

## 검토한 대안

- Hibernate가 삽입 시 새 PK 생성: Redis 저널 ID와 달라져 현재 재전달·중복 비교 계약을 유지할 수 없어 이번 수정에서는 선택하지 않는다.
- 기존 blockId 유지: 현재 생산·소비 코드 및 재전달 계약을 유지하므로 제안한다. 별도 식별자 컬럼이나 마이그레이션을 추가하지 않는다.

## 영향과 재검토 조건

식별자 생성 시점·알고리즘에 대한 공통 기본 규칙과의 예외는 담당자가 검토해야 한다. 현재 코드의 근거를 기록했으며 문서 상태를 임의로 채택으로 바꾸지 않는다. 이력 ID 생성 방식·저널 인계 계약·별도 중복 식별자를 변경하려면 기존 데이터와 API 호환성을 함께 재검토한다. 실제 MySQL 중복 저장 동작은 통합 테스트 결과로 확인하며, 이 제안만으로 검증 성공을 주장하지 않는다.
