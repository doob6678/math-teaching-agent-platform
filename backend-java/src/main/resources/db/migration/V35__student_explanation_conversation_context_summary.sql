ALTER TABLE student_explanation_session
    ADD COLUMN context_summary_from_message_id CHAR(36) NULL AFTER total_messages,
    ADD COLUMN context_summary_to_message_id CHAR(36) NULL AFTER context_summary_from_message_id,
    ADD COLUMN context_summary_version INT NOT NULL DEFAULT 0 AFTER context_summary_to_message_id,
    ADD COLUMN context_summary_hash CHAR(64) NULL AFTER context_summary_version,
    ADD COLUMN context_summary_text MEDIUMTEXT NULL AFTER context_summary_hash;

CREATE INDEX idx_student_explanation_message_conversation_order
    ON student_explanation_message (tenant_id, conversation_id, created_at, explanation_id);
