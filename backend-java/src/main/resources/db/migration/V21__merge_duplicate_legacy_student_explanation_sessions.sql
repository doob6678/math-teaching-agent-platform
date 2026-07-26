-- Early versions created a new conversation for repeated first-turn submissions before the browser had received an id.
-- Only exact legacy duplicates without a generated title are merged; titled conversations remain independent by design.
CREATE TEMPORARY TABLE student_explanation_legacy_session_merge AS
SELECT
    MIN(conversation_id) AS canonical_conversation_id,
    tenant_id,
    subject_type,
    subject_id,
    COALESCE(last_question_text, '') AS last_question_text
FROM student_explanation_session
WHERE title IS NULL OR TRIM(title) = ''
GROUP BY tenant_id, subject_type, subject_id, COALESCE(last_question_text, '')
HAVING COUNT(*) > 1;

UPDATE student_explanation_message AS message_row
JOIN student_explanation_session AS source_session
    ON source_session.conversation_id = message_row.conversation_id
JOIN student_explanation_legacy_session_merge AS merge_group
    ON merge_group.tenant_id = source_session.tenant_id
    AND merge_group.subject_type = source_session.subject_type
    AND merge_group.subject_id = source_session.subject_id
    AND merge_group.last_question_text = COALESCE(source_session.last_question_text, '')
SET message_row.conversation_id = merge_group.canonical_conversation_id
WHERE message_row.conversation_id <> merge_group.canonical_conversation_id;

UPDATE student_explanation_session AS canonical_session
JOIN (
    SELECT
        message_row.conversation_id,
        COUNT(*) AS total_messages,
        SUBSTRING_INDEX(
            GROUP_CONCAT(message_row.explanation_id ORDER BY message_row.created_at DESC, message_row.explanation_id DESC),
            ',',
            1
        ) AS last_explanation_id
    FROM student_explanation_message AS message_row
    JOIN student_explanation_legacy_session_merge AS merge_group
        ON merge_group.canonical_conversation_id = message_row.conversation_id
    GROUP BY message_row.conversation_id
) AS merged_messages
    ON merged_messages.conversation_id = canonical_session.conversation_id
SET canonical_session.total_messages = merged_messages.total_messages,
    canonical_session.last_explanation_id = merged_messages.last_explanation_id;

DELETE duplicate_session
FROM student_explanation_session AS duplicate_session
JOIN student_explanation_legacy_session_merge AS merge_group
    ON merge_group.tenant_id = duplicate_session.tenant_id
    AND merge_group.subject_type = duplicate_session.subject_type
    AND merge_group.subject_id = duplicate_session.subject_id
    AND merge_group.last_question_text = COALESCE(duplicate_session.last_question_text, '')
WHERE duplicate_session.conversation_id <> merge_group.canonical_conversation_id;

DROP TEMPORARY TABLE student_explanation_legacy_session_merge;
