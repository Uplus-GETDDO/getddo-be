# 타꼬 타이밍 맞추기 구현

- 상태: 검토 대기. 사용자 단위 테스트 통과, CI 통합 검증·배포 전.
- 기준: BE `ca405c3a`, spec `5f06725a`, FE 모델 `cfb35a2b`. 2026-10-18 원격 상태를 확인했다.
- 공용 계약: [타이밍 연동 계약](https://github.com/Uplus-GETDDO/getddo-spec/blob/main/05-api/game-takko-timing-draft.md), [게임 도메인](https://github.com/Uplus-GETDDO/getddo-spec/blob/main/02-domain/game.md). 사용자 선택을 반영한 공용 명세는 `c2c7036`으로 spec main에 공유했다.

## 기존 흐름에 연결한 이유

사용자가 기존 게임과 같은 방식으로 처리하고 점수만 타이밍 기준으로 맞추도록 요청했다. 새로운 Controller·통계 테이블·지급 서비스를 만들지 않고 G01~G05와 기존 트랜잭션을 사용한다. 타이밍 게임의 구간 생성·입력 해석·점수 판정만 추가했다. 기존 색깔 게임과 타꼬런의 입력 해시는 유지했다.

| 위치 | 책임과 이유 |
| --- | --- |
| `core/.../game/domain/TakkoTimingRules` | 저장된 v1 규칙을 검증한다. 필수 정수·값 불일치·중복 키·후행 JSON을 기본값으로 보정하지 않는다. 저장 JSON 원문은 오류에 노출하지 않는다. |
| `TakkoTimingRoundGenerator`, `TakkoTimingRound` | seed와 성공 횟수로 구간·이동 시간을 재현한다. 같은 난수 소비·연산 순서를 유지하며 꽂은 정수 ms로 점수를 계산한다. DB를 조회하지 않는다. |
| `TakkoTimingTap`, `TakkoTimingSubmission` | 한 판의 번호와 시간 배열을 불변 값으로 보관한다. API JSON이나 JPA Entity를 core 판정 입력으로 사용하지 않는다. |
| `GameResultSubmission` | 타이밍 전용 버전 접두어와 번호·시간·배열 순서로 해시한다. JSON 공백·키 순서는 무시하되 입력 변경은 최초 결과와 충돌하게 한다. |
| `core/.../game/service/TakkoTimingPlayStartHandler` | 기존 G02/G03의 Handler 목록에 코드·버전·공개 규칙을 연결한다. |
| `TakkoTimingValidator` | 저장한 버전·규칙·seed로 결과를 재현한다. 첫 실패가 마지막 기록일 때만 점수를 반환하고, 미완료·불연속·음수·중간 실패는 null로 반환한다. |
| `GamePlayResultRecorder` | 저장된 게임 종류와 입력 모델을 맞추고 판정기를 호출한다. 유효 점수의 기존 통계·보상 청구·응모권 지급을 동일 트랜잭션에서 처리한다. |
| `api/.../game/dto/request/GameResultRequest` | taps의 JSON 형식과 개수만 검사한다. 업무 규칙의 성공·실패는 판정기에 맡긴다. |
| `api/.../game/dto/response/TakkoTimingRulesResponse`, `GameRulesResponse` | 공개 규칙 세 필드만 응답한다. 기존 G03 gameData는 seed·generatorVersion을 그대로 재사용하며 tickMs는 타꼬런에만 둔다. |

## 저장·보상·예외

기존 `games`의 `TAKKO_TIMING` 설정을 G03에서 `game_plays`로 스냅샷한다. G04 결과 확정 시 플레이 → 사용자·게임별 통계 → 일일 청구 → 응모권 순서로 잠금·저장한다. 타이밍용 테이블이나 스키마 변경은 없다.

정확한 점수·종료·입력 상한의 정책 원본은 spec이다. 구현에서는 일반10·퍼펙트20·보상50 상수를 사용한다. 0점·미달 점수도 통계에 포함하고 무효는 제외한다. 같은 날짜·게임 보상은 한 번이고 게임끼리는 구분한다. 재시도는 최초 결과를 반환하며 202·429·소유권 은닉·ADMIN 체험 제한은 기존 흐름을 따른다.

형식 오류는400 COMMON-005, 개수 또는 기존 Advice의1MiB 초과는413 GAME-008이다. 형식상 정상인 규칙 위반은200 INVALID이며 결과를 확정한다. 저장 규칙 손상은500으로 롤백하며 INVALID로 바꾸지 않는다.

## 테스트 작성과 실행 상태

다음 검증 코드를 작성했다. 사용자가 단위 테스트를 실행해 성공을 알렸고, 결과 XML에서 core35개·API32개 총67개 통과(실패·오류·건너뜀0건)를 확인했다. 에이전트는 추가 실행하지 않았다. 통합 테스트·전체 빌드는 CI 대기다. 고정 예제는 같은 연산식으로 생성한 참조 데이터이며 FE/Java 교차 실행 통과의 증거가 아니다.

| 테스트 | 검증할 동작 |
| --- | --- |
| `TakkoTimingPlayStartHandlerTest` | 지원 버전·손상 규칙·내부 JSON 은닉 |
| `TakkoTimingValidatorTest` | seed1의0/40/50/60점·반사 이동, 미완료·중간 실패·불연속·음수, 설정 손상 |
| `TakkoTimingRoundTest` | 포함 경계·경계 바로 바깥, 반대편 구간·난이도 하한 |
| `TakkoTimingResultRequestTest` | 정규화 해시·입력 불변성·형식 오류·혼합 입력·10000/10001 한도 |
| `TakkoTimingResponseTest` | 공개 필드·tickMs 제외 |
| `GamePlayResultRecorderTest` | 새 판정기 의존성과 기존 회귀 시나리오 |
| `TakkoTimingApiIntegrationTest` | 실제 MySQL의 시작·저장·통계·50점 지급·원본 재시도·변경409·동일 날짜 중복 지급·INVALID |

사용자가 작업 폴더에서 실행할 명령:

```bash
# getddo-be 저장소 루트에서 실행
./gradlew :core:test --tests '*TakkoTiming*' --tests '*GamePlayResultRecorderTest'
./gradlew :api:test --tests '*TakkoTiming*'
```

통합 검증은 Docker/Testcontainers가 필요한 기존 CI에서 `:api:integrationTest --tests '*TakkoTimingApiIntegrationTest'`로 수행한다. 전체 `test`·`build` 성공은 아직 확인하지 않았다. 정적 diff·링크 검사는 실행 결과와 구분해 기록한다.

## FE 전달과 후속

FE 소스는 수정하지 않았다. 담당자에게 다음 변경을 전달해야 한다.

1. 목록의 실제 DB UUID와 `TAKKO_TIMING`으로 G02/G03 호출. 임시 game-timing ID를 서버 UUID로 보내지 않는다.
2. Math.random 대신 시작 seed의 xorshift32를 사용하고, 구간당 한 번만 난수를 소비한다.
3. 꽂은 순간의 활성 시간을 Math.floor로 내림하고 **같은 정수 ms로 화면 판정과 제출**을 한다. 마지막 렌더링 프레임 위치를 판정값으로 사용하지 않는다.
4. 첫 실패까지 `taps:[{sequence,elapsedMs}]`를 수집해 기존 G04에 제출한다. 저장 확정 전 점수는 확인 중으로 표시하고 재시도에는 원본 입력을 유지한다.
5. spec의 고정 예제로 양측 결과를 확인한다. seed 기반 기록 재현이 사람의 실제 플레이를 증명하지는 않는다.

남은 순서는 CI·리뷰 → 병합·배포 → 공유 DB의 게임·유효 GAME 보상 정책 최초 등록 → FE 실제 연결 확인이다. 현재 공유 DB에 타이밍을 등록하지 않았다. 타꼬주문은 이후 별도 답변으로18점 기준을 채택했으며 [주문 구현](game-takko-order.md)을 따른다.

## PR #136 리뷰 반영 — 2026-10-19

타이밍·주문·타꼬런의 시작 스냅샷 검사를 기존 GamePlay.hasVerificationContext(ruleVersion, generatorVersion)로 모았다. 게임별 버전·오류 메시지·seed 범위는 유지했다. 주문 sequence의 int 범위 초과, 저장 규칙·생성 버전·seed 손상, 주문 길이 초과와 공통 검사 경계 테스트를 추가했다. 영구 문서의 명령은 저장소 루트 기준으로 수정했다.

기존0ddd5f0e의 CI 빌드·테스트는 성공했다. 이 리뷰 수정에서 추가·변경한 테스트는 사용자 지시에 따라 로컬 실행하지 않았고 새 CI 결과로 확인해야 한다. 앞의67개는 최초 구현의 사용자 실행 결과이며 이번 수정의 통과 건수가 아니다.

## 재검토 추가3건 반영 — 2026-10-19

CodeRabbit 재검토가 늦게 도착해 저장 규칙 해석 중복과 타이밍·타꼬런 검증기 호출 테스트 누락을 추가로 확인했다. StoredRulesSnapshot(package-private)에 타이밍·주문·타꼬런의 엄격한 JSON 객체·정수 해석과 원문을 숨긴 설정 오류 변환을 모았다. 게임별 공개 필드·버전 상수·점수·오류 메시지는 유지한다. null 저장 설정도 원문 없는 IllegalStateException으로 구분한다.

타이밍·타꼬런 검증기 진입점에서 규칙/생성 버전과 seed0·-1·상한 초과를 거절하는 테스트를 보강했고 주문의 미지원 규칙 버전도 추가했다. 기존 파서의 중복 키·후행 JSON·소수/필수 필드·정보 은닉 검증은 유지한다. 이번 수정의 테스트는 로컬에서 실행하지 않았으며 새 CI와 CodeRabbit 재검토로 확인한다.
