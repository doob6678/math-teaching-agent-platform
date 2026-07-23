-- Learning-loop facts and projections. Apply this migration before enabling the MyBatis store in a real database.
CREATE TABLE IF NOT EXISTS student_learning_attempt (
    attempt_id VARCHAR(64) PRIMARY KEY,
    tenant_id VARCHAR(128) NOT NULL,
    student_id VARCHAR(128) NOT NULL,
    question_id VARCHAR(128) NOT NULL,
    question_text TEXT,
    knowledge_point_ids_json TEXT NOT NULL,
    correct BOOLEAN NOT NULL,
    response_time_ms BIGINT NOT NULL DEFAULT 0,
    submitted_at TIMESTAMP NOT NULL,
    KEY idx_learning_attempt_student (tenant_id, student_id, submitted_at),
    KEY idx_learning_attempt_question (tenant_id, question_id)
);

CREATE TABLE IF NOT EXISTS student_knowledge_mastery (
    tenant_id VARCHAR(128) NOT NULL,
    student_id VARCHAR(128) NOT NULL,
    knowledge_point_id VARCHAR(256) NOT NULL,
    mastery_percent INT NOT NULL,
    attempt_count INT NOT NULL,
    correct_count INT NOT NULL,
    incorrect_count INT NOT NULL,
    weakness_level INT NOT NULL,
    last_attempt_at TIMESTAMP NULL,
    evidence_summary VARCHAR(512) NOT NULL,
    PRIMARY KEY (tenant_id, student_id, knowledge_point_id),
    KEY idx_learning_mastery_tenant (tenant_id, weakness_level, mastery_percent)
);
