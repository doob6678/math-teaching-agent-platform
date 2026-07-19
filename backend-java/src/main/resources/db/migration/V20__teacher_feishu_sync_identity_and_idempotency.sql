-- A Feishu URL is a presentation address.  The provider token is the durable identity used to prevent the same
-- remote document from being registered repeatedly under different titles or query strings.
ALTER TABLE source_document
    ADD COLUMN source_identity VARCHAR(512) NULL COMMENT 'Canonical remote token or normalized local path used as source identity' AFTER original_url,
    ADD COLUMN source_identity_hash CHAR(64) NULL COMMENT 'SHA-256 of source_identity, used by the idempotency constraint' AFTER source_identity,
    ADD COLUMN feishu_export_format VARCHAR(8) NULL COMMENT 'Native Feishu export representation: md, docx, or pdf' AFTER provider_revision;

CREATE UNIQUE INDEX uk_source_document_source_identity
    ON source_document (tenant_id, created_by, source_type, source_identity_hash, feishu_export_format);

-- MySQL permits multiple NULLs in a unique index.  Completed/failed jobs have NULL here, while one queued, running,
-- or paused job is atomically protected for a source document even when several application instances receive clicks.
ALTER TABLE source_sync_job
    ADD COLUMN active_document_key BIGINT
        GENERATED ALWAYS AS (CASE WHEN status IN ('queued', 'running', 'paused') THEN source_document_id ELSE NULL END) STORED
        COMMENT 'Non-null only while a job is active; enforces one active sync per source document' AFTER source_document_id,
    ADD UNIQUE INDEX uk_source_sync_job_active_document (tenant_id, active_document_key);
