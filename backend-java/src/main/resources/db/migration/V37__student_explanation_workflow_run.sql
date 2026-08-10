-- Java-owned public student explanation workflows and replayable SSE events.
-- Python keeps opaque model sub-runs only; public identity, authorization and history stay in Java.
CREATE TABLE student_explanation_workflow_run (
    run_id CHAR(36) NOT NULL,
    tenant_id VARCHAR(64) NOT NULL,
    subject_type VARCHAR(32) NOT NULL,
    subject_id VARCHAR(128) NOT NULL,
    client_request_id VARCHAR(128) NOT NULL,
    request_fingerprint CHAR(64) NOT NULL,
    request_json LONGTEXT NOT NULL,
    status VARCHAR(24) NOT NULL,
    retry_count INT NOT NULL DEFAULT 0,
    deadline_at TIMESTAMP(3) NULL,
    response_json LONGTEXT NULL,
    error_code VARCHAR(64) NULL,
    error_message VARCHAR(512) NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (run_id),
    UNIQUE KEY uk_student_explanation_workflow_request (tenant_id, subject_type, subject_id, client_request_id),
    KEY idx_student_explanation_workflow_recovery (status, deadline_at, updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE student_explanation_workflow_event (
    event_id BIGINT NOT NULL AUTO_INCREMENT,
    run_id CHAR(36) NOT NULL,
    event_name VARCHAR(32) NOT NULL,
    event_json LONGTEXT NOT NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (event_id),
    KEY idx_student_explanation_workflow_event_cursor (run_id, event_id),
    CONSTRAINT fk_student_explanation_workflow_event_run
        FOREIGN KEY (run_id) REFERENCES student_explanation_workflow_run(run_id)
        ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
