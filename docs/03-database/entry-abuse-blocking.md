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

기존 인터셉터는 사용자 전체 한도를 넘으면 이벤트별 제한기를 호출하지 않는다. 일반 허용 한도·429 판정은 유지하되, 신규 응모 연동에서는 일반 제한 판정과 거절 횟수 기록을 분리한다. `tryAcquire`를 거절 집계 목적으로 다시 호출해 일반 요청 횟수를 두 번 올리지 않는다.

- 사용자 전체·식별 가능한 이벤트의 집계 구간 메타데이터는 각 대상의 첫 응모 요청에서 준비한다. 전체 한도 초과로 기존 이벤트 제한기를 건너뛰어도 해당 이벤트의 첫 요청·만료 시각은 기록한다. 이벤트의 거절 카운터를 첫 429 시점부터 새 구간으로 만들지 않는다.
- 일반 제한 결과를 한 번 확정한 뒤 허용이면 거절 카운터를 변경하지 않는다. 일반 429이면 한 곳에서만 `recordDenied(userId, eventId, window)`를 호출해 사용자 전체와 해당 이벤트의 거절 횟수를 각각 한 번 증가시킨다. 이 이름은 후속 구현의 책임을 설명하는 표기이며 현재 제공 코드가 아니다.
- 전체 한도에서 거절된 경로도 동일한 기록 진입점을 사용하므로 이벤트별 거절이 빠지지 않는다. 이벤트 한도에서 거절된 경로에서도 다시 예외를 처리하며 기록하지 않아 각 카운터가 2회 증가하지 않게 한다.
- 이벤트 경로 ID가 UUID로 식별되지 않으면 이벤트별 키를 만들지 않고 기존 요청 해석·오류 계약을 따른다. 차단 중 거절·저장소 장애 통과는 일반 429 거절 집계에서 제외한다.

사용자 식별은 기존 X-User-ID 기반의 한계가 있다. 헤더 UUID는 신원 인증의 증거가 아니며, DB 저장 시 사용자 FK와 사용자 문맥을 확인해야 한다. 공통 사용자·오류 계약과 다른 기능의 제한 흐름은 임의 변경하지 않는다.

## Redis 처리 순서

1. 사용자별 응모 차단 키가 있으면 남은 TTL로 429를 반환한다. 집계와 이력 생성, TTL 연장은 하지 않는다.
2. 차단이 없으면 기존 일반 제한을 판정하고 해당 1분 구간의 거절 횟수를 집계한다. 각 거절 카운터는 대응하는 일반 요청 카운터의 만료 시각을 공유한다. 첫 429에서 별도 1분 구간을 만들지 않는다.
3. 차단 기준 도달 시 차단 미존재 확인·차단 생성·DB 저장 대기 등록을 하나의 원자적 작업으로 처리한다. 사용자 전체·이벤트 기준이 동시에 충족되어도 차단 한 건만 만든다.
4. 차단 값과 대기 데이터에 최초 시작·종료 시각과 DB 기록용 식별자·근거를 보존한다. TTL만으로 최초 시작 시각을 재계산하지 않는다.
5. 저장 작업자가 대기 건을 읽어 DB 트랜잭션으로 저장한다. commit 후에만 완료 처리한다. 실패하면 대기 상태를 유지해 재시도하고, 경고 로그와 대기 적체를 관측한다.

Lua 등 구체적인 원자 처리 수단과 저장 대기 자료구조는 구현에서 선택한다. 스크립트 내부 오류가 이미 실행된 명령을 자동 롤백한다고 가정하지 않는다. 잘못된 키 타입·메모리 부족·부분 실패 시 차단만 생기거나 대기 데이터만 남는 경로를 검증한다. 저장 대기 TTL을 차단 TTL과 동일하게 두어 미저장 건을 자동 삭제하면 안 된다.

Redis 연결 실패는 기존 제한기의 fail-open 정책을 따른다. 일반 요청 집계·차단 여부 확인을 수행하지 못하면 요청을 통과시키고 경고 로그를 남긴다. Redis 복구 이후 남은 키에 따라 제한이 다시 적용될 수 있다.

### 저장 대기의 실패 복구

- DB 실패 시 차단은 유지하고 같은 최초 시각·식별자·근거로 저장을 재시도한다. 차단이 이미 끝났어도 이력 저장은 필요하다.
- DB commit 직후 작업자가 중단되어 같은 건을 다시 읽어도 복합 UNIQUE로 중복을 막고 기존 기록과 일치하는지 확인한 후 완료 처리한다. 다른 데이터의 무결성 오류를 모두 중복 성공으로 처리하지 않는다.
- 작업자 재시작·여러 작업자의 동시 소비·처리 도중 중단 건 재인계가 가능해야 한다. 재시도 주기·배치 크기·경고 기준은 구현 운영값이며 이번 대화에서 숫자를 정하지 않았다.
- Redis 데이터 자체가 유실되면 차단과 아직 DB에 기록되지 않은 대기 건이 유실될 수 있다. 이 한계를 허용한 초기 설계다. 유실 없는 기록을 보장한다고 표현하지 않는다.

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

두 기준이 동시에 충족되면 어떤 기준을 대표 사유로 남길지는 구현 전에 결정하고 동일 차단을 두 건으로 저장하지 않는다. DB 재시도에서 evidence를 최신 설정이나 이후 요청 수로 다시 계산하지 않는다. JSON 원문을 관리자에게 무제한 전달하지 않고 이 허용 필드만 DTO로 변환한다. 원본 요청 본문·토큰·무관한 개인정보는 저장·응답하지 않는다.

### 시각 기록과 재시도 불변성

서버가 임계값 도달 요청을 받은 시각을 occurred_at으로 전달하고, Redis 원자 처리에서 한 번 읽은 서버 시각을 detected_at과 blocked_at에 동일하게 사용한다. blocked_until은 이 blocked_at과 당시 적용한 차단 기간으로 한 번 계산한다. 최초 차단 생성에 성공한 한 요청의 시각만 차단 값·저장 대기 건에 보존하며, 동시에 기준에 도달한 다른 요청의 수신 시각으로 교체하지 않는다.

DB 저장 작업자는 대기 건의 occurred_at·detected_at·blocked_at·blocked_until을 그대로 사용한다. 저장 재시도·중복 전달·작업자 재시작 시 현재 시각이나 TTL로 다시 계산하지 않는다. DB에 넣을 시각은 DATETIME(6) 정밀도로 한 번 정규화해 대기 건에도 같은 값으로 저장한다. API는 이 중 blockedAt·blockedUntil만 노출한다.

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
- 차단 중 추가 요청의 TTL 불변·추가 이력 없음, 차단 만료 뒤 새 집계, 설정의 양수·집계 시간 ≤ 차단 시간 검증.
- Redis 여러 연결의 동시 차단 1건·대기 1건, Redis 장애 fail-open과 경고, 부분 실패·데이터 유실 한계 확인.
- MySQL 저장 실패·DB commit 후 작업자 중단·중복 전달·작업자 재시작과 미완료 건 재인계·차단 만료 후 뒤늦은 이력 저장.
- occurred_at은 임계값 도달 요청의 최초 수신 시각, detected_at=blocked_at은 최초 Redis 판정 시각인지 검증하고, DB 실패·중복 전달·작업자 재시작 후에도 세 값과 blocked_until이 모두 최초 값 그대로임을 assertion으로 확인한다.
- 빈 테이블 마이그레이션 성공·행이 있는 환경의 중단과 무삭제, 신규 필수값 NULL INSERT 거절, 복합 UNIQUE 중복 거절, blocked_until <= blocked_at CHECK 거절, 기존 FK 및 event_id NULL/유효 FK를 검증한다. 새 스키마와 테스트·데모 시드의 일치도 확인한다.
- AR01/AR02가 ENTRY 자동 차단 이력만 조회하며 단순 탐지·검토를 생성·변환하지 않는지, 목록 정렬·페이징·필터와 상세 근거의 관리자 전용 경계를 검증한다.
- 실제 연동 후 전체 ./gradlew test, ./gradlew build. 일부 테스트나 설계 문서 검증을 구현 완료로 표현하지 않는다.
