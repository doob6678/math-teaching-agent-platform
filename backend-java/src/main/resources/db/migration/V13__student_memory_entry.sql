CREATE TABLE student_memory_entry (
    memory_id CHAR(36) NOT NULL PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    student_id VARCHAR(128) NOT NULL,
    memory_scope VARCHAR(32) NOT NULL,
    knowledge_point_name VARCHAR(255) NOT NULL,
    question_text TEXT NOT NULL,
    answer_text MEDIUMTEXT NOT NULL,
    status VARCHAR(32) NOT NULL,
    metadata_json JSON NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    KEY idx_student_memory_candidates (tenant_id, status, memory_scope, student_id, created_at),
    KEY idx_student_memory_knowledge (tenant_id, student_id, knowledge_point_name, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
