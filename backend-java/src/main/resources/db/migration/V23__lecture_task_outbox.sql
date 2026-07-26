-- Durable outbox prevents an accepted teaching task from being lost between MySQL commit and RabbitMQ publish.
CREATE TABLE lecture_task_outbox_event (
    event_id CHAR(36) NOT NULL,
    task_id CHAR(36) NOT NULL,
    event_type VARCHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL,
    created_at DATETIME(3) NOT NULL,
    published_at DATETIME(3) NULL,
    PRIMARY KEY (event_id),
    UNIQUE KEY uk_lecture_task_outbox_task_event (task_id, event_type),
    KEY idx_lecture_task_outbox_status_created (status, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
