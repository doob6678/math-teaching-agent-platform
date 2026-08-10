-- Optimistic revision prevents parallel Agent Workers from replacing a sibling's newer workflow snapshot.
ALTER TABLE multi_agent_writing_workflow
    ADD COLUMN revision BIGINT NOT NULL DEFAULT 0 AFTER metadata_json;
