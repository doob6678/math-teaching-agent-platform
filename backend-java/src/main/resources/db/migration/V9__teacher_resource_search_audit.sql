CREATE TABLE teacher_resource_search_audit_log (
    id BIGINT NOT NULL AUTO_INCREMENT,
    query_id CHAR(36) NOT NULL,
    occurred_at DATETIME(3) NOT NULL,
    tenant_id VARCHAR(64) NOT NULL DEFAULT 'default',
    subject_type VARCHAR(32) NOT NULL,
    subject_id VARCHAR(128) NOT NULL,
    query_text TEXT NOT NULL,
    requested_limit INT NOT NULL,
    retrieval_mode VARCHAR(64) NOT NULL,
    hit_count INT NOT NULL DEFAULT 0,
    elapsed_ms BIGINT NULL,
    endpoint VARCHAR(255) NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_teacher_resource_search_query (query_id),
    KEY idx_teacher_resource_search_subject (tenant_id, subject_type, subject_id, occurred_at),
    KEY idx_teacher_resource_search_endpoint (endpoint, occurred_at),
    KEY idx_teacher_resource_search_created_at (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE teacher_resource_search_audit_hit (
    id BIGINT NOT NULL AUTO_INCREMENT,
    query_id CHAR(36) NOT NULL,
    rank_no INT NOT NULL,
    document_id VARCHAR(128) NOT NULL,
    document_title VARCHAR(255) NULL,
    permission_scope VARCHAR(128) NULL,
    block_id VARCHAR(128) NOT NULL,
    block_type VARCHAR(32) NULL,
    block_order INT NOT NULL DEFAULT 0,
    page_no INT NULL,
    score DECIMAL(12,6) NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_teacher_resource_search_hit_query_rank (query_id, rank_no),
    KEY idx_teacher_resource_search_hit_document (document_id, block_id),
    CONSTRAINT fk_teacher_resource_search_hit_query
        FOREIGN KEY (query_id) REFERENCES teacher_resource_search_audit_log(query_id)
        ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
