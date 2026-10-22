# GD-142·GD-143 관리자 차단 이력 조회 구현

- 상태: 검토 대기 — Codex 작성, 담당자 확인 전
- 범위: AR01 목록과 AR02 상세의 백엔드 구현. 배포·실제 자동 차단 생산 완료를 의미하지 않는다.
- 공용 계약 원본: [어뷰징 조회 API](https://github.com/Uplus-GETDDO/getddo-spec/blob/main/05-api/abuse.md)

## 재사용과 상세 조회

GD-143은 GD-142의 `AdminAbuseCaseController`·`AbuseCaseQueryService`·`AbuseCaseRepository`를 확장한다. `AbuseCaseEntity`·MapStruct 목록 변환·사용자/이벤트 조인·자동 차단 조회 조건·사용자 문맥·응답 봉투를 재사용한다. 신규 테이블·마이그레이션이나 Redis 읽기는 추가하지 않는다. V030 적용 전제와 복구 절차는 [스키마 전환 런북](../06-runbook/entry-abuse-migration.md)을 따른다.

`GET /api/v1/admin/abuse-cases/{caseId}`는 기존 사용자 문맥 검증 후 Service에서 활성 관리자 권한을 먼저 확인한다. 권한을 통과한 경우에만 상세 저장소를 호출한다. 대상 부재는 `ABUSE-002`, 권한 거절은 `ABUSE-001`에 연결하며 메시지·상태와 USER/COMMON 오류 정의는 공용 계약을 따른다. 경로 UUID 해석 실패는 기존 MVC 오류 처리기를 사용한다. DB 조회 실패를 대상 부재로 치환하지 않는다.

상세 저장소는 목록과 같은 ENTRY 자동 차단 조건을 사용하고 사용자 표시명·선택 이벤트 제목을 한 조회에서 함께 읽는다. 만료 조건은 추가하지 않으며, 이벤트가 없는 행도 left join으로 보존한다. 상세 조회 모델은 기존 `AbuseCaseSummary`와 `AbuseEvidence`를 합성한다. HTTP DTO는 목록의 변환을 재사용하면서 공용 계약대로 목록 필드 10개와 evidence를 같은 깊이에 제공한다.

## 저장 근거의 변환 경계

`AbuseEvidenceMapper`는 DB JSON에서 확정된 일곱 필드만 추출해 타입이 정해진 도메인 모델로 복원한다. 추가 JSON 속성은 DTO에 옮기지 않는다. 문자열을 숫자로 강제 변환하지 않고 정수·int 범위·필수 필드·집계 구간과 임계값 도달 관측값을 확인한다. ruleVersion·USER/EVENT 대표 범위·시각·관측값·당시 설정은 저장값 그대로 반환한다. 현재 운영 설정으로 보충하거나 대표 범위를 다시 선택하지 않는다.

필수 근거가 없거나 손상되면 빈 evidence 또는 404로 대체하지 않고 기존 공통 내부 오류 경계로 전달한다. JSON 파서 예외의 원문 스니펫이 공통 로그에 남지 않게 변환 오류에는 원인 예외와 원문 JSON을 연결하지 않는다. 목록 조회는 근거를 파싱하지 않으므로 상세 근거 복원 실패 때문에 목록을 막지 않는다.

## 검증 범위

- DB 없는 Service·standalone MVC·저장소·근거 변환 단위 테스트로 권한 선확인, 상세 부재, 공통 오류, 응답 허용 필드와 저장값 보존을 검증한다.
- 실제 사용자 문맥·V030 MySQL·Repository·MapStruct·Service·MVC를 연결하는 `AdminAbuseCaseIntegrationTest`를 추가한다. 선택 이벤트·현재 표시값 조인·저장 근거·정밀도·목록 회귀·권한·대상 제외·손상 근거를 검증하는 코드다.
- 사용자 지시로 로컬 통합 테스트는 실행하지 않는다. 테스트 소스 컴파일과 실제 DB 실행 결과를 구분해 보고한다.
- GD-144 자동 차단 생성과 GD-291 이력 저장·재시도·배치는 후속이다. 조회 검증은 해당 생산 경로의 완료를 대신하지 않는다.

## 로컬 검증 결과 — 2026-10-22

- 어뷰징 단위 테스트 62건 통과. 전체 단위 테스트 1,645건 통과(실패·오류·건너뜀 0).
- 아래 명령으로 통합 테스트 실행을 제외한 전체 빌드와 API·DB 모듈의 통합 테스트 소스 컴파일에 성공했다. Java 21로 실행했다.

```bash
./gradlew test build :api:compileIntegrationTestJava :storage:db:compileIntegrationTestJava \
  -x :api:integrationTest -x :storage:db:integrationTest --no-daemon --max-workers=1 --no-parallel
```

통합 테스트 소스 7개 시나리오는 작성·컴파일했지만 실행하지 않았다. 실제 MySQL·HTTP 연동, 시드 주입과 배포 실측은 이번 로컬 검증 결과에 포함하지 않는다.
