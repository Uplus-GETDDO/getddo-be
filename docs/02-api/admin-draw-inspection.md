# GD-137 관리자 추첨 조회·검증

- 작업 브랜치: `feat/GD-137`, 최초 기반 0faf75e4. 사용자 승인 후 원격 `dev` b2be294를 merge하여 GD-87 공통 구현과 GD-168 User 변경을 반영했다. 원래 커밋 이력과 기존 응모 Entity 예외를 보존하고 GD-137 사용자 호출을 새 getter에 맞췄다. 공용 spec 확인 기준은 `main` 7076e1b다.
- 사용자 확인: AD01·AD02·AD03·AD04·AD08 경로·DTO 초안, 불일치 검증의 201/`passed=false` 이력 저장, `executionType` 대신 실제 저장 의미인 `drawType=INITIAL/REPLACEMENT` 사용을 승인했다.
- 공용 정책 원본: [추첨 규칙](https://github.com/Uplus-GETDDO/getddo-spec/blob/main/02-domain/drawing.md), [API 초안](https://github.com/Uplus-GETDDO/getddo-spec/blob/main/05-api/drawing.md). 이 문서는 백엔드 구현·미연결 범위를 설명한다.

## 구현된 경로

기본 경로 `/api/v1`, 기존 `ResponseEnvelope`·`PageResult`를 사용한다. 모든 경로는 기존 `@CurrentUser(membershipRequired=false)` 문맥으로 확인한 활성 ADMIN을 Service에서 검사한다. `X-User-ID`·`X-User-Role`은 공통 문맥 계약을 따른다. 페이지는 1부터 시작하고 기본 `page=1,size=20`, 크기 범위 1~100이다.

| ID | 메서드·경로 | 내용 |
| --- | --- | --- |
| AD01 | GET `/admin/events/{eventId}/draws` | 이벤트별 실행 차수 내림차순 페이지 |
| AD02 | GET `/admin/draws/{drawId}` | 저장 실행·조건·결과와 개별 결과의 공개 이력·취소 여부 |
| AD03 | GET `/admin/draws/{drawId}/participants` | `draw_run_candidates`에 실제 연결된 최초 후보 정보의 ID순 페이지 |
| AD04 | POST `/admin/draws/{drawId}/checks` | 본문 없음, CONFIRMED 실행 검증 후 201·검증 결과 반환 |
| AD08 | GET `/admin/draws/{drawId}/checks` | 검증 시각·ID 내림차순 이력 페이지 |

등록 이벤트에 실행이 없으면 빈 페이지다. 없는 이벤트·실행은 404, 비관리자·비활성 사용자는 403, 미확정 실행 검증은 409다. 오류 코드는 `DrawInspectionErrorCode`와 기존 `DrawingErrorCode.EVENT_NOT_FOUND`를 따른다. 잘못된 UUID·페이지는 공통 MVC 오류, 사용자 문맥 오류는 기존 공통 계약을 따른다.

## 저장 자료 검증

조회와 검증은 Service 계층의 REPEATABLE_READ 트랜잭션으로 실행·후보·결과를 읽는다. Controller·Repository 구현에는 업무 트랜잭션을 선언하지 않는다. 검증 결과만 기존 `draw_checks`에 UUID v7로 저장하며 불일치도 항목·검증자·UTC 시각과 함께 보존한다. 기존 DDL에 필요한 테이블이 있어 새 마이그레이션은 추가하지 않았다.

지원 범위는 최초 실행의 입력 버전 1, 재추첨의 입력 버전 2와 `weighted-without-replacement-v1`이다. 입력 확정·결과 확정 시각, 원본 후보·응모자·실행·이벤트 연결, 저장 응모의 등급별 수량·차감 근거·가중치, 경품 소속·등수·자리·선정 순서·후보 및 사용자 중복·후보 소진 후 미충원을 확인한다. 현재 응모 수량·경품 등수·어뷰징 상태로 저장 입력을 재계산하지 않으며 난수 선정기를 호출하지 않는다. 손상된 스냅샷 구조는 검증 불일치로 기록하고 DB 접속·저장 실패는 전파한다.

최초 검증 항목은 `SUPPORTED_INPUT`, `FIXED_INPUT`, `CANDIDATE_RELATIONS`, `CANDIDATE_EVIDENCE`, `RESULTS`다. 재추첨은 `REPLACEMENT_SOURCE`, `REPLACEMENT_SLOTS`를 추가한다. 지원하지 않는 종류·버전·알고리즘은 `SUPPORTED_INPUT=false`로 기록한다.

버전 2 조건 응답은 GD-87 저장 계약의 `originalRunId`, `targetSlots(prizeId,rank,slotNumber)`, `retainedAwards(resultId,userId,prizeId,slotNumber)`를 제공한다. 최초 버전 1은 원본 ID null과 두 빈 목록을 반환한다. GD-87 공용 실행 모델을 변경하지 않고 GD-137 조회 전용 모델과 JSON 매퍼로 읽는다. 손상되거나 누락된 재추첨 정보를 추정해 채우지 않는다.

재추첨은 최초 저장 조건·후보·응모 근거·가중치와 대조한다. 현재 실행보다 앞선 차수이며 입력 고정 시각까지 CONFIRMED인 SELECTED 결과를 조회해 역대 당첨 후보를 제외한다. 이후 취소된 당첨자도 과거 당첨자이며 다시 후보로 포함하지 않는다. 현재 취소 여부나 공개 상태를 이 조회의 필터로 사용하지 않는다. 대상 자리의 등수·범위·중복, 유지 결과 ID의 실제 사용자·경품·자리 배정, 대상과 유지 자리의 겹침을 검증한다. 저장 결과는 대상 자리의 실제 번호와 순서를 따르며 후보 소진 뒤 UNFILLED를 허용한다. 후보가 0명이어도 모든 대상 자리가 UNFILLED이면 검증 가능하다.

이 검증은 고정 조건과 저장 배정의 일치를 검사한다. 연결 취소의 모든 실제 빈 자리를 빠짐없이 반영했는지, 유지 결과 목록이 당시 유효 당첨자를 빠짐없이 포함했는지는 실제 취소 조회 계약 연결 전에는 확정하지 않는다. `passed=true`를 전체 취소·재추첨 처리 흐름의 검증 완료로 해석하지 않는다.

`wasPublished`는 해당 결과 ID가 `draw_publication_results`에 실제 포함된 이력이다. `draw_publications.source_draw_id`나 실행 확정 상태만으로 추정하지 않는다. `isCanceled`는 해당 결과에 연결된 취소 이력이며, 공개 후 취소는 두 값 모두 true가 된다. 취소 이전의 확정 결과 정합성은 취소 사실만으로 불통과시키지 않는다.

## 보류한 연결과 담당 요구사항

- [GD-169](https://ureca4.atlassian.net/browse/GD-169)은 GD-87·138·139·137의 실제 서비스 연결과 전체 흐름 통합 검증을 담당한다. GD-137의 독립 조회·재추첨 검증 본체와 단위 테스트는 이 작업에서 보완했다.
- GD-87 `DrawFailureRepository.count(UUID runId)`·`findLatest(UUID runId)`와 `DrawFailure(id,runId,failureCode,failedAt,traceId)`를 실제 공통 코드 반영 후 AD02에서 재사용해야 한다. GD-87 #53 공통 구현은 dev 병합으로 현재 브랜치에 반영됐다. 실패 요약을 AD02에 연결하는 작업은 GD-169에 남아 있으며 이 문서에서 연결 완료로 보고하지 않는다. 실패 요약을 0/null로 가장하거나 중복 실패 조회 API를 만들지 않았다.
- GD-138 취소 근거 상세 조회와 실제 연결 취소·재추첨 계약은 GD-169에서 연결해야 한다. 원래 결과·실행 ID, 취소 처리자·사유·시각은 실행 시작·재개 처리자·시각과 구분한다. 실행 행위는 공용 감사 계약으로 연결한다.
- GD-138 취소·재추첨 준비 및 GD-139 공개 서비스는 연결하지 않았다. 기존 저장 관계 조회와 테스트 대역 검증은 실제 담당 서비스 연동 결과가 아니다.

GD-87 공용 실행·실패 파일과 GD-138 취소 모델·생성 흐름은 변경하지 않았다. 실행·재개·취소·재추첨 생성·공개·마스킹 정책은 구현 범위에서 제외한다.

## 이번 보완의 검증 범위

Java 21로 core·storage:db·api 컴파일과 관련 단위·API 테스트 44건이 통과했다. 전체 `./gradlew test --offline --no-daemon`도 467건(core 294, api 151, storage:db 22) 모두 실패·오류·건너뜀 없이 통과했다. 재추첨 원본 불일치·실제 자리 번호·유지 결과 변조·역대 당첨 후보 제외·후보 부족/0명·사후 취소/공개를 검증한다. API와 JDBC Repository 테스트는 저장소/DB 호출 대역을 사용하며 실제 MySQL 통합 검증과 구분한다.

사용자 요청으로 이번 보완에서 Docker와 `integrationTest`, 전체 `build`는 실행하지 않는다. 과거 버전의 MySQL 통합·전체 build 성공 결과는 이번 버전 2 보완의 연동 검증 결과가 아니다.

## CodeRabbit 리뷰 확인

상세 조회는 결과에 연결된 사용자 ID만 조인하여 읽고 전체 후보 JSON을 조회하지 않는다. 응답 정렬은 저장된 조건의 등수·자리 순이며 현재 경품 등수 변경에 영향을 받지 않는다. 검증 전체 통과 판정은 Service에서 수행하고 영속성 구현에 전달한다. DrawCheckEntity의 필수값은 생성 시 null을 거절한다.

[재추첨 자리 검증 리뷰](https://github.com/Uplus-GETDDO/getddo-be/pull/61#discussion_r4244803111)는 유효한 위험으로 확인했다. 고정 시각까지의 실제 취소와 자리·유지 명단 완전성 검증을 사용자 확인에 따라 GD-169 담당 확인으로 남겼으며 현재 검증은 이를 보장하지 않는다. [공통 Entity 예외 리뷰](https://github.com/Uplus-GETDDO/getddo-be/pull/61#discussion_r4244803115)는 사용자 승인에 따라 ADR-0001에 DrawCheckEntity 예외를 명시했다. 기존 스키마와 서비스 검증 시각을 보존하며 created_at을 추가하지 않는다. 재추첨 자리 검증 스레드는 미해결로 유지한다.

최신 dev 병합 후 검증은 별도 결과로 기록한다. 병합 전 CodeRabbit 수정의 전체 단위 471건 성공과 병합 뒤 결과를 혼동하지 않는다. Docker 기반 통합과 전체 build는 사용자 지시에 따라 로컬에서 수행하지 않는다.

GD-137 User 접근자 수정 후 관련 테스트 실행은 dev의 `NotificationAdminService` 접근자 오류 5건으로 `core:compileJava`에서 실패했다. 테스트는 시작되지 않았고 최신 병합 버전의 통과 건수는 없다. 해당 파일은 사용자 결정에 따라 GD-72 담당 수정으로 남기며 GD-137에서 변경하지 않는다. 이전 병합 전 471건 성공을 최신 코드의 성공으로 표시하지 않는다.

## GD-314: 실행 감사 표시 계약과 공개 오류 문구

공용 원본은 [관리자 실행 감사 표시 계약](https://github.com/Uplus-GETDDO/getddo-spec/blob/main/05-api/drawing.md#실행-감사-표시-계약--gd-314)이다. 이번 백엔드 변경은 감사 저장·조회 코드와 응답 필드를 바꾸지 않는다. 프론트 표시 변경은 계약 반영 후 별도 적용이 필요하다.

현재 `DrawExecutionControlService.claim`은 `DRAW_RUN` 대상에 `DRAW_EXECUTE` 또는 `DRAW_RESUME`을 기록한다. `beforeData`에는 시작 직전 `status`, `afterData`에는 새 시도의 `attemptId`를 담는다. `afterData`는 처리 후 성공·실패 상태가 아니다. 실제 사유 `reason`과 두 문자열 필드는 서로 독립적이다. 시스템 행위자의 ID·이름은 null일 수 있다. JSON을 화면에서 숨기는 결정은 저장 이력이나 API 필드를 삭제하는 결정이 아니다.

`DrawPublicationErrorCode.NOT_CONFIRMABLE`의 `PUBLICATION-002` 메시지를 최초·재추첨 공통 문구로 정렬했다. HTTP 409·오류 코드와 기존 거절 조건은 유지한다. API 테스트에서 `publication`과 `result-confirmation` 두 경로의 코드·메시지를 함께 확인한다. 이 변경은 병합·배포 전까지 현재 배포 응답이 변경됐다는 뜻이 아니다.
