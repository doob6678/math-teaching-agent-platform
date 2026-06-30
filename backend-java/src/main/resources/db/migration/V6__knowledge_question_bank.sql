CREATE TABLE knowledge_point (
    knowledge_point_id CHAR(36) NOT NULL,
    tenant_id VARCHAR(64) NOT NULL,
    owner_subject_id VARCHAR(128) NULL,
    permission_scope VARCHAR(128) NOT NULL,
    knowledge_point_name VARCHAR(255) NOT NULL,
    chapter_path VARCHAR(512) NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'active',
    source_summary VARCHAR(255) NOT NULL DEFAULT 'manual',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (knowledge_point_id),
    KEY idx_knowledge_point_tenant_status (tenant_id, status, updated_at),
    KEY idx_knowledge_point_scope_owner (tenant_id, permission_scope, owner_subject_id),
    KEY idx_knowledge_point_name (knowledge_point_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE knowledge_relation (
    relation_id CHAR(36) NOT NULL,
    tenant_id VARCHAR(64) NOT NULL,
    source_knowledge_point_id CHAR(36) NOT NULL,
    target_knowledge_point_id CHAR(36) NOT NULL,
    relation_type VARCHAR(64) NOT NULL,
    evidence_summary VARCHAR(512) NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'active',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (relation_id),
    KEY idx_knowledge_relation_source (tenant_id, source_knowledge_point_id, relation_type),
    KEY idx_knowledge_relation_target (tenant_id, target_knowledge_point_id, relation_type),
    CONSTRAINT fk_knowledge_relation_source
        FOREIGN KEY (source_knowledge_point_id) REFERENCES knowledge_point(knowledge_point_id),
    CONSTRAINT fk_knowledge_relation_target
        FOREIGN KEY (target_knowledge_point_id) REFERENCES knowledge_point(knowledge_point_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE question_bank_item (
    question_id CHAR(36) NOT NULL,
    tenant_id VARCHAR(64) NOT NULL,
    owner_subject_id VARCHAR(128) NULL,
    permission_scope VARCHAR(128) NOT NULL,
    question_title VARCHAR(255) NOT NULL,
    question_text LONGTEXT NOT NULL,
    answer_json JSON NULL,
    difficulty VARCHAR(32) NULL,
    source_document_id BIGINT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'active',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (question_id),
    KEY idx_question_bank_tenant_scope (tenant_id, permission_scope, owner_subject_id, status),
    KEY idx_question_bank_title (question_title),
    KEY idx_question_bank_source_document (source_document_id),
    CONSTRAINT fk_question_bank_source_document
        FOREIGN KEY (source_document_id) REFERENCES source_document(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE question_knowledge_link (
    link_id CHAR(36) NOT NULL,
    tenant_id VARCHAR(64) NOT NULL,
    question_id CHAR(36) NOT NULL,
    knowledge_point_id CHAR(36) NOT NULL,
    confidence DECIMAL(8,6) NULL,
    bind_type VARCHAR(32) NOT NULL DEFAULT 'manual',
    status VARCHAR(32) NOT NULL DEFAULT 'active',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (link_id),
    KEY idx_question_knowledge_link_question (tenant_id, question_id, status),
    KEY idx_question_knowledge_link_point (tenant_id, knowledge_point_id, status),
    CONSTRAINT fk_question_knowledge_link_question
        FOREIGN KEY (question_id) REFERENCES question_bank_item(question_id),
    CONSTRAINT fk_question_knowledge_link_point
        FOREIGN KEY (knowledge_point_id) REFERENCES knowledge_point(knowledge_point_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
