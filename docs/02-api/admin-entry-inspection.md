# GD-218 관리자 응모 내역·이벤트 응모자 조회

- 작업 브랜치: `feat/GD-218`
- 공용 계약 원본: [응모 API](https://github.com/Uplus-GETDDO/getddo-spec/blob/main/05-api/entry.md)의 AO06·AO07. 이 문서는 백엔드 구현 범위와 판단만 설명한다.
- 읽기 전용이라 감사 기록 대상이 아니다.

## 구현된 경로

기본 경로 `/api/v1`, `ResponseEnvelope`·`PageResult`를 사용한다. 두 경로 모두 `@CurrentUser(membershipRequired=false)` 문맥으로 확인한 활성 ADMIN만 호출할 수 있고, 아니면 403 `ENTRY-010`이다. 페이지는 1부터, 기본 `page=1,size=20`, 크기 범위 1~100이다.

| ID | 메서드·경로 | 내용 |
| --- | --- | --- |
| AO06 | GET `/admin/entries` | 모든 사용자의 접수·거절 응모 내역 최신순. `userId,eventId,status,from,to` |
| AO07 | GET `/admin/events/{eventId}/participants` | 이벤트의 응모자 최초 응모 시각 최신순. 없는 이벤트는 404 `ENTRY-004` |

## 판단과 제약

- AO06 응답은 내 응모 이력(E06)의 응모 결과 필드에 `userId`와 `participantId`를 더한 것이다. 거절 건은 응모자가 없어 `participantId`가 `null`이다.
- AO06·AO07 응답은 `userId`와 함께 사용자의 현재 이름 `userName`을 담는다(ADR-028). 이름은 요청 시점에 `UserNameRepository`로 한 번에 읽고(N+1 없음), 저장하지 않으며 표시용이다. 사용자 행이 없으면 `null`이다.
- 기간은 결과 기록 시각(`created_at`) 기준 `[from, to)`이고 시각은 `Z`나 오프셋을 포함해야 한다. 시작이 끝보다 앞서지 않으면 400 `COMMON-002`이다(E06과 같다).
- 정렬은 AO06이 `created_at DESC, id DESC`, AO07이 `created_at DESC, id DESC`다. 페이지 시작 위치가 int 범위를 넘으면 조회하지 않고 빈 목록과 전체 건수를 돌려준다.
- AO06 쿼리는 E06의 SQL을 사용자 조건만 선택으로 바꿔 함께 쓴다. 이벤트 제목은 이벤트 도메인 테이블을 읽기 전용 JOIN으로 가져온다.
- AO07은 삭제된 이벤트의 응모자도 보여 준다(E05가 삭제된 이벤트의 내 이력을 보여 주는 것과 같다). 없는 이벤트만 404다.
- AO07 응답에는 추첨 제외 상태·사유·시각을 넣지 않는다(공용 스펙의 제외 결정 제거 사항).
- 인덱스는 추가하지 않았다. 로컬 MySQL 8.4에서 `event_entries` 10만 행으로 확인한 결과, 사용자 조건이 없는 AO06은 `created_at`으로 시작하는 인덱스가 없어 전체 행을 정렬한다(첫 페이지 조회 약 40ms, 한 번 잰 값). 관리자 화면 호출 빈도와 현재 데이터 크기에서는 문제가 없다고 보고, 행이 훨씬 늘어나면 `(created_at, id)` 인덱스를 새 마이그레이션으로 추가한다. AO07은 `uq_event_participants_1`의 `event_id`로 찾은 뒤 이벤트 안에서만 정렬한다.

## 검증

- 단위: `AdminEntryQueryServiceTest`(권한·이벤트 확인·저장소 위임·이름 결합), `AdminEntryControllerTest`(응답 필드·파라미터·400/403/404).
- 실제 MySQL: `AdminEntryNamesIntegrationTest`(`userName`이 `users.name`과 같고 이름을 바꾸면 다음 조회에 반영).
- 실제 MySQL 통합 테스트는 이번 작업에 포함하지 않았다. 저장소 SQL(필터 조합·기간 경계·정렬·페이지·응모자 조회)은 작업 중 로컬 MySQL로 한 번 확인했지만 저장소에는 남기지 않았다.
