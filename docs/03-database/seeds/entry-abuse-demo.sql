-- GD-142 수동 데모 전용. Flyway 경로 밖에 두며 자동 실행하지 않는다.
-- V030 적용 후 대상 DB·기존 사용자 UUID를 확인하고 같은 세션에서 다음 변수를 먼저 지정한다.
-- SET @abuse_demo_user_id = '실제 데모 사용자 UUID';
-- SET @abuse_demo_event_id = NULL; -- 또는 존재하는 데모 이벤트 UUID
-- UTC 고정 예시다. 기존 ID가 있으면 실패하며 갱신·삭제하지 않는다.
SET time_zone = '+00:00';

INSERT INTO abuse_cases (id, user_id, event_id, source_type, detection_type, detection_reason,
  evidence, occurred_at, detected_at, blocked_at, blocked_until)
VALUES (UUID_TO_BIN('14200000-0000-4000-8000-000000000001'), UUID_TO_BIN(@abuse_demo_user_id),
  UUID_TO_BIN(@abuse_demo_event_id), 'ENTRY', 'REPEATED_RATE_LIMIT_EXCEEDED',
  '응모 요청 한도 초과가 반복되어 일시 차단되었습니다.',
  JSON_OBJECT('ruleVersion', 'v1', 'triggerScope', 'USER',
    'windowStartedAt', '2026-10-22T05:59:30Z', 'windowEndsAt', '2026-10-22T06:00:30Z',
    'requestLimit', 20, 'rejectedCount', 20, 'rejectionThreshold', 20),
  '2026-10-22 06:00:00.000000', '2026-10-22 06:00:00.000000',
  '2026-10-22 06:00:00.000000', '2026-10-22 06:10:00.000000');
