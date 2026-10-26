-- 관리자 작업 목록의 최신순 ID 페이지를 payload 정렬 없이 조회한다.
CREATE INDEX ix_notification_job_created
    ON notification_jobs (created_at, id);
