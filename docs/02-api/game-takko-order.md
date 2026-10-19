# 타꼬 주문 구현

- 상태: 검토 대기. 사용자 단위 테스트 통과, CI 통합 검증·배포 전.
- 기준: BE `ca405c3a`, spec `5f06725a`, FE 모델 `cfb35a2b`.
- 공용 정책: [주문 연동 계약](https://github.com/Uplus-GETDDO/getddo-spec/blob/main/05-api/game-takko-order-draft.md), [게임 규칙](https://github.com/Uplus-GETDDO/getddo-spec/blob/main/02-domain/game.md). 사용자 선택을 반영한 공용 명세는 `c2c7036`으로 spec main에 공유했다.

## 구현 범위와 이유

사용자가 “현재 FE 규칙 유지,18점부터 하루1장”을 선택했다. 기존 G01~G05·통계·일일 청구·응모권 지급을 재사용하며 주문 전용 Controller·테이블·스케줄러를 추가하지 않는다.

| 파일 | 이 코드가 필요한 이유 |
| --- | --- |
| `core/.../game/domain/TakkoOrderRules` | 시작 당시 목숨·주문 길이의 저장 설정을 검증한다. 손상 설정을 사용자 기록의 INVALID와 구분한다. 최소 보상18점은 v1 상수다. |
| `TakkoOrderMenu` | FE와 같은 메뉴 인덱스 순서를 고정한다. 순서는 난수 결과와 해시에 영향을 주므로 변경 시 규칙 버전을 바꿔야 한다. |
| `TakkoOrderGenerator` | xorshift32의 흐름을 주문마다 이어 사용하고, 같은 메뉴 세 개 연속은 난수를 추가 소비해 다시 뽑는다. 각 주문에서 연속 제한을 새로 시작한다. |
| `TakkoOrderRoundInput`, `TakkoOrderSubmission` | 주문 번호·선택 배열을 불변 값으로 복사한다. 첫 오답 이후 선택이 없는 원본 기록을 보존한다. |
| `core/.../game/service/TakkoOrderPlayStartHandler` | 기존 상세·시작 Handler 목록에 주문 코드·버전을 연결한다. |
| `TakkoOrderValidator` | 저장된 seed·규칙으로 주문을 재현한다. 성공이면 길이만큼 점수와 다음 길이+1, 실패면 목숨-1·길이 유지, 세 번째 실패가 마지막일 때만 점수를 확정한다. |
| `GameResultSubmission` | 주문 번호·선택 개수·각 메뉴를 해시해 주문 경계를 보존한다. 같은 메뉴를 다른 주문에 옮겨도 동일 재시도로 오인하지 않는다. |
| `GamePlayResultRecorder` | 게임 종류와 입력 모델을 검사하고18점부터 기존 청구·지급 서비스를 호출한다. 트랜잭션·잠금 순서는 기존 게임과 동일하다. |
| `api/.../game/dto/request/GameResultRequest` | rounds의 JSON 형식·메뉴·개수만 검증한다. 정답·종료·미완료 여부는 core에서 판정한다. |
| `api/.../game/dto/response/TakkoOrderRulesResponse` | 초안과 같은 initialLives·startLength·maxLength 세 필드만 공개한다. gameData는 seed·generatorVersion이며 tickMs는 추가하지 않는다. |

점수·통계·보상은 기존 game_plays·user_game_stats·game_reward_claims·응모권 테이블을 사용한다. 새 DB 스키마·Flyway 변경은 없다. 타이밍·주문의 제출 모델을 기존 색깔·타꼬런에 보내도 형식 오류로 처리한다.

## 정책 대조

| 적용 항목 | 코드·검증 상태 |
| --- | --- |
| 현재 FE 규칙·메뉴 순서·3~8 길이·목숨3·주문 길이 점수 | Rules·Generator·Validator 작성, 고정 입력·길이 상한 테스트 작성 |
| 원본 기록·첫 오답 즉시 종료·세 번째 실패로 자연 종료 | Validator의 부분 일치·추가 선택·종료 검사, INVALID 테스트 작성 |
|18점·게임별 KST 하루1장·동일 결과 재시도 | Recorder와 기존 청구·지급 경로, 보상 경계 단위 및 MySQL 통합 테스트 작성 |
| 기존1MiB·최대10000 주문·형식/무효 구분 | 기존 BodyAdvice와 rounds parser, 한도·혼합 입력 테스트 작성 |
| 기존 색깔·타꼬런 호환 | 기존 해시 계산 유지, 기존 게임에 신규 입력 제출 거절 테스트 추가 |
| API/core/storage 책임·core 클래스·DTO record | 기존 모듈 의존 유지, 응답 DTO만 record·Builder |
| FE·공유 DB·실제 배포 | 변경하지 않음. 담당자 연결·병합·배포 후 최초 설정 필요 |

## 작성한 검증과 실행 상태

- `TakkoOrderPlayStartHandlerTest`: 지원 버전·저장 JSON 검증·내부 정보 은닉.
- `TakkoOrderValidatorTest`: seed1 고정18점,0점·길이 유지/8 상한·미완료·첫 오답 이후 선택·종료 이후 기록·불연속.
- `TakkoOrderResultRequestTest`: 키 순서와 주문 경계 해시·불변성·메뉴/형식·입력 혼합·10000/10001 한도.
- `TakkoOrderResponseTest`: 공개 규칙·gameData 필드.
- `GamePlayResultRecorderTest`: 타이밍50/주문18 경계의 기존 지급 연결, 기존 게임에 신규 모델 거절.
- `TakkoOrderApiIntegrationTest`: MySQL의 상세·시작·18점·중복 지급 방지·같은 결과 재시도·변경409·INVALID.

사용자가 단위 테스트를 실행해 성공을 알렸고, 결과 XML에서 core35개·API32개 총67개 통과(실패·오류·건너뜀0건)를 확인했다. 에이전트는 추가 실행하지 않았다. 통합 테스트·전체 빌드는 CI에서 수행한다. 고정 예제는 FE 연산식으로 만든 참조 데이터로, Java/TS 교차 실행 통과를 주장하지 않는다.

```bash
# getddo-be 저장소 루트에서 실행
./gradlew :core:test --tests '*TakkoOrder*' --tests '*GamePlayResultRecorderTest'
./gradlew :api:test --tests '*TakkoOrder*'
# CI의 Docker/Testcontainers 환경
./gradlew :api:integrationTest --tests '*TakkoOrderApiIntegrationTest'
```

## FE 전달·남은 작업

FE는 실제 DB UUID로 G03을 호출하고 그 seed로 첫 주문부터 생성해야 한다. 손님 얼굴 등 꾸밈 난수와 분리하고 실패 후에도 난수 흐름을 이어 간다. 주문별 picks는 성공 전체 또는 첫 오답까지 모으며 세 번째 실패에 G04로 제출한다. 서버 확정 점수·보상만 표시하고 기존 재시도·이탈·관리자 체험 표시를 재사용한다.

주문 표시 시간·실제 사람의 기억·조작 여부는 이번 기록만으로 검증하지 않는다. FE 자동 정지 추가는 기존 초안의 권장 후속으로 남긴다. BE 변경으로 FE 구현 완료를 주장하지 않는다.

후속은 CI·리뷰, 병합·배포, 게임과 유효 GAME 보상 정책 최초 등록, FE 실제 연결 확인이다. 아직 공유 DB에 주문 게임을 등록하지 않았다.

## PR #136 리뷰 반영 — 2026-10-19

타이밍·주문·타꼬런의 시작 스냅샷 검사를 기존 GamePlay.hasVerificationContext(ruleVersion, generatorVersion)로 모았다. 게임별 버전·오류 메시지·seed 범위는 유지했다. 주문 sequence의 int 범위 초과, 저장 규칙·생성 버전·seed 손상, 주문 길이 초과와 공통 검사 경계 테스트를 추가했다. 영구 문서의 명령은 저장소 루트 기준으로 수정했다.

기존0ddd5f0e의 CI 빌드·테스트는 성공했다. 이 리뷰 수정에서 추가·변경한 테스트는 사용자 지시에 따라 로컬 실행하지 않았고 새 CI 결과로 확인해야 한다. 앞의67개는 최초 구현의 사용자 실행 결과이며 이번 수정의 통과 건수가 아니다.
