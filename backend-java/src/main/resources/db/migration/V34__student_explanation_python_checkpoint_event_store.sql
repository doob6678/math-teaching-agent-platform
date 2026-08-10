-- Shared student-explanation worker run state and replay cursor.
-- Java owns business history and authorization; Python stores only opaque run IDs and worker-safe terminal packages.
CREATE TABLE IF NOT EXISTS student_explanation_checkpoint (
    run_id VARCHAR(128) NOT NULL,
    request_fingerprint CHAR(64) NOT NULL,
    status VARCHAR(24) NOT NULL,
    response_json LONGTEXT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (run_id)
);

CREATE TABLE IF NOT EXISTS student_explanation_event (
    event_id BIGINT NOT NULL AUTO_INCREMENT,
    run_id VARCHAR(128) NOT NULL,
    event_json LONGTEXT NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (event_id),
    KEY idx_student_explanation_event_run_cursor (run_id, event_id)
);
