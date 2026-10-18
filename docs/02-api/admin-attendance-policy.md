# GD-219 관리자 출석 정책 조회·등록

- 작업 브랜치: `feat/GD-219`
- 공용 계약 원본: [출석 API](https://github.com/Uplus-GETDDO/getddo-spec/blob/main/05-api/attendance.md)의 AP01~AP04, 정책 규칙은 [출석 도메인 규칙](https://github.com/Uplus-GETDDO/getddo-spec/blob/main/02-domain/attendance.md)의 정책 변경. 이 문서는 백엔드 구현 범위와 판단만 설명한다.

## 구현된 경로

기본 경로 `/api/v1/admin/policies/attendance`. 모든 경로는 활성 ADMIN만 호출할 수 있고 아니면 403 `ATTENDANCE-002`다. 조회의 페이지는 1부터, 기본 `page=1,size=20`, 크기 범위 1~100이다.

| ID | 메서드·경로 | 내용 |
| --- | --- | --- |
| AP01 | GET `/daily` | 일일 출석 정책을 적용 시작 시각 최신순으로 조회 |
| AP02 | POST `/daily` | 본문 `rewardTicketCount`(1 이상). 201 `DailyAttendancePolicy` |
| AP03 | GET `/streak` | 연속 출석 정책 묶음을 적용월 최신순으로 조회. 단계는 일수 오름차순 |
| AP04 | POST `/streak` | 본문 `milestones`(`milestoneDays` 1~28, `rewardTicketCount` 1 이상). 201 `StreakPolicySet` |

오류: 필수값 누락·범위 밖·빈 배열은 400 `COMMON-002`, JSON 형식·타입 오류는 400 `COMMON-005`, 서비스 규칙 위반(단계가 오름차순이 아님·일수 중복)은 400 `ATTENDANCE-003`, 같은 적용일(일일)·적용월(연속)에 이미 예약된 정책이 있으면 409 `ATTENDANCE-004`·`ATTENDANCE-005`다.

## 저장과 적용 시점

- 일일 정책은 `reward_policies`의 `reward_type=ATTENDANCE` 행이고 등급은 `BRONZE`다. 연속 정책은 `attendance_streak_policy_sets`(적용월)와 `attendance_streak_policies`(단계)에 저장한다.
- 적용 시점은 요청 본문으로 받지 않는다. 일일 정책은 요청 시각 다음 KST 자정, 연속 정책은 요청 시각이 속한 KST 월의 다음 달 1일이다.
- 일일 정책을 등록하면 현재 열린 정책(종료 시각 없음)의 종료 시각이 새 정책의 시작 시각으로 정해진다. 이미 적용 중인 정책의 수량과 기존 보상 기록은 바꾸지 않는다.
- 사용자 출석의 정책 조회(`AttendancePolicyRepository`)는 그대로이며, 적용 기간 `[effective_from, effective_until)`과 "적용월 이하 중 가장 최근 묶음" 규칙으로 등록한 정책을 읽는다.

## 판단과 제약

- 같은 적용일·적용월의 예약 정책을 다시 고치는 방식은 공용 스펙의 "보상 수량·예약 정책 계약"에 맡겨져 있어, 이번에는 스펙 초안대로 중복 생성을 409로 거절한다. 예약을 바꾸는 API는 만들지 않았다.
- 일일 정책 등록은 열린 정책 행을 `FOR UPDATE`로 잠가 읽은 뒤 이전 정책 종료(즉시 반영)와 새 정책 저장을 같은 트랜잭션에서 한다. `reward_policies`는 종료 시각이 없는 출석 정책을 최대 하나만 허용(`open_guard` UNIQUE)해서, 종료를 먼저 반영하지 않으면 저장이 실패한다. 열린 정책이 없을 때 동시에 등록하면 UNIQUE 위반을 409로 바꿔 돌려준다.
- 연속 정책 묶음은 같은 적용월을 `UNIQUE (effective_month)`로 막는다. 먼저 존재를 확인하고, 동시 등록으로 UNIQUE에 걸리면 409로 바꿔 돌려준다.
- 정책 등록은 `AuditLogService`로 감사 기록을 남긴다(`ATTENDANCE_DAILY_POLICY_CREATE`, `ATTENDANCE_STREAK_POLICY_CREATE`). 공용 스펙에 정해진 작업 코드는 없어 이 이름은 이 구현에서 정했다.
- 일일 보상 수량의 상한은 두지 않았다. 스펙에 상한이 정해져 있지 않다.
- 첫 일일 정책(열린 정책이 없는 상태)을 등록하면 다음 KST 자정부터 적용되므로 그 전까지 출석은 `ATTENDANCE-001`(500)이 날 수 있다. 초기 정책은 운영 설정(시드)으로 미리 넣어 둔다.
- 관리자 권한은 응모·응모권 관리자 조회와 같은 방식으로 서비스에서 확인한다(활성 ADMIN).

## 검증

- 단위: `AdminAttendancePolicyServiceTest`(권한, KST 자정·월 경계의 적용 시점, 중복·동시 등록 변환, 단계 규칙, 감사 기록), `AdminAttendancePolicyControllerTest`(응답 필드·본문 검증·오류 코드).
- 작업 중 로컬 MySQL로 일일 정책의 종료·저장 순서, 중복 거절, 연속 정책 저장·조회, 감사 기록 저장을 한 번 확인했고 그 테스트는 저장소에 남기지 않았다. 통합 테스트는 이번 작업에 포함하지 않았다.
- 기존 통합 테스트 컨텍스트(`TicketIntegrationTestApplication`)가 출석 서비스를 함께 올리므로 새 서비스가 쓰는 감사 로그 빈을 그 컨텍스트에 추가했다.
