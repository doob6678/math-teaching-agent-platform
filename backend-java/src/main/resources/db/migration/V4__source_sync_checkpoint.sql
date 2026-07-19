CREATE TABLE source_sync_checkpoint (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    job_id CHAR(36) NOT NULL,
    tenant_id VARCHAR(64) NOT NULL,
    source_document_id BIGINT NOT NULL,
    root_token VARCHAR(128) NULL,
    current_folder_token VARCHAR(128) NULL,
    current_path TEXT NULL,
    page_token VARCHAR(256) NULL,
    visited_folder_tokens_json JSON NOT NULL,
    downloaded_items_json JSON NOT NULL,
    failed_items_json JSON NOT NULL,
    cursor_version INT NOT NULL DEFAULT 1,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_source_sync_checkpoint_tenant_job (tenant_id, job_id),
    KEY idx_source_sync_checkpoint_document (tenant_id, source_document_id, updated_at),
    KEY idx_source_sync_checkpoint_folder (tenant_id, current_folder_token),
    CONSTRAINT fk_source_sync_checkpoint_job
        FOREIGN KEY (job_id) REFERENCES source_sync_job(job_id),
    CONSTRAINT fk_source_sync_checkpoint_source_document
        FOREIGN KEY (source_document_id) REFERENCES source_document(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
