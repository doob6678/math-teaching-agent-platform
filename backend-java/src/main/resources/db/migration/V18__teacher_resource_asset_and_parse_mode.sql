ALTER TABLE source_document
    ADD COLUMN parse_mode VARCHAR(16) NOT NULL DEFAULT 'TEXT' COMMENT 'TEXT keeps deterministic extraction; AI enables costly semantic labeling without changing permissions' AFTER parse_status,
    ADD COLUMN provider_revision VARCHAR(128) NULL COMMENT 'Remote provider revision, for example Feishu last edited revision used by incremental sync' AFTER version;

CREATE INDEX idx_source_document_parse_mode
    ON source_document (tenant_id, parse_mode, sync_status);

CREATE TABLE teacher_resource_asset (
    asset_id CHAR(36) NOT NULL,
    tenant_id VARCHAR(64) NOT NULL DEFAULT 'default',
    owner_subject_id VARCHAR(64) NOT NULL,
    document_id BIGINT NOT NULL,
    block_id BIGINT NULL,
    permission_scope VARCHAR(128) NOT NULL DEFAULT 'TEACHER_PRIVATE',
    source_path VARCHAR(1024) NULL,
    page_no INT NULL,
    provider_asset_id VARCHAR(255) NOT NULL,
    checksum VARCHAR(128) NOT NULL,
    mime_type VARCHAR(128) NOT NULL,
    width INT NULL,
    height INT NULL,
    storage_key VARCHAR(1024) NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'active',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (asset_id),
    UNIQUE KEY uk_teacher_asset_provider_checksum (tenant_id, document_id, provider_asset_id, checksum),
    KEY idx_teacher_asset_document_status (tenant_id, document_id, status),
    KEY idx_teacher_asset_owner_scope (tenant_id, owner_subject_id, permission_scope, status),
    KEY idx_teacher_asset_block (block_id),
    CONSTRAINT fk_teacher_asset_source_document
        FOREIGN KEY (document_id) REFERENCES source_document(id),
    CONSTRAINT fk_teacher_asset_document_block
        FOREIGN KEY (block_id) REFERENCES document_block(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
