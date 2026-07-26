CREATE TABLE teaching_task (
    task_id CHAR(36) NOT NULL,
    tenant_id VARCHAR(64) NOT NULL DEFAULT 'default',
    subject_type VARCHAR(32) NOT NULL,
    subject_id VARCHAR(128) NOT NULL,
    owner_key VARCHAR(320) NOT NULL,
    idempotency_key VARCHAR(512) NOT NULL,
    client_request_id VARCHAR(128) NOT NULL,
    status VARCHAR(32) NOT NULL,
    response_json JSON NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (task_id),
    UNIQUE KEY uk_teaching_task_idempotency (idempotency_key),
    KEY idx_teaching_task_owner (tenant_id, subject_type, subject_id, updated_at),
    KEY idx_teaching_task_owner_key (owner_key, updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE teaching_human_feedback (
    feedback_id CHAR(36) NOT NULL,
    task_id CHAR(36) NOT NULL,
    tenant_id VARCHAR(64) NOT NULL DEFAULT 'default',
    subject_type VARCHAR(32) NOT NULL,
    subject_id VARCHAR(128) NOT NULL,
    owner_key VARCHAR(320) NOT NULL,
    rating INT NOT NULL,
    decision VARCHAR(64) NOT NULL,
    comment TEXT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (feedback_id),
    KEY idx_teaching_feedback_owner_task (owner_key, task_id, created_at),
    KEY idx_teaching_feedback_task (tenant_id, task_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
