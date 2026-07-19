package com.doob.mathagent.knowledge.service;

import java.util.List;

/**
 * Service-layer record for one question bank item.
 *
 * @param questionId stable question id
 * @param tenantId tenant that owns the question
 * @param ownerSubjectId teacher/admin that created private content
 * @param permissionScope visibility scope such as TEACHER_PRIVATE or MATH_VIP
 * @param questionTitle compact display title
 * @param questionText full question text
 * @param answerJson structured answer and explanation JSON
 * @param difficulty difficulty label such as easy, medium, or hard
 * @param status active, archived, or draft
 * @param sourceResourceDocumentId teacher resource document id that produced this question
 * @param sourceBlockId parsed teacher resource block id that produced this question
 * @param sourceChecksum checksum of the parsed block at import time
 * @param knowledgePointIds linked knowledge point ids
 */
public record QuestionBankItemRecord(
        String questionId,
        String tenantId,
        String ownerSubjectId,
        String permissionScope,
        String questionTitle,
        String questionText,
        String answerJson,
        String difficulty,
        String status,
        String sourceResourceDocumentId,
        String sourceBlockId,
        String sourceChecksum,
        List<String> knowledgePointIds) {

    /**
     * Builds a manual question item that does not have teacher-resource source metadata.
     */
    public QuestionBankItemRecord(
            String questionId,
            String tenantId,
            String ownerSubjectId,
            String permissionScope,
            String questionTitle,
            String questionText,
            String answerJson,
            String difficulty,
            String status,
            List<String> knowledgePointIds) {
        this(
                questionId,
                tenantId,
                ownerSubjectId,
                permissionScope,
                questionTitle,
                questionText,
                answerJson,
                difficulty,
                status,
                null,
                null,
                null,
                knowledgePointIds);
    }
}
