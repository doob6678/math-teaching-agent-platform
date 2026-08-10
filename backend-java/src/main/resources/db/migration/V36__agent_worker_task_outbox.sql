-- A worker task and its first dispatch event are committed together. The dispatch version makes recovery
-- idempotent: one queued generation can have at most one durable broker event.
ALTER TABLE agent_worker_task
    ADD COLUMN dispatch_version INT NOT NULL DEFAULT 1 AFTER attempt;

CREATE TABLE agent_worker_task_outbox_event (
    event_id CHAR(36) NOT NULL,
    task_id CHAR(36) NOT NULL,
    dispatch_version INT NOT NULL,
    agent_code VARCHAR(96) NOT NULL,
    stage_code VARCHAR(96) NOT NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'PENDING',
    publish_attempt INT NOT NULL DEFAULT 0,
    next_attempt_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    publish_lease_until DATETIME(3) NULL,
    locked_by VARCHAR(96) NULL,
    last_error VARCHAR(512) NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    published_at DATETIME(3) NULL,
    PRIMARY KEY (event_id),
    UNIQUE KEY uk_agent_worker_outbox_task_dispatch (task_id, dispatch_version),
    KEY idx_agent_worker_outbox_ready (status, next_attempt_at, created_at),
    KEY idx_agent_worker_outbox_lease (status, publish_lease_until),
    CONSTRAINT fk_agent_worker_outbox_task FOREIGN KEY (task_id)
        REFERENCES agent_worker_task(task_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
