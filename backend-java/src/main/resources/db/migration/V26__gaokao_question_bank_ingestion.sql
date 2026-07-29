-- The import run is intentionally independent from RabbitMQ jobs: it is the durable recovery checkpoint for
-- bounded, low-concurrency exam ingestion and therefore records both processing and verification state.
CREATE TABLE import_run (
    import_run_id CHAR(36) NOT NULL,
    paper_type VARCHAR(32) NOT NULL,
    status VARCHAR(48) NOT NULL,
    verification_status VARCHAR(48) NOT NULL DEFAULT 'NOT_STARTED',
    input_root VARCHAR(1024) NOT NULL,
    requested_model VARCHAR(128) NOT NULL DEFAULT 'gpt-5.6-luna',
    progress_json JSON NOT NULL,
    evidence_path VARCHAR(1024) NOT NULL,
    failure_summary TEXT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (import_run_id),
    KEY idx_import_run_status (status, updated_at),
    KEY idx_import_run_verification (verification_status, updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- A changed physical file is a new source version. The unique run/hash key makes resume idempotent without
-- overwriting prior evidence.
CREATE TABLE import_source_file (
    source_file_id CHAR(36) NOT NULL,
    import_run_id CHAR(36) NOT NULL,
    source_file_hash CHAR(64) NOT NULL,
    source_file_name VARCHAR(512) NOT NULL,
    media_type VARCHAR(128) NOT NULL,
    parse_status VARCHAR(48) NOT NULL,
    page_count INT NULL,
    metadata_json JSON NOT NULL,
    failure_summary TEXT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (source_file_id),
    UNIQUE KEY uq_import_source_run_hash (import_run_id, source_file_hash),
    KEY idx_import_source_hash (source_file_hash),
    CONSTRAINT fk_import_source_run FOREIGN KEY (import_run_id) REFERENCES import_run(import_run_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Only reviewed, publishable questions become canonical rows and are eligible for Milvus indexing.
CREATE TABLE canonical_question (
    canonical_question_id CHAR(36) NOT NULL,
    paper_type VARCHAR(32) NOT NULL,
    parent_question_id CHAR(36) NULL,
    content_json JSON NOT NULL,
    formula_canonical_json JSON NOT NULL,
    answer_json JSON NULL,
    solution_json JSON NULL,
    answer_provenance VARCHAR(64) NULL,
    publication_status VARCHAR(48) NOT NULL,
    fingerprint CHAR(64) NOT NULL,
    display_citation VARCHAR(512) NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (canonical_question_id),
    UNIQUE KEY uq_canonical_question_fingerprint (fingerprint),
    KEY idx_canonical_question_publication (publication_status, updated_at),
    CONSTRAINT fk_canonical_question_parent FOREIGN KEY (parent_question_id) REFERENCES canonical_question(canonical_question_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Each occurrence preserves original document coordinates; uniqueness is deliberately source-scoped, not content-only.
CREATE TABLE question_source_occurrence (
    occurrence_id CHAR(36) NOT NULL,
    canonical_question_id CHAR(36) NULL,
    source_file_id CHAR(36) NOT NULL,
    page_start INT NOT NULL,
    page_end INT NOT NULL,
    region_json JSON NOT NULL,
    original_question_number VARCHAR(64) NULL,
    recognized_content_json JSON NOT NULL,
    occurrence_status VARCHAR(48) NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (occurrence_id),
    UNIQUE KEY uq_question_occurrence_location (source_file_id, page_start, page_end, original_question_number),
    KEY idx_question_occurrence_canonical (canonical_question_id),
    CONSTRAINT fk_question_occurrence_canonical FOREIGN KEY (canonical_question_id) REFERENCES canonical_question(canonical_question_id),
    CONSTRAINT fk_question_occurrence_file FOREIGN KEY (source_file_id) REFERENCES import_source_file(source_file_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Immutable audit rows cover split/pair/dedup/review decisions and permit a merge to be reversed.
CREATE TABLE question_ingestion_audit (
    audit_id CHAR(36) NOT NULL,
    import_run_id CHAR(36) NOT NULL,
    occurrence_id CHAR(36) NULL,
    canonical_question_id CHAR(36) NULL,
    action_type VARCHAR(64) NOT NULL,
    actor_type VARCHAR(32) NOT NULL,
    actor_id VARCHAR(128) NULL,
    decision_json JSON NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (audit_id),
    KEY idx_ingestion_audit_run (import_run_id, created_at),
    KEY idx_ingestion_audit_question (canonical_question_id, created_at),
    CONSTRAINT fk_ingestion_audit_run FOREIGN KEY (import_run_id) REFERENCES import_run(import_run_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Every remote call is traceable and model constrained. No default/fallback provider field exists by design.
CREATE TABLE ingestion_model_call (
    model_call_id CHAR(36) NOT NULL,
    import_run_id CHAR(36) NOT NULL,
    occurrence_id CHAR(36) NULL,
    task_type VARCHAR(64) NOT NULL,
    provider VARCHAR(64) NOT NULL,
    model VARCHAR(128) NOT NULL,
    http_status INT NULL,
    elapsed_ms BIGINT NULL,
    prompt_tokens BIGINT NULL,
    completion_tokens BIGINT NULL,
    total_tokens BIGINT NULL,
    retry_count INT NOT NULL DEFAULT 0,
    schema_valid BOOLEAN NOT NULL DEFAULT FALSE,
    error_type VARCHAR(128) NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (model_call_id),
    KEY idx_ingestion_model_call_run (import_run_id, created_at),
    CONSTRAINT fk_ingestion_model_call_run FOREIGN KEY (import_run_id) REFERENCES import_run(import_run_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
