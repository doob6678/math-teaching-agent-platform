ALTER TABLE agent_run_trace
    MODIFY plan_id VARCHAR(96) NOT NULL;

CREATE TABLE multi_agent_writing_workflow (
    workflow_id CHAR(36) NOT NULL,
    tenant_id VARCHAR(64) NOT NULL DEFAULT 'default',
    subject_type VARCHAR(32) NOT NULL,
    subject_id VARCHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL,
    message VARCHAR(512) NOT NULL DEFAULT '',
    metadata_json JSON NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (workflow_id),
    KEY idx_multi_agent_writing_subject (tenant_id, subject_type, subject_id, updated_at),
    KEY idx_multi_agent_writing_status (tenant_id, status, updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
