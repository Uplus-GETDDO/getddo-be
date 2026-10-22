# GD-180 관리자 응모권 조회

- 작업 브랜치: `feat/GD-180`
- 공용 계약 원본: [응모권 API](https://github.com/Uplus-GETDDO/getddo-spec/blob/main/05-api/ticket.md)의 관리자 응모권 조회. 이 문서는 백엔드 구현 범위와 판단만 설명한다.
- 계약 근거: 프론트 GD-152(송동현) 화면이 쓰는 2026-10-12 백엔드 답변. 관리자 조회는 읽기 전용이라 감사 기록 대상이 아니다.

## 구현된 경로

기본 경로 `/api/v1`, `ResponseEnvelope`·`PageResult`를 사용한다. 모든 경로는 `@CurrentUser(membershipRequired=false)` 문맥으로 확인한 활성 ADMIN만 호출할 수 있고, 아니면 403 `TICKET-010`이다. 페이지는 1부터, 기본 `page=1,size=20`, 크기 범위 1~100이다. 기간은 `[from, to)`이며 시각은 `Z`나 오프셋을 포함해야 한다. 시작이 끝보다 앞서지 않으면 400 `TICKET-004`다.

| ID | 메서드·경로 | 내용 |
| --- | --- | --- |
| AO03 | GET `/admin/ticket-grants` | 지급 이력(`GRANT`) 최신순. 응모권 한 장이 한 행. `userId,keyword,missionId,sourceType,grade,from,to` |
| AO04 | GET `/admin/users/{userId}/tickets/consistency` | 한 사용자의 현재 상태와 마지막 이력의 정합성. 없는 사용자는 404 `TICKET-011` |
| AO05 | GET `/admin/ticket-histories` | 처리 이력 최신순. `userId,eventId,missionId,operationType,from,to` |
| AO08 | GET `/admin/ticket-refunds` | 반환 이력(`REFUND`) 최신순. `eventId,userId,from,to` |
| AO09 | GET `/admin/tickets/inconsistencies` | 전체 사용자의 어긋난 응모권. `userId` 선택 |

## 판단과 제약

- AO04 응답은 확인한 사용자의 현재 이름 `userName`을 담는다(ADR-028). 요청 시점에 읽고 저장하지 않는다. AO03·AO05·AO08·AO09의 이름은 이 작업 범위가 아니다.
- 사용자 이름, 이벤트·미션 제목, 지급 근거는 다른 도메인이 소유한 테이블이다. Entity를 만들지 않고 `AdminTicketQueryRepositoryImpl`의 읽기 전용 네이티브 SQL JOIN으로 읽는다. 값은 모두 파라미터로 바인딩하고 `keyword`의 `%`·`_`는 문자 그대로 검색한다.
- AO03의 `sourceId`는 출석 기록(`ATTENDANCE`)·미션 제출(`MISSION`)·게임 플레이(`GAME`) ID다. `missionId`를 주면 미션 지급만 나오고 `sourceType`과 함께 모순되면 빈 결과다.
- AO05·AO08의 이벤트는 사용 이력이 가리키는 응모에서, 반환 이력은 되돌린 원본 사용 이력의 응모에서 얻는다. AO08의 `reasonType`은 반환이 이벤트 취소로만 일어나므로 `EVENT_CANCELED`로 고정이다. 반환 처리 상태·재시도 횟수는 노출하지 않는다.
- "어긋남"은 응모권의 현재 `version`·`status`가 마지막(가장 큰 `ticket_version`) 이력과 다르거나 이력이 하나도 없는 경우다. 이력이 없으면 `latestHistoryVersion=0`, `latestHistoryStatus=null`이다. AO04의 `availableCount`와 목록은 REPEATABLE_READ 한 트랜잭션에서 구해 같은 기준 시점이다.
- AO09는 요청 때마다 `tickets` 전체를 마지막 이력과 비교한다. 마지막 이력은 `(ticket_id, ticket_version)` 유일 키로 찾는다. 대량 데이터에서의 성능은 측정하지 않았다. 운영 데이터가 커지면 `EXPLAIN`으로 확인하고 필요하면 `updated_at` 기반 증분 확인이나 배치로 바꾼다.
- 페이지 총 건수는 같은 조건의 `count(*)`를 먼저 세고 범위를 넘으면 빈 페이지로 응답한다. 정렬은 AO03·AO05·AO08이 `createdAt DESC, id DESC`, AO09가 `updatedAt DESC, id DESC`다.

## 검증

- 단위: `AdminTicketQueryServiceTest`(권한·기간·정합성 판정), `AdminTicketControllerTest`(응답 필드·파라미터·400/403/404).
- 실제 MySQL: `AdminTicketQueryRepositoryIntegrationTest`, `AdminTicketHistoryRefundIntegrationTest`, `AdminTicketInconsistencyIntegrationTest`(저장소 SQL), `AdminTicketApiIntegrationTest`(공통 문맥·예외 변환 연결).
