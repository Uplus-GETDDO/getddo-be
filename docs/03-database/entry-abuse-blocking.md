# GD-141 응모 요청 자동 차단 저장·연동 설계

- 상태: 검토 대기 — Codex 작성, 담당자 확인 전
- 작성일: 2026-10-21
- 관련 작업·브랜치: [GD-141](https://ureca4.atlassian.net/browse/GD-141), `docs/GD-141`
- 범위: 합의한 설계를 문서화한다. ERD·Flyway·Java 코드·배포 설정은 변경하지 않는다.
- 공용 정책 원본: [응모·어뷰징 규칙](https://github.com/Uplus-GETDDO/getddo-spec/blob/main/02-domain/entry.md)
- FE 계약 원본: [어뷰징 조회 API](https://github.com/Uplus-GETDDO/getddo-spec/blob/main/05-api/abuse.md)

정책과 공개 DTO의 원본은 spec이며 이 문서는 BE 내부 처리·저장·검증만 다룬다. 2026-10-21 합의한 정책·조회 필드를 getddo-spec의 entry.md·abuse.md에 반영하고 main에 push했다([반영 커밋](https://github.com/Uplus-GETDDO/getddo-spec/commit/02ac909)). evidence 세부 필드명·업무 오류 번호는 명시된 후속이다. 문서·설계 작업에는 GD-141 브랜치를 사용하며 Jira 티켓 본문이나 상태는 변경하지 않는다.

## 현재 구현과 변경 경계

윤태형의 [GD-146 / PR #96](https://github.com/Uplus-GETDDO/getddo-be/pull/96) 요청 제한 구조와 [GD-208 / PR #107](https://github.com/Uplus-GETDDO/getddo-be/pull/107)의 Redis 구현을 재사용한다. 원격 dev에서 RateLimiter·RedisRateLimiter·RateLimitInterceptor 및 요청 제한 문서를 확인했다. 해당 구현은 일반 429 제한까지이며 별도 일시 차단·저장 대기·DB 이력·AR01/AR02 구현 완료를 뜻하지 않는다. 배포의 Redis 모드 활성화는 확인하지 않았다.

기존 인터셉터는 사용자 전체 한도를 넘으면 이벤트별 제한기를 호출하지 않는다. 일반 허용 한도·429 판정은 유지하되, 신규 응모 연동에서는 일반 제한 판정과 거절 횟수 기록의 책임을 분리한다. `tryAcquire`를 거절 집계 목적으로 다시 호출해 일반 요청 횟수를 두 번 올리지 않는다.

새 활성 차단 확인·집계 구간 준비·`recordDenied`·차단 생성·이력 등록 전체는 `@RateLimited`의 `policy = "entry"`이고 아래 사용자 존재·문맥 검증을 완료한 경우에만 실행한다. 이 guard를 공통 인터셉터의 응모 분기 진입점에 둔다. attendance·mission-submission·game-result 및 annotation 없는 요청은 기존 흐름을 사용하며 응모 차단 여부도 조회하지 않는다. 따라서 같은 사용자의 응모 차단이 다른 기능을 막거나 그 기능의 429가 응모 차단 횟수에 포함되지 않는다.

- 사용자 전체·식별 가능한 이벤트의 집계 구간 메타데이터는 각 대상의 첫 응모 요청에서 준비한다. 전체 한도 초과로 기존 이벤트 제한기를 건너뛰어도 해당 이벤트의 첫 요청·만료 시각은 기록한다. 이벤트의 거절 카운터를 첫 429 시점부터 새 구간으로 만들지 않는다.
- 일반 제한 결과를 한 번 확정한 뒤 허용이면 거절 카운터를 변경하지 않는다. 일반 429이면 한 곳에서만 `recordDenied(userId, eventId, window)`를 호출해 사용자 전체와 해당 이벤트의 거절 횟수를 각각 한 번 증가시킨다. 이 이름은 후속 구현의 책임을 설명하는 표기이며 현재 제공 코드가 아니다.
- 전체 한도에서 거절된 경로도 동일한 기록 진입점을 사용하므로 이벤트별 거절이 빠지지 않는다. 이벤트 한도에서 거절된 경로에서도 다시 예외를 처리하며 기록하지 않아 각 카운터가 2회 증가하지 않게 한다.
- entry handler가 선택된 요청에서 사용자 ID는 식별되지만 이벤트 경로 ID가 UUID로 식별되지 않으면 eventKey/eventWindow 없이 사용자 전체 일반 제한을 계속 평가한다. 이 평가가 일반 429이면 사용자 전체 거절만 1회 집계하고 USER 임계값 도달 시 일시 차단할 수 있다. 이벤트 카운터는 만들지 않고 관련 event_id는 NULL이다. 일반 제한이 허용하면 기존 경로 ID 해석·400 처리로 넘기며 그 400은 거절 횟수에 포함하지 않는다. 사용자 ID 자체가 없거나 형식이 틀리면 기존처럼 제한 평가 없이 사용자 문맥 오류 처리로 넘긴다. 차단 중 거절·저장소 장애 통과는 일반 429 거절 집계에서 제외한다.

### 응모 평가·집계 구간 제공 계약

현재 RateLimiter.Decision은 allowed·retryAfterSeconds만 반환하며 RedisRateLimiter의 count·PTTL도 공개 결과에 남지 않는다. 이 기존 결과만으로 거절 카운터의 시작·종료 시각을 복원하지 않는다. GD-142/응모 연동에서는 entry 전용 복합 평가 계약 `evaluateEntry(userKey, eventKey?, limits, policy.window())`를 제한기 경계에 추가한다. 아래 이름은 설계상 계약이며 이번 PR에 메서드를 구현한 것은 아니다.

| 계약 | 제공·보장 내용 |
| --- | --- |
| WindowSnapshot | 각 논리 키의 startedAt·endsAt. 구간 길이는 입력 policy.window()이며 1분 상수를 쓰지 않음 |
| EntryLimitDecision | allowed·retryAfterSeconds, 결과 구분(ALLOWED / RATE_LIMITED / BLOCKED), userWindow·eventWindow?, 거절 횟수 스냅샷과 차단 결과. BLOCKED는 기존 ACTIVE이면 구간 없이 최초 blockId·blockedUntil을 반환하며 허용·일반 제한 결과에는 구간 시각 제공 |
| evaluateEntry | ACTIVE 재확인부터 구간 준비·일반 제한 판정·거절 기록·임계값 도달 시 최초 ACTIVE 확정까지 하나의 원자 경계에서 수행. recordDenied와 차단 생성은 평가 내부 책임이며 반환 뒤 별도 후처리로 실행하지 않음 |

사용자·이벤트 구간은 각각 처음 관측한 응모 요청에서 시작한다. 전체 제한이 먼저 거절되는 요청에서도 이벤트 구간 상태를 준비하되 이벤트의 일반 요청 카운터는 기존 단축 판정과 같이 증가시키지 않는다. 메타데이터 준비는 일반 카운터를 0회 증가시키고, 실제 일반 제한 평가만 해당 카운터를 1회 증가시킨다. 이벤트 일반 평가가 나중에 처음 실행돼도 이미 준비된 구간의 만료를 갱신하지 않는다.

Redis 구현은 저장된 startedAt·endsAt·요청 수·거절 수를 같은 논리 구간 상태로 취급하고 Redis 서버 시각 기준 단일 Lua 평가에서 결과 스냅샷을 반환한다. 이 스크립트는 어떤 구간·카운터도 변경하기 전에 사용자 저널 인덱스의 ACTIVE와 blockedUntil을 재확인한다. 유효한 ACTIVE가 있으면 기존 blockId·blockedUntil의 BLOCKED 결과만 반환하며 일반·거절 카운터, 구간, 저널·이력과 TTL을 변경하지 않는다. 없으면 같은 스크립트 안에서 일반 판정·거절 기록과 임계값 도달 시 PREPARED 준비부터 최초 ACTIVE 확정까지 연속 처리한다. 외부의 빠른 차단 조회는 최적화일 뿐 이 재확인을 대체하지 않는다. 최초 ACTIVE 전환과 다른 요청의 재확인·카운터 변경 사이에 명령이 끼어들 수 없도록 모두 동일 Redis 인스턴스의 단일 스크립트 경계로 한정한다. Redis Cluster의 슬롯 간 실행은 이 설계에 포함하지 않는다. 현재 기준은 [Redis 런북의 원본 1대 구성](../06-runbook/redis.md#현재-확인한-상태)이며 배포 시 같은 원자 경계가 유지되는지 재확인한다. 메모리 구현도 주입된 TimeProvider와 사용자 단위 임계 구역 안에서 ACTIVE 재확인·모든 카운터 변경·최초 활성화를 끝내 같은 계약을 제공한다. 기존 Redis의 count==1 만료 설정과 메모리의 endMillis-only 상태를 그대로 재사용하지 않고 준비된 구간을 보존하도록 보완한다. 비응모 정책에는 새 entry 평가를 연결하지 않는다.

각 논리 키의 일반 요청·거절 카운터·구간 메타데이터는 같은 endsAt에 만료한다. 메타데이터 준비와 판정 사이에 구간이 바뀌어 서로 다른 창의 시각·카운터를 섞지 않도록 위 복합 평가 안에서 스냅샷을 확정한다. 차단 판단·evidence는 이 반환값만 사용하고 HTTP 계층 현재 시각·반올림된 Retry-After로 구간을 재구성하지 않는다.

entry 분기의 HTTP 변환은 평가 결과를 받은 뒤 Redis 예외 처리 구간 밖에서 수행한다. ALLOWED는 통과, RATE_LIMITED 및 신뢰 가능한 DENY_RECORDING_FAILED는 기존 RateLimitExceededException으로 변환해 429·COMMON-006과 판정의 retryAfterSeconds를 반환한다. BLOCKED도 같은 예외·오류 코드·ResponseEnvelope를 재사용하되 저널의 blockedUntil과 평가 시 Redis now로 계산한 남은 초를 사용한다. 새 공개 오류나 별도 차단 예외는 필요하지 않다. BLOCKED를 allowed로 해석하지 않으며, 결과 수신 뒤 파생 캐시 갱신 실패가 확정된 429를 통과로 바꾸지 않게 한다.

### 기존 Redis 상태 전환 — 배포 전제

기존 키 형식과 새 구간 메타데이터는 호환되지 않으므로 자동 혼용·부분 채택하지 않는다. 초기 전환은 응모 요청 유입과 기존 entry 제한 쓰기를 중지한 유지보수 구간에서 진행한다. 마지막 쓰기 이후 적용했던 모든 entry 창 길이 이상 기다리고 기존 entry 키가 모두 자연 만료됐음을 검사한 뒤 새 entry 평가를 활성화한다. 만료 없는 기존 키나 구형 writer가 남으면 중단하고 별도 확인하며 자동 삭제하지 않는다. 비응모 키는 변경하지 않는다. 모든 API 인스턴스를 한 평가 버전으로 전환한 뒤 유입을 재개하며 롤링 중 구·신 평가를 섞지 않는다. 실제 키 버전명·전환 명령·검사 결과는 구현 배포 runbook에서 확정한다.

롤백도 응모 유입과 신규 평가 쓰기를 먼저 중지한다. 새 집계 구간이 만료되고 모든 ACTIVE의 blockedUntil이 지났으며 미저장·격리 건까지 DB 저장이 확인된 뒤 기존 평가를 재개한다. 조건이 충족되지 않으면 구버전을 임의 가동하지 않고 중단 상태에서 복구한다. 이 방식으로 진행 중 창을 초기화하거나 차단·미저장 이력을 버리는 롤백을 피한다. 이번 PR에서 배포나 Redis 상태 변경을 실행하지 않았다.

### 사용자 확인과 영구 상태 생성 경계

entry 분기는 Redis 빠른 차단 조회·evaluateEntry보다 먼저 기존 CurrentUserArgumentResolver와 같은 헤더 형식·UserService.findById·DB 역할/멤버십 대조를 완료한다. 해당 handler의 @CurrentUser 조건을 그대로 사용하며 새 인증·권한 정책을 만들지 않는다. 현재 MVC에서는 preHandle이 argument resolver보다 앞서므로, 후속 구현은 기존 검증을 재사용 가능한 요청 단위 경계로 연결하고 검증된 User만 요청에 보관해 resolver의 재조회·중복 검증을 피한다. 공통 문맥 담당과 이 호출 순서·재사용 경계를 확인한 뒤 구현하며 이번 PR은 공통 코드를 변경하지 않는다.

미등록 UUID·문맥 불일치·형식 오류는 기존 사용자 문맥 예외·오류 코드로 종료하며, 어떤 Redis 일반/거절 카운터·차단 키·저널·사용자 인덱스·대기·격리 항목도 만들거나 조회하지 않는다. 사용자 조회 DB 장애도 기존 공통 오류 처리로 넘기며 Redis 장애 fail-open catch에 포함하지 않는다. 검증되지 않은 ID를 키로 별도 영구 실패 레코드나 양성 캐시를 만들지 않는다. 따라서 임의의 미등록 UUID 반복으로 보존 대상 차단·격리 상태를 쌓는 경로를 차단한다. 검증 후 실제 사용자 삭제와 FK 저장 간 경합은 기존 저장 실패·격리 경계에서 다룬다.

이 검증은 등록 사용자와 헤더 문맥의 일치 확인이며 호출자 신원 인증은 아니다. 실재하는 다른 사용자의 ID·역할을 아는 호출자의 사칭까지 해결했다고 주장하지 않는다. 기존 시연용 헤더 정책의 한계이며 인증 방식 변경은 별도 범위다. 사용자 상태·업무별 자격 판정과 비응모 흐름은 기존 Service·resolver 계약을 유지한다.

## Redis 처리 순서

1. entry guard와 사용자 존재·문맥 검증을 통과한 요청만 빠른 조회에 진입한다. 빠른 조회에서도 저널의 ACTIVE와 blockedUntil을 확인하고, Redis 서버 현재 시각보다 종료가 뒤일 때만 429를 반환한다. Retry-After는 ceil(blockedUntil - now)의 초 값이다. 키 TTL은 최적화·정리 힌트이며 차단 유효성이나 응답 시간의 원본이 아니다. 캐시가 먼저 만료되면 저널로 확인하고, 캐시 TTL이 길어도 blockedUntil이 지났으면 차단하지 않는다. 집계와 이력 생성, TTL 연장은 하지 않는다.
2. entry guard 안에서 빠른 조회에 차단이 없더라도 evaluateEntry 내부에서 ACTIVE를 원자적으로 재확인한다. 이미 활성화됐으면 아무 집계·이력 변경 없이 기존 차단 429를 반환한다. 없을 때만 같은 평가 안에서 일반 제한과 거절 횟수를 평가한다. 구간 길이는 policy.window()를 사용하고 각 거절 카운터는 대응하는 일반 요청 구간과 함께 만료한다. 첫 429에서 별도 구간을 만들지 않는다.
3. 차단 기준 도달 시 2번과 동일한 원자 평가 안에서 차단 생성·DB 저장 대기 등록·최초 ACTIVE 전환을 완료한다. 평가 반환 뒤 별도 명령으로 활성화하지 않는다. 사용자 전체·이벤트 기준이 동시에 충족되어도 차단 한 건만 만든다.
4. 차단 값과 대기 데이터에 최초 시작·종료 시각과 DB 기록용 식별자·근거를 보존한다. TTL만으로 최초 시작 시각을 재계산하지 않는다.
5. 저장 작업자가 대기 건을 읽어 DB 트랜잭션으로 저장한다. commit 후에만 완료 처리한다. 실패하면 대기 상태를 유지해 재시도하고, 경고 로그와 대기 적체를 관측한다.

Redis 원자 경계는 위 단일 Lua 평가로 고정하며 저장 대기 자료구조의 세부 선택은 구현에서 정한다. 스크립트 내부 오류가 이미 실행된 명령을 자동 롤백한다고 가정하지 않는다. 잘못된 키 타입·메모리 부족·부분 실패 시 차단만 생기거나 대기 데이터만 남는 경로를 검증한다. 저장 대기 TTL을 차단 TTL과 동일하게 두어 미저장 건을 자동 삭제하면 안 된다.

Redis 장애 응답은 이미 신뢰할 수 있는 제한 판정이 있는지를 기준으로 구분한다.

| 실패 위치 | 응답·처리 |
| --- | --- |
| 활성 차단 확인 실패 | 아직 판정이 없으므로 fail-open. 이번 요청의 차단·일반 제한·거절 집계를 중단하고 업무 처리로 통과, 경고 로그 |
| 일반 제한 evaluateEntry 자체 실패 | 유효한 제한 결과가 없으므로 fail-open. 부분 카운터를 근거로 429를 만들지 않고 통과·경고 |
| 유효한 일반 429 확정 후 거절 기록·차단 생성·대기 등록 실패 | fail-open 예외로 기존 429를 덮어쓰지 않음. 이미 확정한 일반 429와 Retry-After 유지, 경고·아래 부분 상태 복구 수행 |
| 활성 차단 또는 새 차단 결과를 정상 확인 | 429와 확인된 blockedUntil 기준 남은 차단 초 반환 |

응모 평가에서 일반 거절 판정 뒤 후속 Redis 명령이 실패하는 경우에는 이미 확정한 일반 판정·Retry-After를 담은 오류 결과와 '판정 전 실패'를 구분해야 한다. 확정은 API가 신뢰할 수 있는 일반 제한 결과를 수신한 상태를 뜻한다. Redis 평가가 일반 거절 뒤 집계 실패를 감지하면 원래 Retry-After와 함께 DENY_RECORDING_FAILED 같은 내부 결과 태그를 반환하도록 하며, 명령 응답 자체를 잃어 결과를 모르는 경우와 구분한다. 광범위한 RuntimeException catch로 둘 다 통과 처리하지 않는다. 차단 전환 결과가 확정됐는지 알 수 없는 후속 오류에서는 확인된 일반 429를 유지하고, 다음 요청이 아래 저널로 실제 활성 여부를 확인한다. Redis 복구 이후 남은 상태에 따라 제한이 다시 적용될 수 있다.

### 차단·저널·대기 등록의 부분 실패 복구

차단마다 후보 blockId(UUID)를 발급하고 최초 활성화에 성공한 ID를 DB id·저널·대기 항목에 동일하게 사용한다. 응모 멱등키나 제거 대상 detection_key와는 다른 차단 한 건의 ID다. 최초 시각·userId·sourceType·evidence도 함께 고정한다. per-user 인덱스는 여러 미저장 이력 ID를 보존할 수 있어야 하며 새 차단으로 이전 미저장 이력을 덮어쓰지 않는다.

복구 원본은 Redis의 blockId별 전체 페이로드 저널과 사용자별 인덱스다. 대기 큐는 소비를 깨우는 인덱스이며 단독 복구 원본으로 사용하지 않는다. 저널은 PREPARED → ACTIVE 전이와 별도 dbPersisted 플래그를 갖는다. DB commit은 dbPersisted만 바꾸며 종료 전 ACTIVE를 해제하지 않는다. PREPARED 준비·사용자 인덱스 등록·대기 등록을 먼저 완료하고, 그 뒤 저널의 ACTIVE 상태와 최초 판정·시작·종료 시각을 하나의 상태 쓰기 명령으로 함께 확정한다. 이 준비·확정 명령들은 모두 위 evaluateEntry의 동일 Lua 실행 안에 포함되며 별도 왕복으로 분리하지 않는다. 이 ACTIVE 확정을 논리적 차단 시작점으로 삼는다. 빠른 조회용 활성 키와 TTL은 이후 파생 캐시로 구성한다.

활성 키가 없으면 사용자별 저널 인덱스에서 ACTIVE이며 blockedUntil을 넘지 않은 기록을 확인한다. 실제 차단 판정은 저장된 종료 시각을 사용하며 복구 과정에서 10분을 새로 부여하지 않는다. 차단 키·캐시·대기 인덱스가 일부 유실되어도 저널에서 복구하며, Redis 저장소 자체 유실은 아래의 합의된 예외다. 저널·사용자 인덱스는 DB 저장 확인과 blockedUntil 경과가 모두 충족된 뒤에만 정리한다. DB 저장 전 차단 TTL 만료로 근거를 잃지 않는다.

| 부분 상태 | 복구·재처리 규칙 |
| --- | --- |
| PREPARED 저널/대기만 있고 ACTIVE 확정 없음 | 실제 차단·DB 이력으로 취급하지 않음. 원자 명령 종료 뒤 미활성 후보를 취소·정리하고 다음 요청에서 새 후보로 평가. 대기 작업자는 PREPARED를 저장하지 않음 |
| ACTIVE 저널은 있으나 활성 캐시 또는 대기 인덱스 없음 | 동일 blockId·최초 시각으로 캐시/대기 인덱스 재생성. 주기적 저널 스캔도 수행해 후속 요청이 없어도 DB 저장 재시도. 종료 시각이 지났으면 다시 차단하지 않고 이력 저장만 수행 |
| 활성 캐시/대기 항목만 있고 저널 없음 | 확인할 원본이 없어 새 이력·새 차단을 합성하지 않음. 해당 ID의 파생 항목만 무효화·경고하고 기존 일반 429 또는 판정 전 fail-open 규칙 적용 |
| DB 행은 commit됐으나 완료 표시 실패 | 같은 blockId로 재전달·기존 행과 비교 후 저장 확인 표시. 다른 값의 PK/복합 UNIQUE 충돌은 성공으로 삼키지 않음 |

전체 생성은 Redis에서 직렬화하여 동시 후보 중 하나만 ACTIVE가 되게 한다. 활성화 이전 명령의 타입·인자·키 충돌을 사전 검증하고, ACTIVE 확정 후 파생 캐시 쓰기 실패를 활성 취소로 해석하지 않는다. 실패 주입은 각 단계 뒤에 수행해 사용자 응답, ACTIVE 유무, 이력 생성 여부와 같은 ID·시각 보존을 함께 검증한다. PREPARED·ACTIVE는 Redis 내부 처리 상태이며 DB 검토 상태나 공개 DTO 상태로 추가하지 않는다.

### 저장 재시도와 정기 정리

저장 작업자의 정기 유지보수 루프가 후속 요청 유무와 관계없이 DB 저장 확인·종료 경과를 함께 검사하고, 두 조건이 모두 참인 저널·사용자 인덱스의 해당 ID·파생 항목을 정리한다. 여러 차단 ID 중 해당 건만 제거하며 PREPARED는 평가 종료가 확인된 미활성 후보만 정리한다. 활성화와 정리의 경합도 Redis 원자 경계에서 재확인한다.

일시 DB 장애는 nextAttemptAt에 따른 지수 backoff와 jitter를 사용하고 재시도 간격 상한을 둔다. 영구적인 입력·무결성 오류나 운영 재시도 한도 도달은 별도 delivery 상태 QUARANTINED로 격리하고 경고한다. 수치와 운영 알림 경로는 구현 운영값이다. ACTIVE·dbPersisted와 delivery 상태는 별개다. 격리는 차단을 조기 해제하거나 dbPersisted=true로 간주하지 않는다. 전체 최초 payload와 사용자 인덱스는 보존하고 일반 재시도 큐·일반 미저장 스캔에서는 제외한다. 종료 전 활성 확인 대상에는 계속 포함하며, 종료 후에도 DB 미저장이므로 삭제하지 않는다. 격리 목록은 별도 운영 조회·복구 대상으로 두고 자동 무한 재시도하지 않는다. 원인 수정 후 명시적 재처리로 같은 ID·시각을 다시 저장하며 DB 확인 뒤 종료 조건을 충족하면 정리한다. 용량·격리 적체도 관측한다.

### 저장 대기의 실패 복구

- DB 실패 시 차단은 유지하고 같은 최초 시각·식별자·근거로 저장을 재시도한다. 차단이 이미 끝났어도 이력 저장은 필요하다.
- DB commit 직후 작업자가 중단되어 같은 건을 다시 읽어도 복합 UNIQUE로 중복을 막고 기존 기록과 일치하는지 확인한 후 완료 처리한다. 다른 데이터의 무결성 오류를 모두 중복 성공으로 처리하지 않는다.
- 작업자 재시작·여러 작업자의 동시 소비·처리 도중 중단 건 재인계가 가능해야 한다. 재시도 주기·배치 크기·경고 기준은 구현 운영값이며 이번 대화에서 숫자를 정하지 않았다.
- Redis 저장소 자체 데이터가 유실되면 차단·저널·미저장 이력이 유실될 수 있다. 이는 [공용 응모 규칙의 GD-141 예외](https://github.com/Uplus-GETDDO/getddo-spec/blob/main/02-domain/entry.md#응모-요청-반복-초과에-따른-일시-차단--gd-141)와 [ADR-027 영향·재검토 조건](https://github.com/Uplus-GETDDO/getddo-spec/blob/main/03-decisions/027-entry-request-temporary-blocking.md#영향과-재검토-조건)에 명시적으로 허용된 초기 범위다. DB 실패·파생 키 부분 실패 복구와 저장소 자체 유실을 구분하며, 이번 설계에서 유실 없는 보장이나 DB outbox를 임의 추가하지 않는다.

## 테이블 변경 계획 — SQL 미작성

기존 abuse_cases를 재사용하는 방향이다. 다음은 목표 모델이며 현재 DB 스키마가 아니다.

| 항목 | 계획 |
| --- | --- |
| 추가 | source_type VARCHAR(40) NOT NULL, blocked_at DATETIME(6) NOT NULL, blocked_until DATETIME(6) NOT NULL. 빈 테이블 전환 조건 아래 기본값 없이 추가 |
| 신규 차단 행 필수 | 기존 id·user_id NOT NULL을 유지하고 source_type·blocked_at·blocked_until·evidence에도 DB NOT NULL 적용. detection_type·detection_reason·occurred_at·detected_at의 기존 NOT NULL 유지. core 생성·저장 입력에서도 필수값 검증 |
| 중복 방지 | UNIQUE(user_id, source_type, blocked_at). V009의 user_id는 이미 NOT NULL이며 신규 두 컬럼도 DB NOT NULL로 추가하므로 NULL을 통한 UNIQUE 우회를 허용하지 않음. 기존 detection_key UNIQUE는 빈 테이블 확인 후 제거하며 기존 탐지 계약을 병행 운영하지 않음 |
| 제거 대상 | detection_key 및 기존 UNIQUE, request_id 및 관련 근거 CHECK 보완, review_status·reviewed_by·reviewed_at·review_reason 및 검토 CHECK·FK |
| 재사용 | event_id와 기존 출석·미션·게임 원본 FK, detection_type, detection_reason, evidence. event_entry_id는 현재 스키마에 없고 이번 변경에 추가하지 않음 |
| 기존 시각 | occurred_at=차단 임계값에 도달하게 한 요청의 서버 수신 시각, detected_at=Redis가 임계값 도달·차단 생성을 확정하는 시각, blocked_at=같은 detected_at. 기존 두 시각은 NOT NULL·API 비노출 유지 |
| 조회 인덱스 후보 | (blocked_at, id), (user_id, blocked_at, id). 나머지 필터 인덱스는 쿼리·실행계획 검증 후 결정 |

복합 UNIQUE는 같은 차단의 중복 저장을 막을 뿐 겹치는 다른 차단의 생성을 막지 않는다. 활성 차단의 중복 생성은 Redis 원자 처리 책임이다. Redis와 DB가 동일한 blocked_at을 사용해야 하며 DATETIME(6) 정밀도에 맞춰 한 번만 정규화한다.

UUID 형식의 이벤트 경로 값은 집계용 scope일 뿐 실제 이벤트 FK로 확정된 값이 아니다. 기존과 같이 제한 평가가 업무 이벤트 조회보다 앞선다. UUID가 식별되면 기존 범위의 일반·거절 집계에 사용하되, 허용 요청은 기존 업무 조회에서 미존재 시 404를 반환하고 그 404 자체는 거절 집계하지 않는다. 이미 일반 429 또는 활성 차단으로 거절됐으면 업무 조회의 404로 덮어쓰지 않는다. 차단 payload의 event_id는 별도 기존 이벤트 조회 경계를 통해 실재가 확인된 경우에만 저장하고 미존재·미확인은 NULL로 고정한다. 이 조회는 Lua 밖에서 수행하며 이벤트 조회 장애가 제한 판정을 바꾸지 않도록 NULL로 기록·경고한다. 이벤트별 집계 사실은 evidence에 보존하며 event_id가 NULL이어도 범위가 USER로 바뀌지는 않는다. FK 저장 직전 이벤트 삭제 경합은 저장 실패·격리 계약을 따르고 최초 payload를 임의 수정하지 않는다. 제한보다 DB 조회를 앞세우거나 모든 미존재 UUID를 제한에서 제외하는 정책 변경은 이번 설계에 포함하지 않는다.

event_id는 현재 V009의 nullable 컬럼이며 `events.id` FK를 유지한다. 차단을 유발한 요청에서 유효한 이벤트를 확인할 수 있으면 그 ID를 저장하고, 확인할 수 없으면 NULL이다. 사용자 전체 차단을 해당 이벤트에만 한정하는 값이나 응모 기록 ID로 사용하지 않는다.

현재 PR 기준 abuse_cases에는 event_entry_id 컬럼·FK가 없다. 이번 목표 스키마에도 추가하지 않는다. 응모 처리 전 차단에는 원본 응모 행이 필요하지 않으며 사용자·관련 이벤트·evidence로 근거를 남긴다. 기존 attendance_id·mission_submission_id·game_play_id의 nullable·FK는 유지하되 신규 ENTRY 차단 행에는 모두 NULL을 저장한다.

닉네임·이벤트 제목은 조회에서 조인해 DTO로 제공하며 차단 테이블에 중복 저장하지 않는다. 현재 차단 여부 boolean도 저장하지 않는다. 종료 예정 시각이 지나도 이력 행을 수정하지 않는다. 수동 해제·차단 시간 연장 기능은 이번 범위에 없다.

### evidence 저장 구조

차단 당시 판정에 사용한 최소 근거를 JSON 객체로 보존한다. 설정이 변경되어도 과거 차단을 설명할 수 있도록 관측값과 당시 임계값을 함께 저장한다. 다음은 대화에서 설명한 필드명 제안이며 공개 세부 계약 확정·구현 완료를 의미하지 않는다. 적용 대상·시각·사용자 정보는 별도 컬럼과 역할을 나눈다.

```json
{
  "ruleVersion": "v1",
  "triggerScope": "EVENT",
  "windowStartedAt": "2026-10-21T06:00:00Z",
  "windowEndsAt": "2026-10-21T06:01:00Z",
  "requestLimit": 10,
  "rejectedCount": 10,
  "rejectionThreshold": 10
}
```

| 필드 | 타입 | 의미 |
| --- | --- | --- |
| ruleVersion | string | 차단 판정 규칙 버전 |
| triggerScope | string | USER=사용자 전체, EVENT=같은 이벤트. source_type=ENTRY와는 다른 차원 |
| windowStartedAt, windowEndsAt | UTC instant 문자열 | 기준에 도달한 카운터의 집계 구간. 첫 거절에서 새 구간을 시작하지 않음 |
| requestLimit | 양의 정수 | 해당 범위의 일반 요청 허용 한도 |
| rejectedCount | 양의 정수 | 차단 판정 시 관측한 거절 횟수 |
| rejectionThreshold | 양의 정수 | 판정 당시 일시 차단 진입 임계값 |

신규 ENTRY 자동 차단의 detection_type은 공용 계약의 `REPEATED_RATE_LIMIT_EXCEEDED`만 허용한다. detection_reason은 서버가 `응모 요청 한도 초과가 반복되어 일시 차단되었습니다.`라는 고정 문구로 생성하며 USER·EVENT 모두 같은 문구를 사용한다. 요청 본문이나 기존 탐지 사유를 입력값으로 받아 재사용하지 않는다. USER·EVENT 어느 거절 임계값에서 시작해도 같은 코드이며, 범위 차이는 evidence.triggerScope와 그 범위의 WindowSnapshot·설정값·관측 횟수로 표현한다. USER면 사용자 전체 한도·거절 임계값, EVENT면 이벤트별 한도·거절 임계값을 담으며 현재 운영값을 코드에 하드코딩하지 않는다.

USER 임계값만 도달하면 triggerScope=USER, EVENT 임계값만 도달하면 triggerScope=EVENT로 매핑한다. 둘 중 하나로 차단하는 OR 조건을 유지하며 AND 조건으로 바꾸지 않는다. 두 기준이 한 평가에서 동시에 도달하면 기존 전체 우선 판정 순서에 맞춰 USER를 대표 근거로 선택하고 한 건만 생성하는 내부 매핑안을 사용한다. 이 대표 선택은 아직 초안인 evidence 세부 계약을 구체화하는 BE 설계안이며 공용 JSON 필드 확정 시 함께 동기화한다. core 생성·저장 입력은 ENTRY/위 detection_type 조합, USER·EVENT 값과 해당 범위의 임계값 도달 여부를 검증하고 잘못된 조합을 거절한다. mapper도 기존 일반 탐지 코드·사유를 신규 차단 값으로 자동 변환하지 않고 source_type/detection_type/detection_reason 조합이 위 서버 정의와 다르면 거절한다. DB는 기존 detection_reason NOT NULL을 유지하고 새 마이그레이션에 ENTRY 행의 detection_type과 detection_reason이 위 고정값 조합인지 확인하는 CHECK를 추가하는 목표다. core 생성·저장 입력도 null·공백·다른 사유를 거절한다. 최초 생성한 detection_reason을 저널·대기에 함께 보존하며 DB 재시도에서 사유나 evidence를 최신 설정·문구·이후 요청 수로 다시 계산하지 않는다. JSON 원문을 관리자에게 무제한 전달하지 않고 이 허용 필드만 DTO로 변환한다. 원본 요청 본문·토큰·무관한 개인정보는 저장·응답하지 않는다. 관리자 상세의 관련 요청은 occurred_at·사용자·확인된 이벤트와 당시 판정 근거로 설명한다. 개별 HTTP 로그를 찾는 requestId/sourceId 계약은 채택하지 않았으므로 고유 요청 추적 기능을 제공한다고 표시하지 않는다. 추가 추적 식별자가 필요하면 공용 API 계약을 별도로 확인한다.

### 시각 기록과 재시도 불변성

서버가 임계값 도달 요청을 받은 시각을 occurred_at으로 전달하고, Redis 원자 처리에서 한 번 읽은 서버 시각을 detected_at과 blocked_at에 동일하게 사용한다. blocked_until은 이 blocked_at과 당시 적용한 차단 기간으로 한 번 계산한다. 최초 차단 생성에 성공한 한 요청의 시각만 차단 값·저장 대기 건에 보존하며, 동시에 기준에 도달한 다른 요청의 수신 시각으로 교체하지 않는다.

DB 저장 작업자는 대기 건의 occurred_at·detected_at·blocked_at·blocked_until을 그대로 사용한다. 저장 재시도·중복 전달·작업자 재시작 시 현재 시각이나 TTL로 다시 계산하지 않는다. DB에 넣을 시각은 DATETIME(6) 정밀도로 한 번 정규화해 대기 건에도 같은 값으로 저장한다. API는 이 중 blockedAt·blockedUntil만 노출한다.

startedAt·endsAt·occurred_at·detected_at·blocked_at·blocked_until은 모두 UTC instant다. Redis TIME의 epoch 시각으로 만료·신규 차단 종료를 계산하고, MySQL 세션·JDBC 변환도 UTC로 맞춰 DATETIME(6)에 UTC 값을 저장·비교한다. 조회 DTO는 같은 instant를 UTC ISO-8601(Z)로 반환한다. DATETIME 자체에 시간대가 없다는 점을 고려해 로컬 기본 시간대에 의존한 변환을 금지하고 JVM·DB 세션 시간대가 달라도 왕복 값이 같음을 검증한다. 현재 연결 설정이 이 계약을 충족하는지는 후속 구현에서 검증한다.

### 빈 테이블 전환과 마이그레이션 선행 조건

2026-10-21 사용자는 실제 서비스 어뷰징 데이터가 없고 현재 데이터는 더미이며, 데모 데이터는 새 스키마 기준으로 별도 주입한다고 확인했다. 이는 사용자 제공 전제이며 실제 DB 행 수를 이번 문서 작업에서 조회한 결과는 아니다. 별도 보존 테이블·과거 데이터 이전을 설계하지 않고, GD-143 적용 환경에서 abuse_cases가 비어 있는 것을 검증한 경우에만 전환한다.

1. 배포 대상에서 기존 abuse_cases 쓰기를 중지한 상태로 실제 행 수 0을 확인한다. 검사와 DDL 사이에도 쓰기를 허용하지 않는다. 행이 있으면 마이그레이션을 중단하며 더미라고 가정해 자동 DELETE·TRUNCATE·덮어쓰기를 하지 않는다. 비우기나 보존은 별도 확인 후 처리한다.
2. 빈 테이블에서 detection_key와 기존 UNIQUE, request_id 및 chk_abuse_source_evidence, review_*와 chk_abuse_review_shape·reviewed_by FK를 제거한다. 이전 탐지·검토 생산 경로를 함께 운영하지 않으며 기존 detection_key를 신규 키로 임의 변환하지 않는다. 빈 테이블이므로 보존할 기존 detection_key·review_* 값과 병행할 기존 중복 계약이 없다.
3. source_type·blocked_at·blocked_until을 NOT NULL로 추가하고 evidence도 NOT NULL로 강화한다. 기존 user_id 등 필수 조건을 유지하고 UNIQUE(user_id, source_type, blocked_at)와 CHECK(blocked_until > blocked_at)를 추가한다. 원본 없는 신규 차단은 필수 evidence로 근거를 보장하며 request_id 의존 제약을 남기지 않는다.
4. 새 스키마 기준 데모 시드를 별도로 주입한다. 기존 탐지·검토 행을 가짜 차단 시각으로 변환하지 않는다. AR01/AR02는 source_type=ENTRY이고 blocked_at이 있는 자동 차단 행만 조회한다. 새 스키마에서는 blocked_at이 NOT NULL이므로 기존 행을 위한 nullable 예외가 없다.

현재 PR 기준 V009·DBML·api/core/storage의 Java·SQL을 대조했으며 ticket_recovery_targets 테이블 또는 abuse_case_id 참조는 확인되지 않았다. 이를 실제 기존 의존성으로 가정하지 않는다. event_entry_id도 현재 abuse_cases의 컬럼이 아니므로 오래된 로컬 문서의 이름으로 DDL을 만들지 않는다. 적용 직전 최신 기준에서 다시 대조한다.

기존 Flyway를 수정하지 않고 위 전환을 새 버전으로 추가한다. 현재 테스트의 기존 review_*·detection_key 기반 INSERT는 GD-143에서 새 시드로 수정하며, 정상 응모가 차단 이력 때문에 추첨에서 제외되지 않는 검증은 유지한다. 이번 작업은 문서 변경이며 ERD·DDL·DB 데이터를 변경하지 않는다.

## 모듈·후속 작업

- GD-142: core 차단 이력 모델·Service·Repository, 저장 입력 검증·조회 모델 계약.
- GD-143: 빈 테이블 검증을 선행하는 신규 Flyway·JPA·변환·복합 UNIQUE 처리·조회 구현. 행이 있으면 중단하고 별도 확인한다. 새 데모·테스트 시드 전환 포함.
- GD-144: 활성 관리자 전용 AR01/AR02와 HTTP DTO·필터·공통 응답·권한 오류.
- 응모 제한 연동: 인계받은 인터셉터·Redis 카운터에 차단·대기 등록 연결. 원자 경계·저장 작업자 구체 구현은 후속. 기존 지급·응모 로직을 중복 구현하지 않는다.
- API 오류 번호 중 관리자 권한 거절·상세 없음의 도메인 번호는 구현 전에 기존 ErrorCode와 대조해 확정한다. 이번 대화에서 새 번호를 만들지 않았다.

## 검증 계획 — 아직 실행하지 않음

- 첫 요청 기준 시간 구간, 정확한 만료 경계, 거절 임계값 직전·도달과 두 기준 동시 도달.
- 전체 한도 초과 경로는 기존 이벤트 제한기를 건너뛰어도 사용자·해당 이벤트 거절 카운터가 각각 정확히 1 증가하는지 검증한다. 이벤트 한도 초과 경로도 두 거절 카운터가 각각 1 증가하고 한 요청이 예외 처리에서 재집계되지 않음을 검증한다. 일반 허용 한도와 429·Retry-After 동작은 기존 회귀 검증으로 유지한다.
- 일반 허용 요청은 거절 횟수를 올리지 않으며, 전체 제한에 먼저 걸린 이벤트의 첫 요청도 올바른 집계 구간을 갖고 거절 카운터가 그 만료를 넘겨 살아남지 않음을 검증한다.
- policy=entry guard를 검증한다. 같은 사용자의 출석·미션·게임 429가 응모 거절 횟수·이력·차단을 만들지 않고, 응모 차단 중에도 다른 기능은 기존 제한 정책으로 처리됨을 검증한다.
- 메모리·Redis 모두 evaluateEntry의 시작·종료 시각, 메타데이터 준비 시 일반 카운터 증가 없음, 이벤트 첫 요청의 전체 한도 선행 거절, 구간 경계 동시 요청·구간 불변을 같은 시나리오로 검증한다. policy.window()를 1분 외의 값으로 설정해 반영과 공통 종료 시각도 확인한다.
- 차단 중 추가 요청의 TTL 불변·추가 이력 없음, 차단 만료 뒤 새 집계, 설정의 양수·집계 시간 ≤ 차단 시간 검증.
- 두 연결 모두 빠른 조회에서 미차단을 본 뒤 한 연결이 먼저 ACTIVE를 확정하는 경합을 검증한다. 다른 연결은 evaluateEntry 재확인에서 동일 blockId·blockedUntil을 받고 일반·거절 카운터, TTL·이력을 전혀 변경하지 않아야 한다. 메모리에서도 같은 사용자 임계 구역 경합을 검증한다.
- Redis 여러 연결의 동시 차단 1건·동일 blockId, 각 PREPARED/인덱스/대기/ACTIVE/캐시 단계의 실패 주입과 위 복구 표를 검증한다. 후속 요청이 없어도 ACTIVE 저널 스캔으로 저장되고 차단 만료 뒤 새 차단을 복구 생성하지 않음을 확인한다.
- 활성 확인 실패·판정 전 일반 제한 실패는 통과하고, 일반 429 확정 뒤 거절 기록/차단 생성/대기 등록 실패는 원래 429·Retry-After를 유지하는지 각각 검증한다. Redis 저장소 자체 유실은 명시된 예외로 별도 확인한다.
- MySQL 저장 실패·DB commit 후 작업자 중단·중복 전달·작업자 재시작과 미완료 건 재인계·차단 만료 후 뒤늦은 이력 저장.
- occurred_at은 임계값 도달 요청의 최초 수신 시각, detected_at=blocked_at은 최초 Redis 판정 시각인지 검증하고, DB 실패·중복 전달·작업자 재시작 후에도 세 값과 blocked_until이 모두 최초 값 그대로임을 assertion으로 확인한다.
- 빈 테이블 마이그레이션 성공·행이 있는 환경의 중단과 무삭제, 신규 필수값 NULL INSERT 거절, 복합 UNIQUE 중복 거절, blocked_until <= blocked_at CHECK 거절, 기존 FK 및 event_id NULL/유효 FK를 검증한다. 새 스키마와 테스트·데모 시드의 일치도 확인한다.
- entry 분기 안에서 이벤트 ID 정보 누락·형식 오류 시 eventKey 없이 사용자 전체 제한·거절·USER 단독 차단이 작동하고, 일반 허용 뒤의 400은 거절 집계에서 제외되는지 검증한다. 사용자 ID 누락·형식 오류의 기존 처리는 유지한다. entry handler에 매핑되지 않는 404·405 요청은 이 평가 대상이 아님도 확인한다.
- ENTRY의 detection_type·detection_reason 고정값, null·공백·다른 사유·잘못된 조합의 core/mapper/DB 거절, 저장 실패 재시도 후 최초 사유 불변을 검증한다. USER 또는 EVENT 단독 임계값으로 각각 차단, 범위별 설정·관측값 매핑, 동시 충족 시 USER 대표·한 건 저장을 검증한다. AR01/AR02가 ENTRY 자동 차단 이력만 조회하며 단순 탐지·검토를 생성·변환하지 않는지, 목록 정렬·페이징·필터와 상세 근거의 관리자 전용 경계를 검증한다.
- UTC 시각의 Redis·MySQL·JDBC·API 왕복, TTL과 blockedUntil 불일치, ALLOWED/RATE_LIMITED/BLOCKED 및 결과 수신 뒤 실패의 HTTP 변환을 검증한다.
- 기존 entry 창의 자연 만료 전·후 전환, 구·신 writer 혼재 차단, 미저장·격리·ACTIVE가 남은 롤백 중단을 검증한다. 후속 요청 없는 성공 건 정리·backoff·영구 실패 격리·같은 payload 재처리와 종료 전 차단 유지도 검증한다.
- UUID 형식이 맞지만 미존재 이벤트인 요청의 일반 허용 뒤 404와 한도 초과 429를 구분하고, event_id=NULL·EVENT 근거 저장과 이벤트 조회 실패가 제한 판정을 덮지 않는지 검증한다.
- 미등록 UUID 대량 요청·역할/멤버십 불일치·사용자 조회 장애가 Redis 평가 전에 기존 오류로 종료되고 카운터·저널·대기·격리 상태를 하나도 만들지 않는지 검증한다. 정상 사용자는 DB 조회 결과를 요청 내 재사용하고 사용자 삭제 경합은 보존·격리 경계로 전달되는지 확인한다.
- 실제 연동 후 전체 ./gradlew test, ./gradlew build. 일부 테스트나 설계 문서 검증을 구현 완료로 표현하지 않는다.
