-- 큰 수신자 JSON이 있는 작업도 별도 정렬 없이 예약 순서로 선점할 수 있게 한다.
CREATE INDEX ix_notification_job_schedule
    ON notification_jobs (scheduled_at, id);
