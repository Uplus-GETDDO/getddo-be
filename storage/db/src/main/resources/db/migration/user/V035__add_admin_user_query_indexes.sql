-- 관리자 사용자 목록의 최신순 정렬과 전체 활성 일반 사용자 집계를 지원한다.
ALTER TABLE `users`
    ADD INDEX `ix_users_created_id` (`created_at` DESC, `id` DESC),
    ADD INDEX `ix_users_role_status` (`role`, `status`);
