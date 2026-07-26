CREATE TABLE source_sync_job (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    job_id CHAR(36) NOT NULL,
    source_document_id BIGINT NOT NULL,
    tenant_id VARCHAR(64) NOT NULL,
    source_type VARCHAR(32) NOT NULL,
    operation VARCHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL,
    phase VARCHAR(64) NOT NULL,
    attempt INT NOT NULL DEFAULT 0,
    created_by VARCHAR(64) NOT NULL,
    staging_path TEXT NULL,
    message TEXT NULL,
    metadata_json JSON NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_source_sync_job_job_id (job_id),
    KEY idx_source_sync_job_tenant_document (tenant_id, source_document_id, created_at),
    KEY idx_source_sync_job_status (tenant_id, status, phase, created_at),
    CONSTRAINT fk_source_sync_job_source_document
        FOREIGN KEY (source_document_id) REFERENCES source_document(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
