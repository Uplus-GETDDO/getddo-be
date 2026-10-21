# 이벤트 목록 검색·당첨 예정 인원·내 응모 여부

- 관련 작업: [GD-226](https://ureca4.atlassian.net/browse/GD-226), [GD-251](https://ureca4.atlassian.net/browse/GD-251)
- 기준: 2026-10-17 R2·R7 승인 및 2026-10-20 당첨 예정 인원 합계 요청
- 공용 계약: [이벤트 API](https://github.com/Uplus-GETDDO/getddo-spec/blob/main/05-api/event.md)

## R2 관리자 검색

`GET /api/v1/admin/events`의 기존 조건과 권한·페이지 봉투를 유지하고 다음 조건을 추가한다.

| 조건 | 값 | 생략 시 |
| --- | --- | --- |
| `sort` | `createdAt`, `startsAt`, `endsAt`, `title` 중 하나 + `,asc` 또는 `,desc` | `createdAt,desc` |
| `weightingEnabled` | `true` / `false` | 가중치로 제한하지 않음 |
| `createdFrom` | KST `YYYY-MM-DD`, 등록일 하한 | 하한 없음 |
| `createdTo` | KST `YYYY-MM-DD`, 등록일 상한 | 상한 없음 |

등록일은 시작 날짜 00:00 이상·종료 날짜 다음 날 00:00 미만이며 같은 날짜도 검색할 수 있다. 두 날짜는 모두 포함한다. 서비스가 UTC 경계를 계산하며 `created_at`에 적용한다. 기존 `from`·`to`는 모집 기간과의 겹침 조건으로 유지하고 모든 조건은 AND로 적용한다. `weightingEnabled=false`는 필터 생략과 다르다.

정렬은 페이지를 자르기 전에 DB에서 적용한다. 정렬값이 같으면 ID도 같은 방향으로 정렬해 순서를 고정한다. 허용한 정렬만 SQL에 매핑하며 요청 문자열을 SQL에 직접 붙이지 않는다. 목록과 전체 건수는 같은 필터를 사용한다.

예:

```text
/api/v1/admin/events?sort=startsAt,asc&weightingEnabled=true&createdFrom=2026-10-01&createdTo=2026-10-17
```

위 조건은 모집 날짜와 무관하게 10월 1~17일에 등록한 가중치 이벤트를 모집 시작 시각 오름차순으로 조회한다. 모집 날짜도 제한하려면 기존 `from`·`to`를 함께 보낸다.

숫자·boolean·날짜의 타입/형식 오류는 기존 `400 COMMON-005` 처리다. 잘못된 정렬, 역전된 날짜 범위, DB 지원 범위를 벗어난 UTC 경계는 `400 EVENT-008`이다. 빈 날짜 입력과 확장 연도를 허용하지 않는다.

## R7 사용자 목록

`GET /api/v1/events`의 각 `EventSummary`에 `myEntered:boolean`을 추가한다. 관리자 목록·상세와 사용자 상세에는 이번 필드를 추가하지 않는다.

- 현재 일반 사용자의 해당 이벤트 정상 참여 기록이 있으면 `true`다. 응모권 미사용 이벤트도 포함한다.
- 응모하지 않았거나 업무 거절 이력만 있으면 `false`다. 다른 사용자의 참여는 영향을 주지 않는다.
- `CANCELED` 등 이벤트 상태가 바뀌어도 이미 성공한 참여 기록을 이 필드로 취소하거나 제거하지 않는다. 이 값은 추가 응모 가능 여부가 아니며 자격은 기존 E07에서 확인한다.
- 관리자 계정이 사용자 목록을 조회하면 `false`다. 기존 로그인/사용자 문맥 필수 정책은 유지하고 비로그인 공개 조회를 새로 열지 않는다.

서비스는 목록에 포함된 이벤트 ID를 모아 `event_participants`를 한 번 더 조회한다. 응모자 기록은 정상 접수 트랜잭션에서 생성되므로 거절 이력과 구분할 수 있다. `used_ticket_count > 0`으로 판정하지 않는다. 건수·목록·참여 여부는 같은 읽기 트랜잭션에서 조회하며 빈 페이지와 관리자 조회에는 참여 조회를 실행하지 않는다.

## 제외 범위와 연동

R4는 기존 `eventType=NO_TICKET`을 기본 이벤트, `TICKET`을 겟또타임으로 표시하면 된다. 별도 `isTimeRaffle`·분류 컬럼은 추가하지 않는다. 응모·차감·반환 로직과 저장 스키마도 변경하지 않는다.

R2·R4·R7은 공용 이벤트 계약에 반영됐다. 배포·FE 연결 완료는 소스 구현과 별개로 확인한다.

## GD-251 사용자 목록의 당첨 예정 인원 합계

E01 `GET /api/v1/events`의 각 `EventSummary`에 `winnerCount:long`을 추가한다. 경품에 설정한 당첨 예정 인원 합계이며 실제 추첨 결과·응모자 수·당첨 확률이 아니다. 1등 1명·2등 3명이면 `4`다. 경품이 없는 과거 데이터는 `0`이며 등록·수정의 경품 필수 정책은 변경하지 않는다. 복수 경품 합계가 32비트 정수 범위를 넘을 수 있어 64비트 합계를 사용한다.

사용자 목록의 요청 파라미터와 기본 정렬(`createdAt DESC`, 같은 시각이면 `id DESC`), 필터 및 페이지 형식은 유지한다. 인원순 정렬은 이번 범위에서 제외한다. 관리자 정렬도 변경하지 않는다.

합계는 `event_prizes.event_id`를 기준으로 같은 목록 SELECT의 상관 집계에서 읽는다. 경품 상세를 전부 가져오거나 이벤트별 상세 API·추가 쿼리를 실행하지 않는다. 건수 쿼리는 집계·경품 조인 없이 기존 필터만 사용해 중복 집계를 피한다. 테이블·컬럼·Flyway 변경은 없다. 사용자 상세와 관리자 조회에는 기존 경품별 인원을 제공하며 최상위 합계 필드는 추가하지 않는다.

FE 요청 예: `/api/v1/events?eventType=NO_TICKET&page=1&size=12`. `data.items[].winnerCount`로 카드의 인원을 표시한다. `myEntered`, 공개 상태 및 이미지 URL 처리도 유지한다. `tags`·`isRecommended`는 사용자 결정으로 범위에서 제외했다.
