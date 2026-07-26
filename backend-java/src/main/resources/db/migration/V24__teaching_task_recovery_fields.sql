-- TeachingTaskEntity and the RabbitMQ recovery worker share these columns. Keeping them in Flyway makes a fresh
-- deployment and an upgraded database behave identically instead of failing only when the first task is listed.
ALTER TABLE teaching_task
    ADD COLUMN retry_count INT NOT NULL DEFAULT 0 AFTER response_json,
    ADD COLUMN lease_owner VARCHAR(128) NULL AFTER retry_count,
    ADD COLUMN lease_token CHAR(36) NULL AFTER lease_owner,
    ADD COLUMN lease_expire_at DATETIME(3) NULL AFTER lease_token,
    ADD COLUMN current_stage VARCHAR(128) NULL AFTER lease_expire_at,
    ADD COLUMN last_error TEXT NULL AFTER current_stage,
    ADD COLUMN started_at DATETIME(3) NULL AFTER updated_at,
    ADD COLUMN finished_at DATETIME(3) NULL AFTER started_at,
    ADD INDEX idx_teaching_task_recovery (status, lease_expire_at, updated_at);
