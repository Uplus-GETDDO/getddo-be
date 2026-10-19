# GD-231 관리자 일별 집계

- 작업 브랜치: `feat/GD-231`
- 배경: 관리자 대시보드가 집계 API 없이 목록 API를 `size=1`로 호출해 `totalElements`를 세고 있어 한 화면에 요청이 30개를 넘는다. 프론트 요청 R1. 응모권 사용 이력(AO05)으로는 응모권을 쓰지 않는 이벤트(`NO_TICKET`)의 응모를 셀 수 없다.
- 공용 계약: [관리자 일별 집계 API(AS01)](https://github.com/Uplus-GETDDO/getddo-spec/blob/main/05-api/admin-stats.md). 경로·응답 구조·오류 코드는 담당자 확인 전 상태로 공용 스펙에 올라 있고, 이 문서는 백엔드 구현 세부만 설명한다.
- 경로와 필드 이름은 프론트 요청 문서의 제안을 따랐다. 요청 문서는 "한 API로 묶기 어려우면 응모·응모권·차단 집계로 나눠도 된다"고 했지만, 팀 합의(2026-10-18)로 한 API로 구현한다. 기능 담당과 협업 경계의 응모·응모권·출석·미션·게임·어뷰징 집계를 한 곳에서 읽는다.

## 경로

`GET /api/v1/admin/stats/daily?from=2026-10-11&to=2026-10-17`

활성 ADMIN만 호출할 수 있고 아니면 403 `STATS-002`다.

| 파라미터 | 설명 |
| --- | --- |
| `from` | KST 시작 날짜(포함), `YYYY-MM-DD`. 필수 |
| `to` | KST 끝 날짜(포함), `YYYY-MM-DD`. 필수. 시작 날짜와 같거나 이후이고 양끝을 포함해 최대 31일 |

응답은 공통 성공 봉투(`success`, `data`) 안에 담긴다. 예시는 하루짜리 요청이다.

```json
{
  "success": true,
  "data": {
    "days": [
      {
        "date": "2026-10-11",
        "entrantCount": 4,
        "entryCount": 6,
        "activeUserCount": 9,
        "grantedTickets": { "MISSION": 3, "ATTENDANCE": 10, "GAME": 0 },
        "usedTickets": 8,
        "blockedCount": 2
      }
    ],
    "totalUserCount": 120
  }
}
```

## 필드 정의

| 필드 | 정의 | 원본 |
| --- | --- | --- |
| `entrantCount` | 그날 응모한 사용자 수(중복 제거). 응모권을 쓰지 않는 이벤트의 응모도 포함 | `event_entries.created_at` |
| `entryCount` | 그날 접수된 응모 건수 | `event_entries.created_at` |
| `activeUserCount` | 그날 응모·출석·미션 제출·게임 시작 중 하나 이상을 한 **일반 사용자(`USER`)** 수(중복 제거). 관리자 계정의 활동은 제외 | `event_entries`, `attendances.attendance_date`, `mission_submissions`, `game_plays` |
| `grantedTickets` | 그날 지급된 응모권 장수를 지급 근거(`ATTENDANCE`·`MISSION`·`GAME`)별로 나눈 값. 세 키를 항상 담는다. 등급은 구분하지 않는다 | `tickets.created_at`, 지급 근거 컬럼 |
| `usedTickets` | 그날 응모에 사용된 응모권 장수. 반환·정정은 빼지 않는 총량이다 | `ticket_histories` `USE` |
| `blockedCount` | 그날 발생한 어뷰징 탐지 기록 수 | `abuse_cases.occurred_at` |
| `totalUserCount` | 조회 시점의 활성 일반 사용자 수. 참여율의 분모이며 날짜별 값이 아니다 | `users` `USER`·`ACTIVE` |

## 규칙

- 날짜는 KST 업무일이다. 시각 컬럼은 UTC로 저장돼 있어 +9시간으로 환산해 묶는다(KST는 서머타임이 없다). 출석은 이미 KST 날짜인 `attendance_date`를 그대로 쓴다.
- `days`는 기간의 모든 날짜를 오름차순으로 담고 활동이 없는 날은 0으로 채운다.
- 모든 집계는 같은 반복 가능한 읽기 스냅샷에서 구해 서로 맞게 한다.
- 오류는 조건마다 코드 하나다.
  - 활성 관리자가 아님: 403 `STATS-002`
  - `from` 또는 `to`가 없음: 400 `COMMON-002`
  - 날짜 형식이 `YYYY-MM-DD`가 아님: 400 `COMMON-005`
  - `to`가 `from`보다 앞섬, 또는 양끝을 포함한 기간이 31일을 넘음(`to - from`이 31일 이상. `from`과 `to`가 같은 하루는 허용): 400 `STATS-001`

## 판단과 제약

요청 문서가 정하지 않았거나 모호해서 아래처럼 정했다. 팀이 다르게 정하면 바꿔야 한다.

- **`activeUserCount`의 "참여"**: 요청 문서는 "적립까지 넣을지 정하지 않았다"고 했다. 이번 구현은 적립을 넣지 않고 응모, 출석, 미션 제출, 게임 시작만 센다. 미션은 완료하지 못한 제출도, 게임은 완료하지 못한 시작도 참여로 본다.
- **`blockedCount`**: 요청 문서는 "지급 전에 차단된 기록 수"라고 했다. 이 저장소에는 지급 전 차단을 따로 저장하는 테이블이 없고 어뷰징 탐지 테이블 `abuse_cases`만 있다. 그래서 탐지 대상 행위가 발생한 시각(`occurred_at`) 기준 탐지 기록 수를 센다. 검토 상태(`PENDING`·`ALLOWED`·`CONFIRMED`)는 구분하지 않는다. 어뷰징 도메인의 구현 코드는 아직 없고 저장 구조만 있어, 구현 방식이 정해지면 정의를 다시 맞춰야 한다.
- **`usedTickets`**: 반환을 뺀 순사용이 아니라 반환 전 총량이다. 응모권 사용 이력 중 `USE`만 센다.
- **`grantedTickets`**: 출석은 한 번의 보상으로 여러 장이 지급되므로 청구 건수가 아니라 `tickets` 행(장수)을 센다. 등급은 합산한다.
- **`entrantCount`·`entryCount`·`activeUserCount`의 응모**: 거절된 응모(GD-145)도 `event_entries`에 저장되므로 접수 완료(`ACCEPTED`)만 센다. 거절된 응모만 한 사용자는 참여한 것으로 보지 않는다.
- **`totalUserCount`**: 날짜 범위와 무관한 현재 값이다. 가입일 기준 누적이 필요하면 별도 정의가 필요하다.
- **모집단**: `activeUserCount`는 참여율의 분자로 쓰이므로 분모(`totalUserCount`)와 맞게 일반 사용자(`USER`)만 센다. 관리자 계정의 활동은 제외한다. 미션 제출 등은 관리자 계정이 호출해도 막지 않기 때문이다(공용 스펙 미션 API). 계정 상태(`ACTIVE`·`INACTIVE`)는 거르지 않는다. 그날 실제로 참여한 사용자가 이후 비활성화돼도 과거 날짜의 숫자가 바뀌지 않게 하기 위해서이며, 비활성화된 사용자가 많으면 `activeUserCount / totalUserCount`가 실제보다 조금 크게 나올 수 있다. `entrantCount`는 관리자가 응모할 수 없으므로(`ENTRY-002`) 별도로 거르지 않는다.
- 집계는 여섯 테이블을 `created_at` 등 시각 범위로 읽는다. 데이터가 많아지면 `EXPLAIN`으로 확인하고, 필요하면 시각 인덱스를 새 마이그레이션으로 추가한다. 성능은 측정하지 않았다.
- 읽기 전용이라 감사 기록 대상이 아니다.

## 검증

- 단위: `DailyStatsServiceTest`(권한, 기간 검증과 경계, 0으로 채운 날짜와 지급 근거 키), `AdminStatsControllerTest`(응답 구조, 누락·형식 오류 코드, 오류 코드 매핑).
- 실제 MySQL: `DailyStatsIntegrationTest`(KST 자정 직전·직후 데이터가 서로 다른 날짜로 집계됨, 기간 밖 제외, 거절된 응모 제외, 같은 날 같은 사용자의 응모·출석·미션·게임을 한 번만 셈, 지급 근거별 구분, 사용 이력과 어뷰징 기록의 날짜별 건수, 비활성 사용자를 `totalUserCount`에서 제외).
