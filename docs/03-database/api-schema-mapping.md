# API·DB 대응과 스키마 검토

- 상태: 검토 대기 — 담당자 확인 전
- 기준: `getddo-be` 원격 `dev`의 현재 SQL

공용 API 초안과 초기 스키마의 대응을 정리한다. 공용 요구사항과 도메인 정책은 `getddo-spec`을 따른다.

2026-10-08 GD-93에서 V001~V011을 최신 [DBML](schema.dbml)의 44개 테이블로 교체했다. 아래 V001~V011 테이블 대응과 검토 항목은 교체 전 SQL의 기록이며 현재 스키마의 기준으로 사용하지 않는다. 단 V008 행(`tickets`, `ticket_histories`)과 D03·D04·D09·D11은 현행 기준으로 갱신했다. V014 이후 행은 교체 뒤에 추가한 마이그레이션이다. API·서비스 코드의 호환성 수정 및 대응표 재검토는 담당자의 후속 작업이다.

2026-10-05 추첨 ERD 검토안은 이 문서의 V007 초기 정의와 구분한다. 갱신한 공용 계약은 [SPEC 추첨 API](https://github.com/Uplus-GETDDO/getddo-spec/blob/main/05-api/drawing.md)의 AD01~AD09이며, 후보 연결·결과 FK·실패·검증·공개 이력 설계는 [ADR-0003](../04-decisions/0003-drawing-snapshots-and-publications.md)과 ERD SQL 초안 (`drawing-erd-reviewed-erdcloud.sql`, 정리로 삭제됨; 작성 당시 내용은 Git 이력 참조)을 참고한다. 아래 매핑을 새 설계의 운영 반영 완료로 해석하지 않는다.

## 테이블 대응

아래 매핑은 SQL 정적 분석 결과다. 실제 DB 적용·마이그레이션 실행·업무 트랜잭션 동작을 검증했다는 뜻이 아니다. 테이블마다 CRUD API를 만들지 않고 업무 단위로 묶었다.

| 마이그레이션 | 테이블 | API·내부 처리 |
| --- | --- | --- |
| [V001](../../storage/db/src/main/resources/db/migration/user/V001__create_user_tables.sql) | `users` | 헤더 문맥, U01, AO01~AO02, 응모 자격 |
| [V002](../../storage/db/src/main/resources/db/migration/reward/V002__create_reward_tables.sql) | `reward_policies` | AP01~AP02, AM03, 게임 보상 정책 적용 |
| [V003](../../storage/db/src/main/resources/db/migration/mission/V003__create_mission_tables.sql) | `missions`, `mission_submissions`, `mission_reward_claims`, `mission_quiz_questions`, `quiz_question_options`, `quiz_answers`, `quiz_question_progress`, `survey_questions`, `survey_question_options`, `survey_answers` | M01~M04, AM01~AM03 |
| [V004](../../storage/db/src/main/resources/db/migration/attendance/V004__create_attendance_tables.sql) | `attendances`, `attendance_streaks`, `attendance_streak_policy_sets`, `attendance_streak_policies`, `attendance_reward_claims` | AT01~AT03, AP01~AP04 |
| [V005](../../storage/db/src/main/resources/db/migration/game/V005__create_game_tables.sql) | `games`, `game_plays`, `user_game_stats`, `game_reward_claims` | G01~G05, AG01~AG03, AR07 |
| [V006](../../storage/db/src/main/resources/db/migration/event/V006__create_event_tables.sql) | `events`, `event_prizes`, `event_participants`, `event_entries`, `banners` | E01~E09, B01, AE01~AE08, AB01~AB05, AO06~AO07 |
| [V007](../../storage/db/src/main/resources/db/migration/drawing/V007__create_drawing_tables.sql) | `draw_runs`, `draw_candidates`, `draw_results`, `current_awards`, `award_cancellations`, `publications` | E08~E09, AD01~AD07, 최초 자동 추첨·발표 |
| [V008](../../storage/db/src/main/resources/db/migration/ticket/V008__create_ticket_tables.sql) | `tickets`, `ticket_histories` | T01~T02, AR04~AR05, AO03~AO05·AO08, 지급·차감·반환·만료. 응모권 한 장이 `tickets` 한 행이고 상태 변화는 `ticket_histories`에 추가로만 남긴다 |
| [V009](../../storage/db/src/main/resources/db/migration/abuse/V009__create_abuse_tables.sql) | `abuse_cases` | AR01~AR07, 탐지 기록 |
| [V010](../../storage/db/src/main/resources/db/migration/notification/V010__create_notification_tables.sql) | `notification_jobs`, `notifications` | N01~N03, AN01~AN05, 비동기 생성·모의 발송 |
| [V011](../../storage/db/src/main/resources/db/migration/audit/V011__create_audit_tables.sql) | `audit_logs` | 내부 감사 기록 유지. AU01~AU02 공통 조회는 제외하며 업무별 기록 범위는 담당 계약을 따름 |
| [V014](../../storage/db/src/main/resources/db/migration/notification/V014__add_notification_worker_indexes.sql) | 알림 생성·발송 대기 및 선점 만료 조회용 인덱스 | GD-68 비동기 알림 처리 기반. 신규 공개 API는 추가하지 않음 |
| [V016](../../storage/db/src/main/resources/db/migration/drawing/V016__add_initial_draw_terminal_states.sql) | 최초 추첨의 응모자 없음·과거 전원 제외 상태와 입력 CHECK | GD-130 이후 새 전원 제외 실행은 생성하지 않으며 과거 조회는 유지. 공개 API는 추가하지 않음 |
| [V017](../../storage/db/src/main/resources/db/migration/event/V017__add_event_list_index.sql) | `events`의 `(deleted_at, created_at, id)` 인덱스 | 사용자·관리자 공용 이벤트 목록 조회(삭제되지 않은 행, 최신순). 공개 API는 추가하지 않음 |
| [V018](../../storage/db/src/main/resources/db/migration/ticket/V018__add_ticket_usable_lookup_index.sql) | `tickets`의 `(user_id, grade, expires_at)` 인덱스 | GD-133 응모권 차감 서비스의 사용 가능 응모권 조회(사용자·등급·만료 시각 조건, 만료 이른 순). 반환은 응모권 ID로 조회하므로 쓰지 않음. 공개 API는 추가하지 않음 |
| [V019](../../storage/db/src/main/resources/db/migration/drawing/V019__add_draw_execution_control.sql) | `draw_runs`의 실행 제어 필드(시도 ID, 소유권 만료, 재시도 시각 등)와 조회 인덱스 | GD-87 공통 추첨 실행·실패·재시도·중단 복구. 공개 API는 추가하지 않음 |
| [V021](../../storage/db/src/main/resources/db/migration/notification/V021__add_notification_retry_baselines.sql) | `notification_jobs`·`notifications`의 수동 복구 기준 차수 | GD-72 관리자 알림 복구. 누적 선점 차수는 유지하고 복구 이후의 자동 재시도 횟수만 새로 센다([ADR-0005](../04-decisions/0005-notification-manual-retry-fencing.md)) |
| [V022](../../storage/db/src/main/resources/db/migration/drawing/V022__add_award_cancellation_request_key.sql) | `award_cancellations.request_key`와 UNIQUE | GD-138 개별 당첨 취소 요청의 멱등 키. 과거 이력은 NULL |
| [V023](../../storage/db/src/main/resources/db/migration/ticket/V023__add_ticket_expiry_scan_index.sql) | `tickets`의 `(status, expires_at)` 인덱스 | GD-135 응모권 만료 워커가 만료 시각이 지난 사용 가능·반환됨 응모권을 찾는 조회(`FOR UPDATE SKIP LOCKED`). 공개 API는 추가하지 않음 |
| [V025](../../storage/db/src/main/resources/db/migration/mission/V025__add_mission_submission_request_hash.sql) | `mission_submissions.request_hash` | GD-175 미션 제출(M03)의 같은 제출 ID 재전송 본문 비교. 처음 요청의 SHA-256을 보존한다. 이 컬럼을 기록하기 전의 제출은 빈 문자열이다. 공개 API는 추가하지 않음 |
| [V027](../../storage/db/src/main/resources/db/migration/event/V027__add_event_lifecycle_due_indexes.sql) | `events`의 상태·삭제 여부·시작/마감 기한 인덱스 두 개 | GD-203 모집 상태 전환의 상태별 due 후보 조회. 새 테이블·컬럼·공개 API는 추가하지 않음 |
| [V028](../../storage/db/src/main/resources/db/migration/event/V028__link_banner_events_and_catalog_lock.sql) | `banners.event_id`, `banner_catalog_lock` | GD-209 이벤트 연결·빈 목록 쓰기 잠금. 기존 URL·행·CHECK·UNIQUE 보존. 실제 DB 적용은 미실행 |
| [V029](../../storage/db/src/main/resources/db/migration/ticket/V029__add_platinum_ticket_grade.sql) | `tickets.grade`·`reward_policies.reward_ticket_type` ENUM에 `PLATINUM`, `event_entries.requested_platinum_count`와 등급별 요청 장수 CHECK | GD-228 플래티넘 응모권 등급(ADR-025). 기존 행·기존 등급의 비음수 검증 의미·UNIQUE 보존. `chk_entry_requested_grades`는 플래티넘을 포함해 재정의하고 기존 응모의 플래티넘 요청 장수는 0. GD-228의 실제 DB 검증 결과는 해당 PR #126의 기록을 별도로 확인하며, GD-230의 통합 테스트는 미완료 |

## 응모 API 현행 매핑 (GD-134, GD-145, GD-200)

위 표는 교체 전 SQL의 기록이다. 아래는 현재 스키마에서 응모 API(E03·E04·E05·E06·E07)가 실제로 읽고 쓰는 테이블이다.

| API | 경로 | 테이블 | 접근 |
| --- | --- | --- | --- |
| E03 응모 현황 | `GET /api/v1/events/{eventId}/statistics` | `events`, `event_participants` | 잠금 없이 읽기만 한다. 이벤트가 없거나 삭제됐으면 `ENTRY-004`. 응모자 수는 `count(*)`, 차감 합계는 `sum(used_ticket_count)`, 본인 수량은 요청 사용자의 응모자 행 값이다. 거절 이력은 응모자를 만들지 않아 제외되고, 응모권을 반환해도 누적 수량은 줄지 않으므로 합계도 줄지 않는다(`event_participants.event_id`의 UNIQUE 인덱스 사용). |
| E04 응모 | `POST /api/v1/events/{eventId}/entries` | `events` | 응모 판단에 필요한 컬럼만 `FOR SHARE`로 읽는다(네이티브 SQL). 응모끼리 막지 않고 추첨의 `FOR UPDATE`는 진행 중 응모를 기다린다. |
| E04 응모 | 같음 | `event_participants` | 사용자당 이벤트 한 행을 PK로 `FOR UPDATE` 잠그고(없으면 생성) 누적 차감 수량을 늘린다. |
| E04 응모 | 같음 | `event_entries` | 접수된 응모는 `ACCEPTED`로 저장하고 등급별 요청 장수(`requested_*_count`)를 함께 남긴다. ID는 `Idempotency-Key` UUID이다. 같은 키로 사용자·이벤트·등급별 `tickets`가 같은 요청이 다시 오면 기존 영수증을 200으로 돌려주고, 하나라도 다르면 409 `ENTRY-009`로 거절한다(등급별 비교는 차감 서비스의 사용 이력 대조로 한다). |
| E04 응모 | 같음 | `tickets`, `ticket_histories` | 응모 행 저장 뒤 차감 서비스가 응모권을 `SPENT`로 바꾸고 `USE` 이력을 남긴다. |
| E04 응모 | 같음 | `event_entries` (거절 이력) | 업무 거절(`ENTRY-002·003·005·006·007·008`, `TICKET-006`)은 응모 트랜잭션이 롤백된 뒤 별도 트랜잭션에서 같은 응모 ID로 `REJECTED` 행을 남긴다. 응모자·차감 없이 `rejection_code`·`rejection_reason`과 등급별 요청 장수만 저장하고, 이벤트가 없거나 삭제된 경우·요청 형식 오류·키 충돌·속도 제한은 남기지 않는다. 같은 키의 재요청은 저장된 코드로 처음 4xx를 돌려주고, 요청 구성이 다르면 `ENTRY-009`다. |
| E05 이벤트별 내 응모 이력 | `GET /api/v1/events/{eventId}/entries/me` | `events`, `event_entries` | 이벤트 존재를 확인(없으면 `ENTRY-004`)하고 사용자·이벤트 조건으로 접수·거절을 `created_at` 내림차순, 같으면 ID 내림차순으로 읽는다. 이벤트 제목은 `events`를 조인해 가져온다. |
| E06 내 응모 이력 | `GET /api/v1/users/me/entries` | `events`, `event_entries` | `eventId`·`status`·`from`·`to`(`[from, to)`, `created_at` 기준) 조건으로 같은 순서로 읽는다. 인덱스는 `(user_id, created_at, id)`, `(user_id, event_id, created_at, id)`다. 오프셋이 int 범위를 넘으면 조회 없이 빈 목록과 전체 건수를 돌려준다. |
| E07 자격 조회 | `GET /api/v1/events/{eventId}/eligibility` | `events`, `event_participants`, `tickets` | 잠금 없이 읽기만 한다. 사용 수량은 응모자 행, 보유 장수는 응모권 조회(T01의 등급별 보유)를 쓴다. |

## 미션 모델·저장소 현행 매핑 (GD-167)

미션 제출(M03), 목록·상세 조회(M01·M02), 내 제출 조회(M04) API는 구현되어 있고, 관리자(AM01~AM03) API는 아직 없다. 모두 `V003` 테이블을 쓰고, 미션 제출은 V025가 추가한 `mission_submissions.request_hash` 컬럼도 쓴다.

| 도메인 객체 | 테이블 | 저장소 | 비고 |
| --- | --- | --- | --- |
| `Mission` + 문항·선택지(`MissionDetail`) | `missions`, `mission_quiz_questions`, `quiz_question_options`, `survey_questions`, `survey_question_options` | `MissionRepository` | 미션과 문항을 한 번에 저장한다. 운영 중 수정 메서드는 없다. 목록은 운영 시작 시각 내림차순이다. |
| `MissionSubmission` + 답변 | `mission_submissions`, `quiz_answers`, `survey_answers` | `MissionSubmissionRepository` | 제출 시각은 서버가 저장한 시각(`created_at`)이다. 답변의 `mission_id`는 복합 외래 키가 제출·문항의 소속 미션을 맞춘다. |
| `QuizQuestionProgress` | `quiz_question_progress` | `QuizQuestionProgressRepository` | 사용자·문항 단위로 정답 완료를 한 번만 기록한다. |
| `MissionRewardClaim` | `mission_reward_claims` | `MissionRewardClaimRepository` | 사용자·미션 단위로 한 번만 청구한다. 응모권은 1장으로 고정이다. |

### 미션 조회 API 현행 매핑 (GD-174)

| API | 경로 | 테이블 | 접근 |
| --- | --- | --- | --- |
| M01 목록 | `GET /api/v1/missions` | `missions` | 읽기만 한다. `missionType` 조건만 적용하고 상태·기간으로 거르지 않아 `ACTIVE`·`ENDED` 미션을 모두 시작 시각 내림차순(같으면 ID 내림차순)으로 돌려준다. `page`는 1부터, `size`는 1~100(기본 20)이다. |
| M01 목록 | 같음 | `mission_submissions` | 페이지의 미션 ID만 모아 사용자의 완료 제출이 있는 미션을 한 번에 조회해 `completed`를 만든다. |
| M02 상세 | `GET /api/v1/missions/{missionId}` | `missions`, 문항·선택지 테이블 | 읽기만 한다. 문항과 선택지는 표시 순서이며 없는 미션은 404 `MISSION-003`이다. 응답에는 정답(`correctAnswer`, 선택지의 정답 여부)을 싣지 않는다. |
| M02 상세 | 같음 | `mission_submissions` | 사용자의 완료 제출이 있으면 `completed=true`이다. |

`rewardTicketCount`는 미션 보상이 항상 응모권 1장이라 1로 고정이며 지급 등급은 완료할 때 정해진다. `imageUrl`은 이미지 저장소가 정해지기 전이라 이벤트 조회와 같이 null이다. 퀴즈 문항의 `required`는 모든 문항을 맞혀야 완료하므로 항상 true이다.

### 내 미션 제출 조회 API 현행 매핑 (GD-176)

| API | 경로 | 테이블 | 접근 |
| --- | --- | --- | --- |
| M04 내 제출 | `GET /api/v1/missions/{missionId}/submissions/me` | `missions` | 읽기만 한다. 없는 미션은 404 `MISSION-003`이다. |
| M04 내 제출 | 같음 | `mission_submissions` | 요청 사용자의 제출만 최신순(`created_at` 내림차순, 같으면 ID 내림차순)으로 읽는다. `page`는 1부터, `size`는 1~100(기본 20)이다. 오답 제출도 `isCompleted=false`, `reward=null`인 정상 결과로 포함한다. 답변과 정답은 응답에 싣지 않는다. |
| M04 내 제출 | 같음 | `mission_reward_claims`, `tickets`, `ticket_histories` | 완료한 제출에만 그 제출의 보상 청구를 찾아 응모권 지급 결과(`claimId`, `ticketCount`, `grade`, `grantedAt`, `expiresAt`)를 다시 읽는다. 제출 접수(M03)의 재전송 응답과 같은 규칙이다. 완료한 제출에 청구나 지급이 없으면 데이터가 어긋난 것이므로 숨기지 않고 오류로 처리한다. |

### 미션 제출 API 현행 매핑 (GD-175)

| API | 경로 | 테이블 | 접근 |
| --- | --- | --- | --- |
| M03 제출 | `POST /api/v1/missions/{missionId}/submissions` | `mission_submissions` | ID는 `Idempotency-Key` UUID이다. 같은 키로 같은 요청이 다시 오면 기존 결과를 200으로, 처음 요청과 다른 본문(사용자·미션·답변 구성)이면 409 `MISSION-005`로 거절한다. 같은 본문인지는 처음 요청의 해시(`request_hash`)로 판정한다. 서버가 받은 시각을 `created_at`에 저장한다. |
| M03 제출 | 같음 | `missions`, 문항·선택지 테이블 | 읽기만 한다. 운영 기간은 서버가 제출을 받은 시각이 `[starts_at, ends_at)`이고 `ACTIVE`일 때만 인정한다. |
| M03 제출 | 같음 | `quiz_answers`, `survey_answers` | 서버가 채점한 답변을 저장한다. 이미 정답으로 완료한 퀴즈 문항에 다시 보낸 답변은 채점·저장하지 않는다. |
| M03 제출 | 같음 | `quiz_question_progress` | 이번 제출로 처음 맞힌 문항을 문항 ID 순서로 기록한다(퀴즈는 문항별 정답을 누적하며 모든 문항을 맞히면 완료). |
| M03 제출 | 같음 | `mission_reward_claims` | 완료하면 사용자·미션당 한 번 청구한다. 이미 완료한 미션의 재제출은 기존 완료 결과를 돌려준다. 응답의 `reward.claimId`는 이 청구의 ID이고, 새 제출·같은 키의 재전송·M04 조회가 같은 값을 돌려준다. |
| M03 제출 | 같음 | `tickets`, `ticket_histories` | 보상 청구 저장 뒤 지급 서비스가 같은 트랜잭션에서 응모권을 지급한다(`GRANT` 이력, 사유는 미션 제목). |

제출·답변·정답 진행·보상 청구·응모권 지급은 한 트랜잭션(`READ COMMITTED`)이다. 같은 사용자의 동시 완료는 제출 PK, `(user_id, quiz_question_id)`, `(user_id, mission_id)` 유일 제약이 직렬화하고, 늦은 요청은 새 트랜잭션에서 한 번 다시 처리해 먼저 확정된 결과를 본다.

### 이벤트 모집 상태와 감사 저장 (GD-203)

DB 컬럼 추가 없이 기존 `events.status`·`updated_at`과 `audit_logs`를 사용한다. V027은 상태·삭제 여부·시작/마감 기한별 인덱스 두 개를 추가한다. 후보는 잠금 없이 상태별 시간 범위를 조회하고 마지막 ID 뒤부터 순환한다. 개별 이벤트는 JDBC `FOR UPDATE SKIP LOCKED`로 선점한 뒤 최신 JPA 잠금 조회로 상태를 반영한다. 상태·감사는 이벤트 한 건씩 독립 트랜잭션이며 개별 실패 후에도 다음 후보를 처리한다. 등록·수정의 감사 JSON에는 저장된 경품 ID와 설정을 기록하며, 해당 업무 저장과 감사는 함께 커밋한다. 시각 응답 변경·잠금·실패 복구는 [이벤트 구현 문서](../02-api/event-lifecycle-and-audit.md)를 따른다.

## 확인된 차이와 구현 검토 항목

| ID | 코드·SQL에서 확인한 사실 | 명세 영향과 후속 작업 |
| --- | --- | --- |
| D01 | V004 `chk_streak_days`는 현재 `consecutive_days BETWEEN 0 AND 31` | 실제 연속 일수 29~31일을 저장할 수 있도록 현재 원격 `dev`에 반영됨. 단계 설정 상한 28일과 구분 |
| D02 | V006은 `max_tickets_per_user`의 양수 여부만 제한하고 별도 소진용 지정 필드는 없음 | `TICKET`·가중치 적용·상한 null 조합이 월말 소진용 상한 없는 이벤트다. 팀은 월말에만 운영하지만 등록 서비스는 이벤트 날짜가 월말인지 검사하지 않는다. 서비스가 일반 5장·미가중치 1장·미사용 0장 조합을 검증하고, 실제 응모에서는 유효 보유량을 검증한다. [공용 응모권 규칙](https://github.com/GETDDO/getddo-spec/blob/main/02-domain/ticket.md)을 따른다 |
| D03 | 취소에 따른 반환은 별도 반환 작업 테이블 없이 동기 처리한다. 옛 `ticket_refund_jobs`는 현재 스키마에 없다 | GD-202의 `EventCancellationService`가 기존 `events.canceled_at` 매핑을 사용해 취소 상태·시각과 응모별 `TicketRefundService.refund` 호출을 같은 트랜잭션에서 처리한다. 이벤트 쓰기 잠금 뒤 성공·유상 응모를 최신 읽기로 조회하고 반환 실패 시 취소·알림·감사도 롤백한다. 완료된 취소의 재요청은 409로 거절한다. 저장·연동과 검증 경계는 [취소 구현](../02-api/event-cancellation.md)을 따른다 |
| D04 | 현행 V006의 events에는 `deleted_at`이 있고 banners에는 `event_url`만 있으며 이벤트 FK·`deleted_at`은 없음 | GD-61은 이벤트만 논리 삭제한다. [공용 배너 수동 삭제 정책](https://github.com/Uplus-GETDDO/getddo-spec/blob/main/03-decisions/021-manual-banner-deletion.md)에 따라 배너는 관리자가 별도 삭제하며, 연결된 배너가 있어도 이벤트 삭제를 제한하지 않는다. 알림 연동은 [이벤트 삭제 구현](../02-api/event-deletion.md)을 따른다 |
| D05 | 검토 현재 상태는 `abuse_cases`, 공개 현재 상태는 `publications`, 게임 판정은 `game_plays`; 별도 검토/공개 변경/검증 결과 이력 테이블 없음 | `audit_logs.before_data/after_data` 등에 구조화된 이력을 저장하는 설계 또는 추가 스키마 검토. 현재 JSON 컬럼 존재만으로 이력 API가 구현되었다고 보지 않음 |
| D06 | `current_awards`는 현재 당첨 배정이고 공개 revision별 스냅샷 테이블은 없음 | 재추첨 실행 중의 새 배정과 기존 공개 명단을 구분해야 함. `current_awards`를 그대로 공개 조회하지 않고 publication 상태·변경 이력과 일치시키는 설계 필요 |
| D07 | `game_reward_claims`는 사용자·source_key 및 사용자·게임·보상일 UNIQUE를 모두 가짐 | 현재 원격 `dev`에 추가된 사용자·게임·보상일 UNIQUE를 기준으로 중복 지급을 검증 |
| D08 | `reward_policies`에 게임별 보상 조건 JSON 또는 검증 버전 필드는 없음. `games.rules`는 현재 게임 규칙 | 다음 일일 기준일 적용이 필요한 게임 보상 조건의 버전·예약 저장 구조 설계 필요 |
| D09 | 업무 Controller·DTO와 사용자 문맥 필터가 기능별로 구현되어 있다(출석, 응모, 응모권 조회, 이벤트 조회·등록, 알림, 추첨 등). 미션은 제출(M03), 목록·상세 조회(M01·M02), 내 제출 조회(M04)가 구현되어 있고 관리자(AM01~AM03) API는 없다 | 본 문서의 API 중 구현되지 않은 것은 호출할 수 없다. 구현된 API는 Swagger와 공용 계약을 대조한다 |
| D10 | `missions.status`는 `ACTIVE`/`ENDED` 두 값뿐이다(API 초안의 `DRAFT`는 스키마에 없음). 문항별 `quiz_question_progress` 존재 | 시작 전 초안 상태를 쓰려면 새 마이그레이션이 필요하다. 상태 전환·다문항 누적 달성 정책은 테이블에서 역으로 확정하지 않음 |
| D11 | 응모권 지급 원장은 `tickets`·`ticket_histories`이며 지급 수량은 1 이상이다. 출석 단계 정책은 보상 0을 허용 | 보상 정책의 수량과 실제 지급을 구분. 지급량 0 허용 범위와 응답 계약 확인 필요 |

위 항목은 최신 SQL의 정적 검토 결과이며 런타임 검증 결과가 아니다. D01·D07은 기존 초안과 달리 현재 `dev`에 반영된 상태를 기록했다.
