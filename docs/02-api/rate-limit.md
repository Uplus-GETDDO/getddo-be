# 요청 빈도 제한 (429)

- 상태: 검토 대기 · 담당 윤태형(GD-146) · 수치는 확정 전 추천값
- 적용 대상: 응모(E04), 출석(AT02), 미션 제출(M03), 게임 결과 제출(G04). G04는 정책 `game-result`(분당 10회)로 `GameResultController`에 `@RateLimited(policy = "game-result")`가 붙어 있다.

## 응모 전용 확장 — GD-144

응모(entry)는 [자동 차단 구현](entry-auto-blocking.md)에 따라 기존 문맥 검증 후 일반·거절 집계를 평가한다. 기존 ACTIVE의 읽기 전용 거절만 DB 조회 없이 처리한다. 따라서 아래 비즈니스 검증 전 일반 제한 설명은 비응모 정책과 구별해야 한다. 응모 정책·수치는 [공용 응모 규칙](https://github.com/Uplus-GETDDO/getddo-spec/blob/main/02-domain/entry.md#응모-요청-반복-초과에-따른-일시-차단--gd-141)을 기준으로 한다. DB 차단 이력 저장은 GD-291 후속이며 Redis 저장 대기 등록과 구별한다.

## 동작

사용자별로 일정 시간 안에 보낼 수 있는 요청 수를 제한한다. 한도를 넘으면 `429 Too Many Requests`, 오류 코드 `COMMON-006`(`RATE_LIMIT_EXCEEDED`), `Retry-After`(다시 시도할 수 있을 때까지 남은 초) 헤더를 돌려준다. 거절 요청은 비즈니스 로직과 요청 검증보다 먼저 일어나므로 응모권 차감·거절 이력 저장 같은 DB 작업을 하지 않는다. 거절 이력(GD-145)에도 남기지 않는다.

- 사용자는 `X-User-ID` 헤더로 구분한다. 헤더가 없거나 UUID 형식이 아니면 제한하지 않고 보내 기존 사용자 문맥 검증이 401·400으로 처리한다.
- 한도를 세는 방식은 **고정 창**이다. 창 안에서 한도까지 허용하고, 창이 끝나면 처음부터 다시 센다. 메모리 구현은 한도를 넘은 요청의 횟수를 더 올리지 않는다(Redis 구현은 아래 "Redis 사용"을 따른다).
- 같은 사용자라도 정책이 다르면 따로 센다. 정책에 `scoped-limit`이 있으면 경로 변수(이벤트·미션) 하나당 한도를 따로 적용한다.
- 같은 `Idempotency-Key`의 재시도도 요청으로 센다.

## 설정

`api/src/main/resources/application.yaml`의 `getddo.rate-limit`에서 조정한다. 환경변수 `RATE_LIMIT_ENABLED=false`를 반영하고 프로세스를 재시작하면 전체를 끌 수 있다. 런타임 설정 자동 갱신은 제공하지 않는다. 정책이 설정에 없으면 그 API는 제한하지 않는다.

| 정책 | 대상 | 한도(창 1분) | 범위별 한도 |
| --- | --- | --- | --- |
| `entry` | E04 응모 | 사용자 20회 | 같은 이벤트 10회 |
| `attendance` | AT02 출석 | 사용자 5회 | 없음 |
| `mission-submission` | M03 미션 제출 | 사용자 20회 | 같은 미션 5회 |
| `game-result` | G04 게임 결과 제출 | 사용자 10회 | 없음 |

수치는 추천값이며 운영에서 조정한다. 시연 중 버튼 연타나 자동 테스트가 한도에 걸리면 값을 올리거나 `RATE_LIMIT_ENABLED=false`로 끈다.

## 구현 구조

- `@RateLimited(policy, scopeVariable)`를 Controller 메서드에 붙이면 `RateLimitInterceptor`가 `/api/**` 요청에서 확인한다.
- 횟수는 `RateLimiter` 인터페이스가 센다. `getddo.rate-limit.store`(환경변수 `RATE_LIMIT_STORE`)로 구현을 고른다. `memory`(기본)는 서버 메모리의 `InMemoryRateLimiter`이며 서버가 한 대인 동안만 정확하다. `redis`는 `RedisRateLimiter`이며 서버가 여러 대여도 같은 키의 횟수를 하나로 센다(GD-208).
- 제한기가 실패하면(예: Redis 장애) 요청을 막지 않고 통과시키고 경고 로그를 남긴다. 제한 때문에 응모·출석이 모두 멈추는 것보다 낫다는 판단이다.
- `Retry-After`는 CORS 노출 헤더(`getddo.cors.exposed-headers`)에 포함해 프론트 JavaScript가 읽을 수 있다.

## Redis 사용 (서버 여러 대)

`RATE_LIMIT_STORE=redis`로 켜고 접속 정보를 환경변수로 준다. 배포 서버는 Parameter Store에서 설정을 받으므로 `RATE_LIMIT_STORE`도 [Parameter Store 런북](../06-runbook/parameter-store.md)에 따라 `/getddo/dev/app/RATE_LIMIT_STORE`로 등록해야 한다(배포 스크립트의 조회 목록에는 포함돼 있다). 등록하지 않으면 배포 스크립트가 설정 누락으로 실패한다. 설정하지 않으면 메모리 구현으로 동작한다.

| 환경변수 | 기본값 | 설명 |
| --- | --- | --- |
| `RATE_LIMIT_STORE` | `memory` | `memory` 또는 `redis` |
| `SPRING_DATA_REDIS_HOST` | `localhost` | Redis 주소 |
| `SPRING_DATA_REDIS_PORT` | `6379` | Redis 포트 |
| `SPRING_DATA_REDIS_PASSWORD` | 없음 | 비밀번호. 없으면 인증하지 않는다 |
| `SPRING_DATA_REDIS_SSL_ENABLED` | `false` | TLS 접속 여부. 비밀번호를 쓰고 Redis가 같은 호스트·사설망 밖에 있으면 `true`로 켠다. 꺼 두면 비밀번호가 암호화되지 않은 연결로 전송된다 |

- 횟수 증가와 만료 설정을 Lua 스크립트 하나로 실행해 서버 여러 대의 동시 요청도 정확히 센다. 키는 `getddo:rate-limit:{정책·사용자·범위}`이고 첫 요청에서 창 길이만큼 만료를 걸어 창이 끝나면 사라진다. 창은 Redis 서버 시계로 재므로 서버 사이의 시계 차이에 영향받지 않는다.
- Redis 구현은 한도를 넘은 요청도 `INCR`로 횟수를 올린다(판정은 올린 뒤 값과 한도를 비교). 거절 요청이 많아도 키는 창이 끝나면 사라지고 값은 64비트라 쌓여서 문제가 되지 않으며, 허용·거절 판정과 `Retry-After`는 메모리 구현과 같다.
- Redis 명령 제한 시간은 300ms, 연결 제한 시간은 500ms다(`spring.data.redis.timeout`·`connect-timeout`). Redis가 느리거나 끊겨도 요청이 오래 붙잡히지 않고, 제한기 실패로 처리되어 요청을 통과시킨다(경고 로그). 이 동안에는 제한이 풀린다.
- `store=redis`인데 Redis 설정이 없으면(템플릿 빈 없음) 시작 단계에서 실패하지 않고 `localhost:6379`로 접속을 시도한다. 접속 정보를 빠뜨리면 모든 요청에서 경고 로그만 남고 제한이 풀리므로 배포 뒤 로그에서 `요청 빈도 제한기를 쓰지 못해`가 반복되는지 확인한다.
- Redis 장애가 오래 가면 서버별로 메모리 구현으로 되돌리는 것은 자동이 아니다. `RATE_LIMIT_STORE=memory`로 바꿔 재배포하거나 `RATE_LIMIT_ENABLED=false`로 끈다.

## 한계

- 메모리 구현은 서버를 재시작하면 횟수가 초기화되고 서버별로 따로 센다. 서버가 두 대 이상이면 `redis`를 쓴다.
- 사용자 식별이 `X-User-ID` 헤더라 헤더를 바꾸면 우회할 수 있다. 시연용 사용자 문맥 정책과 같은 수준이며, 실제 인증이 생기면 인증된 사용자 기준으로 바꾼다.
- 고정 창이라 창 경계에서 짧은 시간에 최대 한도의 두 배까지 허용될 수 있다.
- 만료된 키 정리는 1024번째 호출마다 그 요청 스레드에서 전체 키를 훑는다. 키가 매우 많으면 그 요청이 느려질 수 있다. 서버 한 대 시연 규모에서는 문제가 없고, 커지면 키 수 상한이나 별도 정리 스레드를 둔다.
