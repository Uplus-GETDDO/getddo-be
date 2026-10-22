# API 명세

`api` 모듈의 springdoc-openapi가 Spring MVC Controller와 요청·응답 DTO를 분석해 OpenAPI 문서를 생성합니다.

공용 요청·응답 초안은 [getddo-spec의 도메인별 API 문서](https://github.com/GETDDO/getddo-spec/tree/main/05-api)를 따릅니다. 백엔드 구현 검토는 [내부 처리 흐름](internal-flows.md), [검증 항목](verification.md), [API·DB 대응](../03-database/api-schema-mapping.md)에 기록합니다.

GD-52의 요청 헤더와 Controller 사용법은 [시연용 사용자 문맥과 내 정보 API](user-profile.md)에 정리합니다.

추첨 API 리뷰의 경로·요청·응답과 결정 사항은 [SPEC 추첨 API](https://github.com/Uplus-GETDDO/getddo-spec/blob/main/05-api/drawing.md)로 이관했습니다. 백엔드 저장·트랜잭션의 판단 근거와 남은 설계 문제는 [추첨 저장 ADR](../04-decisions/0003-drawing-snapshots-and-publications.md)에 기록합니다.

GD-55의 내부 Service 호출과 트랜잭션 규칙은 [감사 로그 기록 기능](audit-recording.md)에 정리합니다.

GD-60의 수정 제한·경품 교체와 GD-70 알림 연결은 [이벤트·경품 수정](event-modification.md)에 정리합니다.

GD-61의 논리 삭제·조회 제외와 연결 기능의 확인 사항은 [이벤트 삭제](event-deletion.md)에 정리합니다.

GD-196의 추첨 호출용 상태 변경 메서드와 트랜잭션·예외 계약은 [이벤트 내부 상태 변경](event-status-change.md)에 정리합니다.

GD-202의 취소 API와 응모권 반환·취소 안내·감사 기록 연동은 [이벤트 취소](event-cancellation.md)에 정리합니다.

GD-203의 시작·마감 상태 전환, 추첨 시각 응답과 등록·수정 감사 연동은 [이벤트 모집 상태와 감사 기록](event-lifecycle-and-audit.md)에 정리합니다.

GD-226의 관리자 목록 정렬·가중치·등록일 필터와 사용자 목록의 내 응모 여부는 [이벤트 목록 검색 확장](event-query.md)에 정리합니다.

GD-146의 요청 빈도 제한(429)과 한도 설정은 [요청 빈도 제한](rate-limit.md)에 정리합니다.

GD-156의 색깔 게임 시작 처리·저장 정보·테스트 및 연동 준비는 [게임 시작 구현](game-play-start.md)에 정리합니다.

GD-155의 활성 게임 목록·상세 공개 규칙과 조회 오류·테스트는 [게임 조회 구현](game-query.md)에 정리합니다.

GD-159·160·161의 결과 제출·최초 결과 보존·일일 지급·내 통계와 동시성 검증은 [게임 결과와 통계](game-results.md)에 정리합니다.

GD-158의 타꼬런 시작·고정tick 재현·시간 종료·400점 보상 연결은 [타꼬런 구현](game-takko-run.md)에 정리합니다.

타이밍 맞추기의 기존 G02~G05 연결·파일별 이유·검증 상태는 [타이밍 게임 구현](game-takko-timing.md)에 정리합니다.

타꼬주문의 주문 재현·18점 지급 연결·파일별 이유는 [타꼬주문 구현](game-takko-order.md)에 정리합니다.

## 접속

DB 연결 등 애플리케이션 실행 준비를 마친 뒤 `./gradlew :api:bootRun`으로 실행합니다.
기본 포트 기준 접속 주소는 다음과 같습니다.

| 문서 | 주소 |
| --- | --- |
| Swagger UI | <http://localhost:8080/swagger-ui.html> |
| OpenAPI JSON | <http://localhost:8080/v3/api-docs> |

Swagger UI 진입 주소는 `/swagger-ui/index.html`로 이동합니다.
현재 사용자·알림·관리자 이벤트·응모권 조회·출석 Controller의 API가 표시됩니다. `@CurrentUser User`를 받는 API에는 공통 사용자 헤더 입력란이 자동으로 추가됩니다.

## 자동 문서 생성

- `com.getddo.api` 아래의 Controller를 문서화하며 API 그룹은 통합 문서 하나로 시작합니다.
- `@GetMapping`, `@PostMapping`, `@RequestBody` 등 Spring MVC 선언과 DTO 타입에서 API 경로·요청·응답 구조를 추출합니다.
- `@Tag`, `@Operation`, `@Schema` 같은 Swagger 전용 어노테이션은 필수가 아닙니다.
- 응답은 기존 `ResponseEnvelope<실제 응답 DTO>`를 사용합니다. 구체적인 타입을 선언해야 `data`의 내부 구조도 문서화할 수 있습니다.
- 실행 중 결정되는 업무 오류 코드와 모든 가능한 오류 응답이 자동으로 추론되는 것은 아닙니다.

문서 제목·버전·설명은 [OpenApiConfig](../../api/src/main/java/com/getddo/api/common/config/OpenApiConfig.java),
검색 범위와 표시 순서는 [application.yaml](../../api/src/main/resources/application.yaml)에서 관리합니다.
사용자·관리자 문서 그룹은 API 경로 규칙이 정해지면 분리합니다.

## 검증

Swagger 전용 어노테이션이 없는 테스트 전용 Controller로 UI 접근과 요청·공통 응답 스키마의 자동 생성을 검증합니다.

```bash
./gradlew :api:integrationTest --tests 'com.getddo.api.common.config.OpenApiConfigTest'
./gradlew test
./gradlew build
```

라이브러리 설정은 [springdoc 공식 문서](https://springdoc.org/)를 참고합니다.

## GD-140 사용자 결과 조회 구현

E08·E09의 공용 계약은 [SPEC 추첨 API](https://github.com/Uplus-GETDDO/getddo-spec/blob/main/05-api/drawing.md)를 따른다. `UserDrawResultController`는 기존 사용자 문맥과 응답 봉투를 사용하며 `UserDrawResultService`에 조회를 위임한다. 멤버십 제한이 없는 결과 조회이므로 멤버십 헤더는 선택이다.

서비스의 읽기 전용 REPEATABLE_READ 트랜잭션에서 이벤트와 최신 `draw_publications` 버전을 함께 읽는다. 당첨자는 해당 버전의 `draw_publication_results` 연결로만 읽고, 이후 취소 기록이나 최신 내부 확정 결과로 명단을 바꾸지 않는다. 최초 버전의 공개 시각과 최신 버전의 반영 시각을 구분한다. 저장소는 이름·전화번호를 읽는 즉시 마스킹한 조회 모델로 변환하며, HTTP DTO는 공개 계약의 필드만 제공한다. 응모 여부는 ACCEPTED 응모 이력으로 판정하며 REJECTED 기록은 포함하지 않는다.

단위·MVC 검증과 기존 공개 서비스 기반 MySQL 검증은 GD-199의 최초 확인·수동 공개 서비스 합동 검증과 구분한다. GD-199가 준비되면 공개 전후 및 최초 공개 전 취소·재추첨 후 최종 명단을 실제 서비스로 함께 검증해야 한다.

GD-209의 배너 구현·FE 연결·검증 상태는 [배너 안내](banners.md)에 정리합니다.

GD-227의 이벤트·경품 이미지 업로드 API, 5MB 제한과 S3Mock 실행 방법은 [이벤트 이미지 업로드](event-images.md)에 정리합니다.

GD-142의 AR01 차단 이력 목록은 기존 사용자 문맥·ResponseEnvelope·PageResult를 재사용합니다. `AbuseCaseQueryService`가 활성 관리자 권한을 먼저 확인하고, 저장소에서 사용자 닉네임·선택 이벤트 제목을 조인합니다. 공개 계약은 [SPEC 어뷰징 API](https://github.com/Uplus-GETDDO/getddo-spec/blob/main/05-api/abuse.md), 신규 DB 전환·시드와 미실행 검증은 [차단 이력 전환 절차](../06-runbook/entry-abuse-migration.md)를 따릅니다. AR02·Redis 차단·저장 작업자는 후속입니다.
