-- File-level Feishu sync state makes discovery, download, parsing, embedding, indexing, and retry transitions durable.
-- The lease and recovery columns allow another worker to reclaim a file after an interrupted stage.
ALTER TABLE teacher_source_sync_manifest
    ADD COLUMN local_path TEXT NULL AFTER document_id,
    ADD COLUMN last_error TEXT NULL AFTER local_path,
    ADD COLUMN lease_until TIMESTAMP NULL AFTER last_error,
    ADD COLUMN next_retry_at TIMESTAMP NULL AFTER lease_until,
    ADD COLUMN downloaded_at TIMESTAMP NULL AFTER next_retry_at,
    ADD COLUMN parsed_at TIMESTAMP NULL AFTER downloaded_at,
    ADD COLUMN indexed_at TIMESTAMP NULL AFTER parsed_at,
    ADD COLUMN sync_status VARCHAR(32) NOT NULL DEFAULT 'DISCOVERED' AFTER archive_status,
    ADD COLUMN attempt INT NOT NULL DEFAULT 0 AFTER sync_status,
    ADD INDEX idx_sync_manifest_recovery (tenant_id, sync_status, next_retry_at, lease_until, updated_at);
