ALTER TABLE teaching_human_feedback
    ADD COLUMN review_context_json JSON NULL AFTER comment;
