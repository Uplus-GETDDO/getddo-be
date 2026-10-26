-- 관리자 응모권 이력·지급 조회가 전체 이력을 훑고 정렬하지 않고 최신순 첫 쪽을 인덱스 순서로 읽게 한다.
ALTER TABLE `ticket_histories`
    ADD INDEX `ix_ticket_histories_created` (`created_at`, `id`),
    ADD INDEX `ix_ticket_histories_op_created` (`operation_type`, `created_at`, `id`);
