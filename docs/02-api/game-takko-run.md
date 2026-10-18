# 타꼬런 구현 — GD-158

공용 기준은 [타꼬런 계약](https://github.com/Uplus-GETDDO/getddo-spec/blob/main/05-api/game-takko-run-draft.md)이다. 2026-10-16 사용자가 FE 초안에 BE를 맞추도록 선택했고, 추가 질문으로 활성10분 정상종료·서버 경과시간 대조 후속을 선택했다. 공용 계약은 spec main의 bda7722에 게시했다.

## 현재 코드와 이전 안

이전 60Hz·LCG·TAKKORUN·totalTicks/action은 미게시 로컬 구현안이다. 이번에는 FE의 TAKKO_RUN·120Hz·xorshift32·endTick/type으로 변경했다. 규칙/생성 버전은 최초 공개용 takko-run-v1/takko-run-generator-v1이다. 이미 배포된 60Hz 판을 호환한다고 설명하지 않는다. 이전 코드·fixture와 검증 기록은 별도로 보존했다.

공통 Controller·DB 테이블·Flyway·지급 서비스는 추가하지 않았다. 기존 게임 시작/상세 처리기와 결과·통계·일일 청구·TicketGrantService 흐름을 재사용한다. 원래 GD-158에 squash로 포함했던 선행 #102는 이미 dev에 병합됐다. 현재 변경분은 dev88833cef 기준으로 타꼬런에 필요한 차이만 분리했으며 기존 작업 폴더는 보존했다.

## 구현 경계와 이유

1. TakkoRunPlayStartHandler는 code/ruleVersion과 저장 rules를 검사한다. TakkoRunRules는 minimumRewardScore=400·maxTicks=72000을 정확한 정수로 검증한다. 누락·손상은 기본값으로 보정하지 않는다.
2. GamePlayDataResponse는 타꼬런에만 tickMs=1000.0/120을 제공한다. 색깔 게임은 null 필드를 직렬화에서 제외해 기존 generatorVersion·seed 두 필드만 유지한다. 실제 물리는 DT=1.0/120이고 반올림한8.333을 사용하지 않는다.
3. GameResultRequest는 FE의 endTick·inputs[].tick/type을 읽는다. press/release는 내부 TakkoRunAction enum으로 변환한다. 이전 totalTicks/action 또는 색깔 answers와 혼합한 형식을 거절한다.
4. TakkoRunSubmission의 endTick은0부터 시작하는 마지막 번호다. Validator는0~endTick을 포함해 endTick+1번 진행한다. 음수·72000 이상·입력 역전·범위 밖 입력은 재생 전 INVALID다.
5. TakkoRunReplay는 매 요청마다 새로 만들고 색깔 게임과 동일한 xorshift32 비트 연산을 사용한다. 기존 FE의 장애물 크기·풀·난수 소비·물리 순서를 버전 기준으로 고정한다. 같은tick 입력 배열을 정렬하거나 중복 제거하지 않는다.
6. 충돌은 제출 마지막tick에서 처음 발생해야 한다. 충돌 없이71999까지 진행한 판도 정상 시간 종료다. 상한은 CPU 재생량을 제한하고 FE도 같은 시점에 종료해야 한다.
7. GameResultSubmission은 종류와 새 endTick/type 의미를 해시 접두어에 넣고 종료 번호·입력 순서를 고정 길이 바이트로 해시한다. 색깔 게임의 기존 해시는 유지한다.
8. 결과 저장기는 저장된 ruleVersion으로 판정기를 선택하고, 유효 결과에 기존 통계 잠금·일일 청구·지급 트랜잭션을 적용한다. 형식 불일치는 게임 전용 Advice로 기존400 COMMON-005에 연결한다.

## 유지한 동작

- 첫 입력은tick0 press, 정지·탭 이동·카운트다운은 활성tick에서 제외하고 정지 때 키해제 효과를 다음 활성tick에 보존한다.
- 본문1MiB·입력1만개 상한과413 GAME-008, 400형식 오류와200 INVALID 구분, 소유권·역할·404/409/202/429 의미는 공용 계약을 유지한다.
- 타꼬런400점·색깔50점 기준과 KST 완료일·하루1장·재시도 원본 결과는 기존 저장·지급 흐름을 사용한다.
- 서버 경과시간과 활성시간의 허용 오차는 미확정이므로 새 차단 조건을 넣지 않았다.
- 실제 games 행·보상 정책·배포 상태는 이번에 조회/수정하지 않았다. 저장 rules 변경이 필요한 기존 행이 있는지 운영 반영 전 확인한다. 과거 Flyway를 수정하지 않는다.

## 검증 소스와 실제 실행 상태

사용자가 최신 dev 정리본의 단위 테스트 성공을 알렸고, 결과 XML에서1,241개(core837·api354·storage:db50), 실패·오류·스킵0을 확인했다. 에이전트는 테스트를 실행하지 않았다. 통합 테스트와 전체 빌드는 사용자가 CI에서 확인하기로 했다. 이전 전체2257개 성공은 변경 전60Hz·LCG 구현의 결과이며 새 계약 성공으로 재사용하지 않는다.

- Validator: seed1/123456789의 첫 장애물 충돌은3.5px/tick 및 고정폭을 이용한 독립 산식 기대값을 사용한다. 계산 기대값과 실행 성공은 구분한다.
- 종료 경계: 물리를 대역으로 분리해71999까지 정확히72000회 진행하고 상한 초과는 재생 전 거절하는지 검사한다.
- DTO: FE 입력·같은tick 순서·이전 형식 거절·정확한tickMs·색깔 JSON두필드 호환성을 검사한다.
- 통합: 실제 MySQL의 G02/G03/G04/G05·형식·권한·저득점·일일 보상·재시도를 검사한다. 보상용 입력은 테스트 실행 중 제한된seed/점프 전략으로 준비한다. 이를 독립적인 FE/Java 교차 검증으로 설명하지 않는다.
- 과거 .events는 기존 작업 폴더의 보존 자료이며 새120Hz 테스트에서 읽지 않는다. 이번 기능 커밋에는 포함하지 않는다. 새 FE/Java 공통 입력·점수 대조는 별도 후속이다.

사용자 실행 명령(저장소 루트에서 실행):

```bash
./gradlew :core:test --tests '*TakkoRun*' --tests '*GamePlayResultRecorderTest'
./gradlew :api:test --tests '*GameResultRequestTest' --tests '*TakkoRunResultRequestTest' --tests '*TakkoRunResponseTest' --tests '*GamePlayControllerTest'
./gradlew :api:integrationTest --tests '*TakkoRunApiIntegrationTest' --tests '*GameResultApiIntegrationTest' --tests '*GamePlayApiIntegrationTest'
./gradlew test build --init-script gradle/test-limits.init.gradle --max-workers=1 --no-parallel
```

DB 통합 테스트에는 기존 Testcontainers/MySQL·Docker 환경이 필요하다. 로컬 단위 테스트 통과와 PR의 CI·실제 FE 연결·운영 검증은 구분한다.

## PR #113 리뷰 반영 — 2026-10-17

색깔 게임의 최소 보상 점수를 규칙 상수로 참조하고 보상 기준·사유를 지원 버전별로 명시했다. 저장 규칙의 오류 변환은 TakkoRunRules.fromStoredSnapshot으로 모아 시작·결과가 같은 처리를 사용한다. 원문 JSON을 예외 원인에 남기지 않는 기존 방식을 유지하고 회귀 테스트를 추가했다. 검증 명령은 저장소 루트 기준으로 수정했으며 gradle/test-limits.init.gradle은 기존 추적 파일이다.

기존 head d694f10c의 CI 빌드·테스트·이미지 빌드는 성공했다. 이번 리뷰 수정 후 로컬 테스트·컴파일·빌드는 실행하지 않았으며 새 커밋의 CI 결과를 별도로 확인한다.
