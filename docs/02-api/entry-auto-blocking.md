# GD-144 응모 자동 일시 차단 구현

- 상태: 검토 대기 — Codex 작성, 담당자 확인 전
- 관련 티켓: [GD-144](https://ureca4.atlassian.net/browse/GD-144)
- 정책 원본: [공용 응모 규칙](https://github.com/Uplus-GETDDO/getddo-spec/blob/main/02-domain/entry.md#응모-요청-반복-초과에-따른-일시-차단--gd-141)
- 조회 계약 원본: [AR01/AR02](https://github.com/Uplus-GETDDO/getddo-spec/blob/main/05-api/abuse.md)
- BE 설계: [저장·복구 설계](../03-database/entry-abuse-blocking.md)

## 모듈별 책임

- `api`: `RateLimitInterceptor`·`EntryRateLimitHandler`의 HTTP 처리, 사용자 문맥 검증과 429 응답, `RateLimitProperties`·`EntryBlockProperties`의 Spring 설정 바인딩을 담당한다. 설정 이름·기본값은 유지하고 `toDomain()`으로 순수 정책을 전달한다.
- `core`: `com.getddo.core.common.ratelimit`에 제한기 인터페이스, `RateLimitPolicy`·`EntryBlockPolicy`, 판정·차단 모델과 메모리 구현을 둔다. HTTP·Spring Boot 설정 바인딩·Redis 구현을 참조하지 않는다. 메모리 모드는 Redis 장애의 자동 대체 경로가 아니다.
- `storage:redis`: `com.getddo.redis.ratelimit`에 Redis 제한기·연결 풀·스크립트 실행기와 Redis 빈 구성을 둔다. Lua 리소스는 `storage/redis/src/main/resources/redis`에 있다. 이 모듈은 `core`에만 프로젝트 의존성을 가지며 `api`·`storage:db`를 참조하지 않는다.
- API는 `RedisRateLimiterConfig`를 import해 기본 저장소 구성을 연결한다. 사용자 정의 `RateLimiter`의 기본 빈 대체 계약은 유지한다. Redis 전용 테스트는 `storage:redis`, 메모리·판정 테스트는 `core`, HTTP·실제 API YAML 바인딩 테스트는 `api`에 둔다.

## 적용 경로

`RateLimitInterceptor`는 `policy=entry`를 `EntryRateLimitHandler`와 `EntryRateLimiter`로 연결한다. 다른 정책은 기존 `tryAcquire` 흐름을 유지한다. 기본 메모리·Redis 제한기 모두 응모 평가 인터페이스를 구현한다. 사용자 정의 `RateLimiter`만 주입한 경우 기존 일반 제한 계약을 유지하며 자동 차단을 제공하려면 `EntryRateLimiter`도 구현해야 한다.

기존 `CurrentUserArgumentResolver`의 헤더 해석을 공유한다. 읽기 전용 ACTIVE 조회 전에는 헤더 형식만 검사하며, 유효한 기존 차단이 있으면 DB 조회 없이 같은 429를 반환한다. 그 외 요청은 기존 사용자 존재·역할·멤버십 검증을 마친 뒤 집계하며 검증된 User를 요청 속성에 보관해 Controller의 resolver가 재사용한다. 사용자 DB 오류는 Redis fail-open catch에 포함하지 않는다. 사용자 상태·업무 자격은 기존 Service가 판단한다.

이벤트 UUID는 집계 scope이며 FK와 구별한다. 기존 이벤트 조회 경계로 실재가 확인된 경우에만 payload의 eventId에 넣는다. 미존재·미확인·조회 장애는 NULL이며 집계 범위를 USER로 바꾸지 않는다. 이 조회는 Lua 밖에서 수행하고 실패 원문·개인정보를 로그에 남기지 않는다. 이벤트가 나중에 삭제되는 경합의 DB 저장 처리는 GD-291 범위다.

## 평가와 장애 응답

Redis는 한 Lua 실행에서 ACTIVE 재확인 → 구간 준비 → 기존 단축 순서의 일반 제한 → 거절 기록 → 최초 활성화를 수행한다. 사용자 전체 일반 제한이 먼저 거절되면 이벤트 일반 요청 수는 올리지 않지만, 이벤트 구간과 거절 횟수는 기록한다. 사용자·해당 이벤트 상태는 하나의 HASH에서 단일 HSET으로 함께 교체한다. 각 필드에 최초 startedAt·endsAt을 보존하고 논리 만료를 판정한다. HASH TTL은 남은 구간 중 가장 늦은 endsAt이며 만료된 이벤트 필드는 후속 평가에서 정리한다. 미저장 이력은 별도 저널이므로 집계 만료가 이력을 지우지 않는다.

| 상황 | 처리 |
| --- | --- |
| ACTIVE 조회 실패 | 이번 요청의 일반·거절 평가를 생략하고 문맥 검증 후 통과·경고 |
| 평가 응답 유실·판정 전 실패 | 결과를 합성하지 않고 통과·경고. 같은 요청을 다시 평가하지 않음 |
| 일반 거절 확인 후 기록·준비 실패 | RATE_LIMITED + DENY_RECORDING_FAILED로 원래 429·Retry-After 유지·경고 |
| ACTIVE 재확인 또는 최초 활성화 성공 | 최초 blockedUntil 기준 429·Retry-After. 종료 연장·추가 집계 없음 |

`EntryRedisScriptExecutor`는 응모 전용 Lettuce 연결에 autoReconnect=false와 REJECT_COMMANDS를 사용하고 EVAL을 한 번 전송한다. 공유 Redis 연결과 비응모 동작을 유지한다. commons-pool2 의존성 추가로 Boot가 공유 연결까지 자동 풀링하지 않도록 YAML에서 기본 자동 풀링은 끄고 응모 전용 풀만 직접 구성한다. 요청 평가 연결은 전용 풀에서 최대 8개를 대여·반납하고 대여·반납 시 닫힌 연결을 폐기한다. 풀 대기는 기존 명령 시간 제한 이내로 제한한다. 연결 재사용과 EVAL 재전송은 별개이며 같은 HTTP 요청의 EVAL을 재실행하지 않는다. 원본 단일 Redis 구성이 대상이며 Cluster·Sentinel은 지원하지 않는다. 접속·명령 시간 제한과 자격 증명은 기존 설정을 재사용한다.

메모리 모드는 주입된 TimeProvider와 사용자 단위 임계 구역에서 같은 제한·차단 판단을 제공한다. 서버별 상태이며 재시작 시 사라지고 Redis 저널·DB 저장 대기를 만들지 않는다. 관리자 이력까지 연결하는 GD-291 생산 경로는 Redis 모드가 전제다. 기존 비응모 메모리 제한은 변경하지 않는다.

GD-291 연결 전에는 Redis 모드의 GD-144 운영 배포를 보류한다. 기본 `RedisRateLimiter`가 내부 `RedisEntryRateLimiter`에 위임하고 응모 전용 `EntryRateLimitHandler`가 인터셉터에 연결된 `RATE_LIMIT_STORE=redis` 환경에서는, 요청 제한이 활성화된 응모 평가가 별도 자동 차단 활성화 설정 없이 저널을 생산하므로, 배포 전 확인과 메모리 검증 모드의 제한은 [런북의 배포 제한](../06-runbook/entry-abuse-migration.md#gd-291-연결-전-배포-제한)을 따른다.

## GD-291 저장 작업자 인계

아래 키는 Redis 내부 계약이며 공개 API가 아니다. 전체 키 접두사는 `getddo:entry-block:`이다.

| 키 | 타입·내용 |
| --- | --- |
| counters:{userId} | HASH. user 및 event:{scopeId} 필드에 startedAt·endsAt·count·rejectedCount JSON. 저장 작업자 대상 아님 |
| journal:{blockId} | HASH. state=PREPARED/ACTIVE, context=DB_CONTEXT_CHECKED, dbPersisted=false, payload=최초 전체 JSON |
| user:{userId} | SET. 여러 과거·현재 미저장 blockId를 함께 보존 |
| journal-ids | SET. 전역 저널 스캔 인덱스 |
| pending | ZSET. member=blockId, score=최초 판정 epoch 밀리초. 소비를 깨우는 파생 인덱스 |

저널·사용자 인덱스·대기 항목에는 차단 기간 TTL을 걸지 않는다. PREPARED → 사용자 인덱스 → 전역 인덱스 → 대기 등록 후, 단일 HSET으로 ACTIVE와 최초 payload를 확정한다. 실패한 미활성 후보는 같은 평가에서 정리를 시도한다. Redis 부분 실패로 정리하지 못한 PREPARED는 작업자가 저장하지 않고 정리해야 한다. 활성 캐시는 두지 않으며 빠른 조회와 재평가 모두 사용자 인덱스의 실제 ACTIVE 저널을 읽는다.

payload 필드는 id·userId·eventId(nullable)·sourceType·detectionType·detectionReason·occurredAt·detectedAt·blockedAt·blockedUntil·evidence다. sourceType·탐지 유형·사유는 서버 고정값이다. occurredAt은 요청 평가 진입 시각의 UTC ISO 문자열이며 마이크로초로 한 번 정규화한다. Redis TIME으로 확정한 detectedAt=blockedAt 및 blockedUntil, evidence.windowStartedAt·windowEndsAt은 내부 JSON에서 epoch 밀리초 정수다. GD-291은 이를 Instant.ofEpochMilli로 변환하고 DB에 UTC로 저장한다. API의 UTC instant 문자열이나 DB evidence 문자열로 변환할 때 현재 시각·현재 설정으로 재계산하지 않는다. 나머지 evidence 필드명과 타입은 공용 계약과 같다. 두 거절 임계값을 동시에 충족하면 [공용 evidence 계약](https://github.com/Uplus-GETDDO/getddo-spec/blob/main/05-api/abuse.md)에 따라 차단 한 건과 USER 대표 근거만 생성한다. 이때 evidence에는 USER의 구간·일반 한도·거절 횟수·임계값을 기록하며, GD-291 재전달·저장 재시도에서도 이 최초 근거를 그대로 보존한다.

GD-291 소비자는 아래 경계를 구현한다. 재시도·배치·저널 필드와 운영 확인은 [저장 작업자](../06-runbook/abuse-history-worker.md)를 따른다. 구현과 실제 배포·연결 검증은 구분한다.

- ACTIVE와 생성 표식을 확인한 전체 payload만 저장한다. PREPARED나 파생 항목만으로 차단을 합성하지 않는다.
- pending 유실에도 후속 요청 없이 복구되도록 journal-ids와 실제 journal:* 키의 SCAN을 사용한다. 인덱스 없는 고아 PREPARED 정리와 ACTIVE의 인덱스 복구도 포함한다.
- DB commit 후에만 dbPersisted를 true로 표시한다. 완료 표시 실패 시 같은 ID·payload로 재전달하며 기존 DB 행과 비교한다.
- 저장 전 차단이 만료되거나 새 차단이 생겨도 과거 payload는 덮어쓰지 않는다. 종료 전에는 저장 완료가 차단을 해제하지 않는다.
- DB 저장 확인과 blockedUntil 경과를 모두 만족한 뒤 저널·인덱스를 정리한다. 재시도·배치 대기 데이터도 이 조건을 지킨다.
- GD-291 작업자가 Redis 저널을 DB에 커밋하면 기존 AR01/AR02에서 조회된다. 운영 환경의 작업자 배포·동작과 FE 실측은 별도 확인한다.

## 설정과 검증

[application.yaml](../../api/src/main/resources/application.yaml)의 기존 `getddo.rate-limit` enabled·store·entry 일반 한도를 재사용한다. entry-block.user-threshold·event-threshold·duration·rule-version을 추가했다. 기본값은 해당 설정 파일을 따른다. ENTRY_RATE_LIMIT·ENTRY_SCOPED_RATE_LIMIT·ENTRY_RATE_WINDOW와 ENTRY_BLOCK_* 환경변수 또는 Spring 외부 설정으로 조정하고, 양수·최소 1ms 구간·집계시간≤차단시간을 부팅 시 검사한다. 설정 주입 변경은 프로세스 재시작을 필요로 하며 런타임 동적 갱신 기능을 추가하지 않는다. `ruleVersion`은 공용 계약상 문자열이며 JSON의 `v1`은 예시다. 이 구현의 기본값은 설정 바인딩과 YAML 모두 `entry-v1`이고 `ENTRY_BLOCK_RULE_VERSION`으로 변경할 수 있다. 저장된 과거 근거의 버전은 현재 기본값으로 덮어쓰지 않는다. 기존 Parameter Store 필수 조회 목록·CD 워크플로는 변경하지 않았다.

모듈 분리 전 검증 결과:

- 응모·기존 제한·설정 단위 테스트 58건 통과. CORS 전용 컨텍스트는 새 의존성 대역을 보완했으며 기존 CORS 회귀도 통과했다.
- 전체 단위 테스트 1,684건 통과(API 544, core 1,042, storage:db 96, storage:s3 2). 실패·오류·건너뛰기 0건.
- 아래 통합 테스트 실행 제외 빌드 통과. API·DB 통합 테스트 소스 컴파일 통과. 새 Redis 시나리오는 실패 주입 파라미터를 포함해 14건이며 실행하지 않았다.
- 실제 Redis·MySQL·배포 검증과 통합 테스트 실행은 사용자 요청으로 수행하지 않았다. 컴파일 성공을 실제 연결·동시성 검증 성공으로 해석하지 않는다.

```bash
./gradlew test build :api:compileIntegrationTestJava :storage:db:compileIntegrationTestJava \
  :storage:redis:compileIntegrationTestJava \
  -x :api:integrationTest -x :storage:db:integrationTest -x :storage:redis:integrationTest \
  --no-daemon --max-workers=1 --no-parallel
```

## CodeRabbit 리뷰 보완

최초 기록이 한 번만 발생하는지 시작 래치로 동시 평가를 검증하고, 판정 객체의 상태·대기 시간 불변식과 잘못된 JSON 응답 검증을 추가했다. Lua 리소스 읽기 실패는 내부 리소스 이름·I/O 원인을 보존한다. 두 ACTIVE 함수의 공통 추출은 현재 판정이 동일한 선택적 리팩터링이므로 이번 수정에 포함하지 않는다. Redis 보존·대기 적체 운영 확인은 [런북](../06-runbook/entry-abuse-migration.md#gd-144-redis-생산-경로-운영-경계)을 따른다. 리뷰 후 대상 단위 테스트 58건·전체 단위 테스트 1,684건과 통합 테스트 실행 제외 빌드가 통과했다. 보강한 통합 소스는 컴파일만 했고 실제 연결·부하 측정 결과를 주장하지 않는다.

재리뷰 보완으로 전용 연결이 원본 TLS 검증 모드 FULL·CA·NONE과 STARTTLS 설정을 그대로 보존하도록 수정하고 세 모드의 회귀 테스트를 통과했다. 연결 재사용 통합 시나리오는 현재 연결 개수 대신 INFO stats의 total_connections_received 누적값을 사용해 연결 종료에 의한 오판을 방지한다. 해당 통합 시나리오는 로컬 실행하지 않았으며 소스 컴파일만 확인했다.

## 모듈 분리 검증 — 2026-10-23

- 전체 단위 테스트 1,684건 통과(실패·오류·건너뛰기 0). 모듈별 건수는 api 515, core 1062, storage/db 96, storage/redis 9, storage/s3 2이다. 테스트를 각 책임 모듈로 옮겼으며 전체 건수는 유지했다.
- 위 통합 테스트 제외 명령으로 전체 빌드 및 API·DB·Redis 통합 테스트 소스 컴파일에 성공했다. 로컬 통합 테스트·실제 저장소 연결·배포 검증은 사용자 요청에 따라 수행하지 않았다.
- core의 API·Redis·Spring Boot 바인딩 역참조 부재, storage:redis의 API 역참조 부재와 API 운영 코드의 직접 Redis 클라이언트 import 부재를 확인했다. 두 Lua 파일은 이동 전과 바이트 단위로 동일하다.
- 실행 JAR에 Redis 모듈과 그 안의 두 Lua 파일이 포함되고, 기존 API 경로에 Redis 구현 클래스나 Lua 파일이 중복되지 않음을 확인했다.

최신 dev(`8ee80cf0`) 병합 후 단위 테스트 1,697건(api 521, core 1,069, storage:db 96, storage:redis 9, storage:s3 2)이 통과했다. 실패·오류·건너뛰기 0건이며 전체 빌드·세 모듈 통합 테스트 소스 컴파일도 성공했다. 늘어난 건수는 dev의 기존 변경을 포함한 결과이고 로컬 통합 테스트는 실행하지 않았다.
