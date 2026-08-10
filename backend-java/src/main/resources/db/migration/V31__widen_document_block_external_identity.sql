-- Feishu relative paths can contain nested folders and long Chinese titles.  The external identity also carries a
-- deterministic segment suffix, so the original VARCHAR(128) truncated real production blocks before retrieval.
-- Keep the searchable prefix index bounded for utf8mb4 while preserving the complete identity in the row.
ALTER TABLE document_block
    DROP INDEX idx_document_block_external,
    MODIFY COLUMN external_block_id VARCHAR(1024) NULL,
    ADD INDEX idx_document_block_external (external_block_id(255));
