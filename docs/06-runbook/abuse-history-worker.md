# 어뷰징 차단 이력 저장 작업자 — GD-291

이 문서는 백엔드의 저장·복구 구현과 운영 확인 절차다. 공용 계약은 [abuse.md](https://github.com/Uplus-GETDDO/getddo-spec/blob/main/05-api/abuse.md)를 따른다. 구현 완료가 배포·실제 연결 검증 완료를 뜻하지 않는다.

## 처리 흐름

1. GD-144가 확정한 ACTIVE·DB_CONTEXT_CHECKED 저널만 선점한다. 임시 소유권은 기본 30초이며 차단 기간과 별개다.
2. 최초 blockId·사용자·이벤트·시각·근거로 DB에 한 건씩 삽입한다. 기존 조회 Entity·MapStruct·evidence 변환을 재사용한다.
3. DB 커밋 후에만 Redis의 dbPersisted를 true로 바꾼다. 저장 완료 후에도 차단 종료 전까지 ACTIVE와 사용자 인덱스를 유지한다.
4. 저장 확인과 차단 종료를 모두 만족한 저널만 삭제한다. 차단이 먼저 끝난 미저장 이력은 보존한다.

삽입 실패는 해당 트랜잭션을 rollback한 뒤 새 읽기 트랜잭션에서 확인한다. 같은 PK의 모든 저장 필드와 JSON 근거가 같을 때만 중복 전달 성공이다. JSON 필드 순서는 무시한다. 다른 blockId의 복합 UNIQUE 충돌, FK·CHECK 위반이나 다른 근거는 저장 성공으로 처리하지 않는다. 기존 행을 수정하거나 이벤트 FK를 임의로 null로 바꾸지 않는다.

## 재시도와 배치

- 최초 DB 실패부터 기본 10분 동안 10초 → 30초 → 1분 → 2분, 이후 2분 간격으로 재시도한다. 다음 재시도가 예산 종료 시각과 같거나 이후면 일반 재시도 대신 배치 대기로 넘긴다.
- 매일 Asia/Seoul 오전 6시에 배치한다. 오전 7시 정기 배포의 1시간 전이며 실제 CD 실행 여부와 연동하지 않는다.
- 배치 시작 시 Redis 시각을 고정하고 그 시점까지 대기 중인 건을 처리한다. 실패한 건만 다음 날 6시로 넘기며 다른 건은 계속 처리한다. 각 건은 독립된 DB 트랜잭션이다.
- 다중 서버는 건별 토큰·소유권 만료를 확인한다. 오래된 소유자는 완료·재예약을 변경하지 못한다. 커밋 후 Redis 응답이 유실돼도 같은 원본을 재전달하고 DB 전체 값으로 중복 확인한다.
- 일반 선점 후 프로세스가 중단되면 소유권 만료 후 다시 처리한다. 배치에서 남은 건은 다음 정기 배치에 대상이 된다. 배치 시간을 놓친 경우 즉시 보충 실행하는 기능이나 수동 재처리 API는 추가하지 않았다.

배치는 전용 단일 실행 스레드에서 동작하며 같은 프로세스의 중복 시작을 막는다. 기존 공용 TaskScheduler 빈과 다른 도메인의 스케줄러 설정은 바꾸지 않는다.

## Redis 복구 원본과 필드

`getddo:entry-block:` 접두사를 사용한다. 기존 journal:{blockId} HASH의 payload는 변경하지 않는다.

| 추가 필드·인덱스 | 용도 |
|---|---|
| ownerToken, leaseUntil | 현재 저장 작업자의 토큰과 Redis epoch 밀리초 만료 |
| firstFailureAt, failureCount | 최초 DB 실패 시각과 누적 실패 횟수 |
| nextAttemptAt, deliveryState | 다음 처리 시각과 NORMAL/BATCH 구분 |
| batch ZSET | 배치 대기 blockId → 다음 처리 시각 |

pending과 batch의 점수는 다음 처리 시각이며 선점 중에는 소유권 만료까지 미룬다. 기존 생산자가 만든 최초 pending 점수도 그대로 소비한다. 재시도 상태를 먼저 저널에 기록하고 파생 인덱스를 갱신하므로 중간 오류 후 저널에서 복구할 수 있다. Lua 오류를 전체 rollback으로 가정하지 않는다.

기본 1분마다 journal-ids와 실제 journal:* 키를 SCAN한다. 전역·pending·사용자 인덱스가 없어도 실제 저널에서 복구한다. PREPARED는 DB에 저장하지 않고 정리한다. 원본 없는 사용자 연결도 정리한다. 손상된 ACTIVE·알 수 없는 상태·다른 ID의 payload는 합성하거나 삭제하지 않고 경고와 함께 보존한다. 미저장 저널에는 TTL을 추가하지 않는다.

## 설정과 운영 확인

[application.yaml](../../api/src/main/resources/application.yaml)의 getddo.abuse.history-worker를 사용한다. Redis 모드에서만 동작하며 enabled 기본값은 true다. 메모리 모드는 DB 이력을 만들지 않는다. 작업자를 꺼도 응모 차단 생산이 중단되는 것은 아니다.

| 설정 | 기본값 |
|---|---|
| poll-delay / recovery-delay | 1000ms / 60000ms |
| batch-size / scan-size | 폴링 최대 20건 / SCAN 힌트 100건 |
| lease | 30s |
| retry-budget / retry-delays | 10m / 10s,30s,1m,2m |

환경변수는 ABUSE_HISTORY_WORKER_ENABLED, ABUSE_HISTORY_POLL_DELAY, ABUSE_HISTORY_RECOVERY_DELAY, ABUSE_HISTORY_BATCH_SIZE, ABUSE_HISTORY_SCAN_SIZE, ABUSE_HISTORY_LEASE, ABUSE_HISTORY_RETRY_BUDGET, ABUSE_HISTORY_RETRY_DELAYS다. 변경은 재시작 후 적용된다. 배치 시각은 고정이다. 기존 Parameter Store·CD 조회 목록은 수정하지 않았다.

- `Abuse history backlog` 로그와 `ZCARD getddo:entry-block:pending`, `ZCARD getddo:entry-block:batch`, `SCARD getddo:entry-block:journal-ids`를 함께 본다. journal-ids에는 저장 완료지만 아직 차단 중인 기록도 포함돼 미저장 수와 같지 않다.
- DB save deferred가 계속 발생하면 예외 종류와 blockId를 기준으로 DB 연결·제약·FK 원본을 확인한다. 원인 해결 후 일반 대기는 다음 재시도, 배치 대기는 다음 오전 6시에 같은 원본으로 다시 처리한다. 임의 삭제·재차단·새 ID 발급은 복구 방법이 아니다.
- batch started/finished는 실행 여부와 처리 시도 건수를 나타내며 전부 저장 성공했다는 뜻이 아니다. interrupted와 다음 날 적체를 함께 확인한다.
- unreadable 경고는 원본·재시도 상태 확인이 필요하다. 로그에 사용자 ID·payload·예외 원문을 출력하지 않는다.
- Redis 전체 유실을 복원하는 DB outbox는 없다. 연결 풀·eviction·메모리와 배포 전 실제 확인은 [기존 운영 경계](entry-abuse-migration.md#gd-144-redis-생산-경로-운영-경계)를 따른다. 경보 임계값은 실제 용량·유입량 측정 후 인프라 담당자와 확정한다.
