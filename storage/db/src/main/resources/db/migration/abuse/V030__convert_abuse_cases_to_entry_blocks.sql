-- GD-142: 빈 abuse_cases만 자동 차단 이력 구조로 전환한다. 기존 데이터는 삭제·변환하지 않는다.
-- 사전 조건: 기존 생산자의 쓰기를 중지한다. 비어 있지 않으면 첫 ALTER 검증에서 실패한다.
-- 이 임시 CHECK는 두 ALTER 사이의 쓰기도 막는다. 후속 DDL 실패 시 자동으로 해제하지 않는다.
ALTER TABLE abuse_cases
  ADD CONSTRAINT chk_abuse_empty_transition CHECK (id IS NULL);

ALTER TABLE abuse_cases
  DROP CHECK chk_abuse_empty_transition,
  DROP CHECK chk_abuse_review_shape,
  DROP CHECK chk_abuse_source_evidence,
  DROP FOREIGN KEY fk_abuse_cases_6,
  DROP INDEX uq_abuse_detection_key,
  DROP COLUMN reviewed_by,
  DROP COLUMN review_status,
  DROP COLUMN reviewed_at,
  DROP COLUMN review_reason,
  DROP COLUMN request_id,
  DROP COLUMN detection_key,
  ADD COLUMN source_type varchar(40) NOT NULL COMMENT '요청 기능. 현재 ENTRY',
  ADD COLUMN blocked_at datetime(6) NOT NULL COMMENT '최초 차단 시작 시각 UTC',
  ADD COLUMN blocked_until datetime(6) NOT NULL COMMENT '최초 차단 종료 예정 시각 UTC',
  MODIFY COLUMN evidence json NOT NULL COMMENT '차단 당시 판정 근거',
  ADD UNIQUE KEY uq_abuse_user_source_blocked (user_id, source_type, blocked_at),
  ADD KEY idx_abuse_blocked (blocked_at, id),
  ADD KEY idx_abuse_user_blocked (user_id, blocked_at, id),
  ADD CONSTRAINT chk_abuse_block_period CHECK (blocked_until > blocked_at),
  ADD CONSTRAINT chk_abuse_entry_sources CHECK (source_type <> 'ENTRY'
    OR (attendance_id IS NULL AND mission_submission_id IS NULL AND game_play_id IS NULL)),
  ADD CONSTRAINT chk_abuse_entry_detection CHECK (source_type <> 'ENTRY'
    OR (BINARY detection_type = BINARY 'REPEATED_RATE_LIMIT_EXCEEDED'
      AND BINARY detection_reason = BINARY '응모 요청 한도 초과가 반복되어 일시 차단되었습니다.'));
