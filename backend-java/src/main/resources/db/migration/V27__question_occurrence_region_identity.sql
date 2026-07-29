-- V26 used page range and printed number only. A multi-column exam page can legitimately repeat a sub-question
-- number in separate visual regions, so this migration adds the region fingerprint required by the source identity.
ALTER TABLE question_source_occurrence
    ADD COLUMN region_fingerprint CHAR(64) NOT NULL DEFAULT '' AFTER region_json,
    -- MySQL permits duplicate NULL values in a unique key; unknown printed numbers therefore normalize to empty text.
    MODIFY original_question_number VARCHAR(64) NOT NULL DEFAULT '';

ALTER TABLE question_source_occurrence
    DROP INDEX uq_question_occurrence_location,
    ADD UNIQUE KEY uq_question_occurrence_location (
        source_file_id,
        page_start,
        page_end,
        region_fingerprint,
        original_question_number
    );
