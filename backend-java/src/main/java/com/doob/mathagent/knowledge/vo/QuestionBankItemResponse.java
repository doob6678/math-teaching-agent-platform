package com.doob.mathagent.knowledge.vo;

import java.util.List;

/**
 * Question bank item returned to teacher/admin consoles.
 *
 * @param questionId stable question id
 * @param tenantId backend tenant id
 * @param ownerSubjectId creator subject id
 * @param permissionScope effective permission scope
 * @param questionTitle compact title
 * @param questionText full question text
 * @param answerJson structured answer JSON
 * @param difficulty difficulty label
 * @param status active, draft, or archived
 * @param sourceResourceDocumentId teacher resource document id that produced the question
 * @param sourceBlockId parsed source block id that produced the question
 * @param sourceChecksum source block checksum captured at import time
 * @param knowledgePointIds linked knowledge point ids
 */
public record QuestionBankItemResponse(
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
     * Builds a response for manually created questions without source metadata.
     */
    public QuestionBankItemResponse(
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
