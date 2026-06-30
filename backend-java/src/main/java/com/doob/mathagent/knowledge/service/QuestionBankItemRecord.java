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
        List<String> knowledgePointIds) {
}
