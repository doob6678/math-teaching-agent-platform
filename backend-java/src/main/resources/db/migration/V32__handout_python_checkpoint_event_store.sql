-- Shared LangGraph node boundaries and operational event cursor.
-- Java remains the business workflow source of truth; these tables only make Python resume safe across replicas.
CREATE TABLE IF NOT EXISTS handout_checkpoint (
    run_id VARCHAR(80) NOT NULL,
    status VARCHAR(24) NOT NULL,
    state_json LONGTEXT NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (run_id)
);

CREATE TABLE IF NOT EXISTS handout_event (
    event_id BIGINT NOT NULL AUTO_INCREMENT,
    run_id VARCHAR(80) NOT NULL,
    event_json LONGTEXT NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (event_id),
    KEY idx_handout_event_run_cursor (run_id, event_id)
);
