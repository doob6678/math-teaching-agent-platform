CREATE TABLE student_explanation_session (
    conversation_id CHAR(36) NOT NULL PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    subject_type VARCHAR(32) NOT NULL,
    subject_id VARCHAR(128) NOT NULL,
    student_id VARCHAR(128) NULL,
    viewer_role VARCHAR(32) NOT NULL,
    last_explanation_id CHAR(36) NULL,
    last_question_text TEXT NULL,
    total_messages INT NOT NULL DEFAULT 0,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    KEY idx_student_explanation_session_owner (tenant_id, subject_type, subject_id, updated_at),
    KEY idx_student_explanation_session_student (tenant_id, student_id, updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE student_explanation_message (
    explanation_id CHAR(36) NOT NULL PRIMARY KEY,
    conversation_id CHAR(36) NOT NULL,
    tenant_id VARCHAR(64) NOT NULL,
    subject_type VARCHAR(32) NOT NULL,
    subject_id VARCHAR(128) NOT NULL,
    student_id VARCHAR(128) NULL,
    viewer_role VARCHAR(32) NOT NULL,
    question_text TEXT NULL,
    image_upload_id CHAR(36) NULL,
    image_status VARCHAR(64) NOT NULL,
    image_problem_text TEXT NULL,
    ai_provider_name VARCHAR(64) NULL,
    ai_model_code VARCHAR(128) NULL,
    prompt_tokens INT NOT NULL DEFAULT 0,
    completion_tokens INT NOT NULL DEFAULT 0,
    total_tokens INT NOT NULL DEFAULT 0,
    total_elapsed_ms BIGINT NOT NULL DEFAULT 0,
    request_json JSON NOT NULL,
    image_understanding_json JSON NOT NULL,
    ai_draft_json JSON NOT NULL,
    workflow_stages_json JSON NOT NULL,
    cards_json JSON NOT NULL,
    sources_json JSON NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    KEY idx_student_explanation_message_conversation (tenant_id, conversation_id, created_at),
    KEY idx_student_explanation_message_owner (tenant_id, subject_type, subject_id, created_at),
    KEY idx_student_explanation_message_model (ai_provider_name, ai_model_code, created_at),
    CONSTRAINT fk_student_explanation_message_session
        FOREIGN KEY (conversation_id) REFERENCES student_explanation_session(conversation_id)
        ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
