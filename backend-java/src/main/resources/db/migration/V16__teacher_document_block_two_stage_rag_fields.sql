ALTER TABLE document_block
    ADD COLUMN source_path VARCHAR(1024) NULL COMMENT 'Stable relative source path used for incremental sync and in-document rerank' AFTER printed_page_no,
    ADD COLUMN block_role VARCHAR(64) NOT NULL DEFAULT 'reference' COMMENT 'Coarse semantic role used by stage-two block rerank' AFTER source_path,
    ADD COLUMN graph_node_ids_json JSON NULL COMMENT 'Normalized knowledge-graph node ids aligned to the block' AFTER formula_refs,
    ADD COLUMN graph_tag_names_json JSON NULL COMMENT 'Normalized graph tag names aligned to the block' AFTER graph_node_ids_json;

CREATE INDEX idx_document_block_doc_role_order
    ON document_block (source_document_id, status, block_role, block_order);

CREATE INDEX idx_document_block_doc_source_path
    ON document_block (source_document_id, status, source_path(255), block_order);
