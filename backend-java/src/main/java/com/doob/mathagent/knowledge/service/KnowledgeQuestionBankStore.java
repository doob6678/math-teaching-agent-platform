package com.doob.mathagent.knowledge.service;

import java.util.List;

/**
 * Store abstraction for standard knowledge points and linked question bank items.
 */
public interface KnowledgeQuestionBankStore {

    /**
     * Saves a knowledge point.
     *
     * @param record knowledge point record
     * @return saved record
     */
    KnowledgePointRecord saveKnowledgePoint(KnowledgePointRecord record);

    /**
     * Saves a question bank item and its knowledge point links.
     *
     * @param record question bank item record
     * @return saved record
     */
    QuestionBankItemRecord saveQuestion(QuestionBankItemRecord record);

    /**
     * Lists visible knowledge points after backend role and owner filtering.
     *
     * @param tenantId backend tenant id
     * @param viewerRole backend viewer role
     * @param viewerSubjectId backend subject id
     * @return visible active knowledge points
     */
    List<KnowledgePointRecord> listKnowledgePoints(String tenantId, String viewerRole, String viewerSubjectId);

    /**
     * Searches visible question bank items by keyword.
     *
     * @param tenantId backend tenant id
     * @param viewerRole backend viewer role
     * @param viewerSubjectId backend subject id
     * @param query keyword query
     * @param limit maximum rows
     * @return visible active questions
     */
    List<QuestionBankItemRecord> searchQuestions(
            String tenantId,
            String viewerRole,
            String viewerSubjectId,
            String query,
            int limit);
}
