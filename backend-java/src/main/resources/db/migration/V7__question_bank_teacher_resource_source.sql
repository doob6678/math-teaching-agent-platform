ALTER TABLE question_bank_item
    ADD COLUMN source_resource_document_id VARCHAR(128) NULL COMMENT 'Teacher resource document id that produced this imported question',
    ADD COLUMN source_block_id VARCHAR(128) NULL COMMENT 'Parsed teacher resource block id that produced this imported question',
    ADD COLUMN source_checksum CHAR(64) NULL COMMENT 'Parsed block checksum captured at import time',
    ADD KEY idx_question_bank_source_block (
        tenant_id,
        source_resource_document_id,
        source_block_id,
        source_checksum,
        status
    );
