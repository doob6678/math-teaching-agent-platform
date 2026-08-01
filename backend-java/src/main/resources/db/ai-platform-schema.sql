-- Durable AI-platform state. Apply before enabling sync-root or review-audit jobs in MySQL.
-- Raw source bytes remain in controlled storage; only immutable references and hashes are persisted here.

-- Python AI worker writes one immutable row per provider attempt, including failures and fallbacks.
CREATE TABLE IF NOT EXISTS ai_usage_event (
    usage_event_id BIGINT PRIMARY KEY AUTO_INCREMENT,
    run_id VARCHAR(128) NOT NULL,
    provider VARCHAR(64) NOT NULL,
    model_code VARCHAR(128) NOT NULL,
    attempt_no INT NOT NULL,
    status VARCHAR(24) NOT NULL,
    prompt_tokens BIGINT NOT NULL DEFAULT 0,
    completion_tokens BIGINT NOT NULL DEFAULT 0,
    total_tokens BIGINT NOT NULL DEFAULT 0,
    estimated_cost DECIMAL(20,10) NOT NULL DEFAULT 0,
    usage_source VARCHAR(24) NOT NULL,
    error_code VARCHAR(128) NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    UNIQUE KEY uq_ai_usage_attempt (run_id, attempt_no, provider),
    KEY idx_ai_usage_provider_model (provider, model_code, created_at),
    KEY idx_ai_usage_run (run_id, created_at)
);

CREATE TABLE IF NOT EXISTS teacher_source_sync_root (
    sync_root_id VARCHAR(64) PRIMARY KEY,
    tenant_id VARCHAR(128) NOT NULL,
    root_url TEXT NOT NULL,
    source_type VARCHAR(32) NOT NULL,
    permission_scope VARCHAR(32) NOT NULL,
    authorization_status VARCHAR(32) NOT NULL,
    authorization_failure_summary VARCHAR(1024) NULL,
    last_success_at TIMESTAMP NULL,
    last_discovered_at TIMESTAMP NULL,
    created_by VARCHAR(128) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    UNIQUE KEY uq_sync_root_tenant_url (tenant_id, root_url(255)),
    KEY idx_sync_root_status (tenant_id, authorization_status, updated_at)
);

CREATE TABLE IF NOT EXISTS feishu_user_credential (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    credential_id VARCHAR(64) NOT NULL,
    tenant_id VARCHAR(128) NOT NULL,
    subject_id VARCHAR(128) NOT NULL,
    access_token_ciphertext TEXT NOT NULL,
    refresh_token_ciphertext TEXT NULL,
    expires_at TIMESTAMP NULL,
    status VARCHAR(16) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    UNIQUE KEY uq_feishu_credential_id (credential_id),
    UNIQUE KEY uq_feishu_active_subject (tenant_id, subject_id, status)
);
CREATE TABLE IF NOT EXISTS feishu_resource_binding (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    tenant_id VARCHAR(128) NOT NULL,
    document_id VARCHAR(64) NOT NULL,
    subject_id VARCHAR(128) NOT NULL,
    credential_id VARCHAR(64) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    UNIQUE KEY uq_feishu_resource_binding (tenant_id, document_id),
    KEY idx_feishu_binding_credential (tenant_id, credential_id)
);

CREATE TABLE IF NOT EXISTS teacher_source_sync_manifest (
    manifest_id VARCHAR(64) PRIMARY KEY,
    sync_root_id VARCHAR(64) NOT NULL,
    tenant_id VARCHAR(128) NOT NULL,
    provider_item_id VARCHAR(256) NOT NULL,
    parent_provider_item_id VARCHAR(256) NULL,
    logical_path TEXT NOT NULL,
    item_type VARCHAR(32) NOT NULL,
    revision VARCHAR(256) NULL,
    provider_modified_at TIMESTAMP NULL,
    content_checksum CHAR(64) NULL,
    document_id VARCHAR(64) NULL,
    archive_status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    sync_status VARCHAR(32) NOT NULL DEFAULT 'DISCOVERED',
    attempt INT NOT NULL DEFAULT 0,
    local_path TEXT NULL,
    last_error TEXT NULL,
    lease_until TIMESTAMP NULL,
    next_retry_at TIMESTAMP NULL,
    downloaded_at TIMESTAMP NULL,
    parsed_at TIMESTAMP NULL,
    indexed_at TIMESTAMP NULL,
    indexed_revision VARCHAR(256) NULL,
    discovered_at TIMESTAMP NOT NULL,
    archived_at TIMESTAMP NULL,
    updated_at TIMESTAMP NOT NULL,
    UNIQUE KEY uq_sync_manifest_item (sync_root_id, provider_item_id),
    KEY idx_sync_manifest_incremental (sync_root_id, archive_status, revision, provider_modified_at),
    KEY idx_sync_manifest_document (tenant_id, document_id),
    KEY idx_sync_manifest_recovery (tenant_id, sync_status, next_retry_at, lease_until, updated_at)
);

CREATE TABLE IF NOT EXISTS teaching_review_audit (
    review_audit_id VARCHAR(64) PRIMARY KEY,
    task_id VARCHAR(64) NOT NULL,
    tenant_id VARCHAR(128) NOT NULL,
    reviewer_subject_type VARCHAR(32) NOT NULL,
    reviewer_subject_id VARCHAR(128) NOT NULL,
    policy_code VARCHAR(32) NOT NULL,
    decision_code VARCHAR(32) NOT NULL,
    reason_text VARCHAR(1000) NULL,
    common_draft_hash CHAR(64) NOT NULL,
    quality_status VARCHAR(32) NOT NULL,
    teacher_version_hash CHAR(64) NULL,
    student_version_hash CHAR(64) NULL,
    lecture_version_hash CHAR(64) NULL,
    published_status VARCHAR(32) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    KEY idx_review_audit_task (task_id, created_at),
    KEY idx_review_audit_tenant (tenant_id, created_at)
);
