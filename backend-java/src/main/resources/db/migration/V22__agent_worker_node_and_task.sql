-- Control-plane records for independently deployed Java Agent Workers. Secrets and prompts are deliberately not
-- stored here: worker tasks reference the existing workflow and carry only scheduling/lease state.
CREATE TABLE agent_worker_node (
    worker_id VARCHAR(96) NOT NULL,
    worker_version VARCHAR(64) NOT NULL,
    supported_agents_json JSON NOT NULL,
    max_concurrency INT NOT NULL,
    current_load INT NOT NULL DEFAULT 0,
    status VARCHAR(24) NOT NULL,
    last_heartbeat_at DATETIME(3) NOT NULL,
    completed_task_count BIGINT NOT NULL DEFAULT 0,
    failed_task_count BIGINT NOT NULL DEFAULT 0,
    last_error_summary VARCHAR(512) NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (worker_id),
    KEY idx_agent_worker_node_status_heartbeat (status, last_heartbeat_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE agent_worker_task (
    task_id CHAR(36) NOT NULL,
    workflow_id CHAR(36) NOT NULL,
    tenant_id VARCHAR(64) NOT NULL,
    agent_code VARCHAR(96) NOT NULL,
    stage_code VARCHAR(96) NOT NULL,
    status VARCHAR(24) NOT NULL,
    attempt INT NOT NULL DEFAULT 0,
    lease_token CHAR(36) NULL,
    lease_expires_at DATETIME(3) NULL,
    worker_id VARCHAR(96) NULL,
    request_json JSON NOT NULL,
    error_summary VARCHAR(512) NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (task_id),
    KEY idx_agent_worker_task_status_agent (status, agent_code, created_at),
    KEY idx_agent_worker_task_lease (status, lease_expires_at),
    KEY idx_agent_worker_task_workflow (workflow_id, created_at),
    CONSTRAINT fk_agent_worker_task_workflow FOREIGN KEY (workflow_id)
        REFERENCES multi_agent_writing_workflow(workflow_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
