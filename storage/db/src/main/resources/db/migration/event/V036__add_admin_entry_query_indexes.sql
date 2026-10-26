-- 관리자 전체 응모 목록 및 이벤트별 응모·참여자 목록의 최신순 정렬을 지원한다.
-- 기존 인덱스와 응모 접수·차감 데이터는 유지한다.
ALTER TABLE `event_entries`
    ADD INDEX `idx_event_entries_created` (`created_at`, `id`),
    ADD INDEX `idx_event_entries_event_created` (`event_id`, `created_at`, `id`);

ALTER TABLE `event_participants`
    ADD INDEX `idx_event_participants_event_created` (`event_id`, `created_at`, `id`);
