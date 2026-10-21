# GD-219·GD-270 관리자 출석 정책 조회·등록·예약 수정

- 작업 브랜치: `feat/GD-219`
- 공용 계약 원본: [출석 API](https://github.com/Uplus-GETDDO/getddo-spec/blob/main/05-api/attendance.md)의 AP01~AP06, 정책 규칙은 [출석 도메인 규칙](https://github.com/Uplus-GETDDO/getddo-spec/blob/main/02-domain/attendance.md)의 정책 변경. 이 문서는 백엔드 구현 범위와 판단만 설명한다.

## 구현된 경로

기본 경로 `/api/v1/admin/policies/attendance`. 모든 경로는 활성 ADMIN만 호출할 수 있고 아니면 403 `ATTENDANCE-002`다. 조회의 페이지는 1부터, 기본 `page=1,size=20`, 크기 범위 1~100이다.

| ID | 메서드·경로 | 내용 |
| --- | --- | --- |
| AP01 | GET `/daily` | 일일 출석 정책을 적용 시작 시각 최신순으로 조회 |
| AP02 | POST `/daily` | 본문 `rewardTicketCount`(1 이상). 201 `DailyAttendancePolicy` |
| AP03 | GET `/streak` | 연속 출석 정책 묶음을 적용월 최신순으로 조회. 단계는 일수 오름차순 |
| AP04 | POST `/streak` | 본문 `milestones`(`milestoneDays` 1~28, `rewardTicketCount` 1 이상). 201 `StreakPolicySet` |
| AP05 | PUT `/daily/{policyId}` | 아직 시작하지 않은 예약 일일 정책의 보상 수량을 바꾼다. 본문은 AP02와 같다. 200 `DailyAttendancePolicy` |
| AP06 | PUT `/streak/{policySetId}` | 아직 시작하지 않은 예약 연속 정책 묶음의 단계를 모두 바꾼다. 본문은 AP04와 같다. 200 `StreakPolicySet` |

오류: 필수값 누락·범위 밖·빈 배열은 400 `COMMON-002`, JSON 형식·타입 오류는 400 `COMMON-005`, 서비스 규칙 위반(단계가 오름차순이 아님·일수 중복)은 400 `ATTENDANCE-003`, 같은 적용일(일일)·적용월(연속)에 이미 예약된 정책이 있으면 409 `ATTENDANCE-004`·`ATTENDANCE-005`다. AP05·AP06에서 없는 정책은 404 `ATTENDANCE-006`, 이미 적용을 시작한 정책은 409 `ATTENDANCE-007`이다.

## 예약 정책 수정 (AP05·AP06)

- 수정할 수 있는 것은 아직 시작하지 않은 예약 정책뿐이다. 일일 정책은 적용 시작 시각(`effective_from`) 정각부터, 연속 정책은 적용월 1일(KST)부터 시작한 것으로 보고 수정을 거절한다(409 `ATTENDANCE-007`). 지금 적용 중인 정책과 지난 정책은 절대 바꾸지 않는다.
- 바뀌는 값은 일일 정책의 보상 수량, 연속 정책의 단계 전체(기존 단계를 모두 지우고 요청한 단계로 대체)다. 적용 시점(시작·종료 시각, 적용월)은 바꾸지 않는다. 단계 규칙(일수 1~28, 오름차순, 중복 없음, 수량 1 이상)은 등록과 같다.
- 정책 행(연속은 묶음 행)을 `FOR UPDATE`로 잠가 읽은 뒤 시작 여부를 다시 확인하므로, 수정끼리 겹쳐도 직렬로 처리되고 시작 시각 이후에는 거절된다. 서비스가 수정과 감사 기록을 같은 트랜잭션에서 한다.
- 연속 정책의 단계는 삭제 뒤 같은 트랜잭션에서 다시 저장한다. `(policy_set_id, milestone_days)` UNIQUE와 부딪히지 않도록 삭제를 먼저 DB에 반영한다.
- 수정은 감사 기록을 남긴다(`ATTENDANCE_DAILY_POLICY_UPDATE`, `ATTENDANCE_STREAK_POLICY_UPDATE`). 수정 전·후 보상 수량(일일) 또는 단계 목록(연속)을 담는다.
- 예약 취소(삭제)와 적용 시점 변경은 제공하지 않는다.
- **적용 시작 순간의 직렬화**: 출석 기록(`AttendanceRecorder`)은 읽은 정책 행을 `FOR SHARE`로 잡는다(`findDailyPolicyForShare`, `findStreakPolicySetForShare`, `findStreakPolicySetByIdForShare`). 수정 트랜잭션이 적용 시작 시각 전에 시작해 시작 이후에 커밋되더라도, 그 사이에 들어온 출석은 수정이 끝날 때까지 기다린 뒤 수정된 수량으로 지급한다. 반대로 수정은 진행 중인 출석 처리가 끝날 때까지 기다린다. 연속 정책 단계는 묶음 행의 잠금을 얻은 뒤에 읽어 삭제 뒤 재저장 중인 단계를 지급 근거로 저장하다 외래 키 오류가 나는 일을 막는다. 공유 잠금끼리는 서로 기다리지 않으므로 동시 출석은 서로 막지 않고, 관리자 정책 등록·수정이 있을 때만 잠깐 기다린다. 읽기만 하는 현황 조회(`AttendanceQueryService`)는 잠금 없는 조회를 그대로 쓴다.

## 저장과 적용 시점

- 일일 정책은 `reward_policies`의 `reward_type=ATTENDANCE` 행이고 등급은 `BRONZE`다. 연속 정책은 `attendance_streak_policy_sets`(적용월)와 `attendance_streak_policies`(단계)에 저장한다.
- 적용 시점은 요청 본문으로 받지 않는다. 일일 정책은 요청 시각 다음 KST 자정, 연속 정책은 요청 시각이 속한 KST 월의 다음 달 1일이다.
- 일일 정책을 등록하면 현재 열린 정책(종료 시각 없음)의 종료 시각이 새 정책의 시작 시각으로 정해진다. 이미 적용 중인 정책의 수량과 기존 보상 기록은 바꾸지 않는다.
- 사용자 출석의 정책 조회(`AttendancePolicyRepository`)는 그대로이며, 적용 기간 `[effective_from, effective_until)`과 "적용월 이하 중 가장 최근 묶음" 규칙으로 등록한 정책을 읽는다.

## 판단과 제약

- 같은 적용일·적용월에 예약 정책이 있을 때 새로 등록하는 것(AP02·AP04)은 스펙 초안대로 409로 거절한다. 예약을 바꾸는 방법은 위 AP05·AP06(GD-270)으로 제공한다.
- 일일 정책 등록은 열린 정책 행을 `FOR UPDATE`로 잠가 읽은 뒤 이전 정책 종료(즉시 반영)와 새 정책 저장을 같은 트랜잭션에서 한다. `reward_policies`는 종료 시각이 없는 출석 정책을 최대 하나만 허용(`open_guard` UNIQUE)해서, 종료를 먼저 반영하지 않으면 저장이 실패한다. 열린 정책이 없을 때 동시에 등록하면 UNIQUE 위반을 409로 바꿔 돌려준다.
- 연속 정책 묶음은 같은 적용월을 `UNIQUE (effective_month)`로 막는다. 먼저 존재를 확인하고, 동시 등록으로 UNIQUE에 걸리면 409로 바꿔 돌려준다.
- 정책 등록은 `AuditLogService`로 감사 기록을 남긴다(`ATTENDANCE_DAILY_POLICY_CREATE`, `ATTENDANCE_STREAK_POLICY_CREATE`). 공용 스펙에 정해진 작업 코드는 없어 이 이름은 이 구현에서 정했다.
- 일일 보상 수량의 상한은 두지 않았다. 스펙에 상한이 정해져 있지 않다.
- 첫 일일 정책(열린 정책이 없는 상태)을 등록하면 다음 KST 자정부터 적용되므로 그 전까지 출석은 `ATTENDANCE-001`(500)이 날 수 있다. 초기 정책은 운영 설정(시드)으로 미리 넣어 둔다.
- 관리자 권한은 응모·응모권 관리자 조회와 같은 방식으로 서비스에서 확인한다(활성 ADMIN).

## 검증

- 단위: `AdminAttendancePolicyServiceTest`(권한, KST 자정·월 경계의 적용 시점, 중복·동시 등록 변환, 단계 규칙, 감사 기록, 예약 정책 수정의 시작 여부·오류 코드), `AdminAttendancePolicyControllerTest`(응답 필드·본문 검증·오류 코드, AP05·AP06 경로).
- 통합: `AttendancePolicyAdminRepositoryIntegrationTest`가 일일 정책의 수량만 바뀌는지와 연속 정책 단계를 같은 일수를 포함해 통째로 바꿔도 UNIQUE에 걸리지 않는지를 실제 MySQL로 확인한다. `AttendancePolicyEditConcurrencyTest`는 수정 트랜잭션이 정책 행을 잡고 있는 동안 들어온 출석이 잠금 대기 뒤 수정된 수량(일일)·단계(연속)로 지급하는지를 확인한다(일일 정책은 `FOR SHARE`를 빼면 실패함을 확인했다. 연속 정책은 기존 재시도 로직이 최종 값을 맞춰 주므로 잠금을 빼도 통과하며, 잠금은 외래 키 오류와 재시도를 미리 막는다). 정책 수정끼리의 직렬화는 별도 동시 실행 테스트로 확인하지 않았다.
- 작업 중 로컬 MySQL로 일일 정책의 종료·저장 순서, 중복 거절, 연속 정책 저장·조회, 감사 기록 저장을 한 번 확인했고 그 테스트는 저장소에 남기지 않았다. 등록·조회(GD-219) 작업에는 통합 테스트를 포함하지 않았고, 예약 정책 수정(GD-270)의 통합 테스트는 위 항목이다. GD-270 통합 테스트는 해당 클래스만 로컬에서 실행했고 전체 `./gradlew build`는 CI로 확인한다.
- 기존 통합 테스트 컨텍스트(`TicketIntegrationTestApplication`)가 출석 서비스를 함께 올리므로 새 서비스가 쓰는 감사 로그 빈을 그 컨텍스트에 추가했다.
