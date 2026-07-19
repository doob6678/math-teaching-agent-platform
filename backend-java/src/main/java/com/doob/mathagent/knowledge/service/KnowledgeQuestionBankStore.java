package com.doob.mathagent.knowledge.service;

import java.util.List;
import java.util.Optional;
import java.util.Set;

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
     * Saves a knowledge relation.
     *
     * @param record relation record
     * @return saved record
     */
    KnowledgeRelationRecord saveKnowledgeRelation(KnowledgeRelationRecord record);

    /**
     * Finds an active knowledge point by normalized owner/scope/name identity.
     *
     * @param tenantId backend tenant id
     * @param ownerSubjectId owner subject id
     * @param permissionScope effective permission scope
     * @param knowledgePointName display knowledge point name
     * @param chapterPath chapter path
     * @return existing active knowledge point
     */
    Optional<KnowledgePointRecord> findKnowledgePoint(
            String tenantId,
            String ownerSubjectId,
            String permissionScope,
            String knowledgePointName,
            String chapterPath);

    /**
     * Saves a question bank item and its knowledge point links.
     *
     * @param record question bank item record
     * @return saved record
     */
    QuestionBankItemRecord saveQuestion(QuestionBankItemRecord record);

    /**
     * Finds an imported active question by source block identity.
     *
     * @param tenantId backend tenant id
     * @param sourceResourceDocumentId teacher resource document id
     * @param sourceBlockId parsed block id
     * @param sourceChecksum checksum captured from the parsed block
     * @return existing imported question
     */
    Optional<QuestionBankItemRecord> findQuestionBySource(
            String tenantId,
            String sourceResourceDocumentId,
            String sourceBlockId,
            String sourceChecksum);

    /**
     * Archives imported questions for one teacher resource document when their source block/checksum is no longer part
     * of the latest active parse result.
     *
     * @param tenantId backend tenant id
     * @param sourceResourceDocumentId teacher resource document id
     * @param activeSourceKeys current active source block/checksum identities
     * @return archived row count
     */
    int archiveQuestionsBySourceDocumentExcept(
            String tenantId,
            String sourceResourceDocumentId,
            Set<String> activeSourceKeys);

    /**
     * Archives one imported question before a parser upgrade writes its corrected replacement.  This is deliberately
     * archival rather than destructive deletion so a source/checksum audit trail remains available.
     */
    default boolean archiveQuestion(String tenantId, String questionId) {
        return false;
    }

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
     * Lists visible knowledge relations after filtering both endpoint points.
     *
     * @param tenantId backend tenant id
     * @param viewerRole backend viewer role
     * @param viewerSubjectId backend subject id
     * @return visible active relations
     */
    List<KnowledgeRelationRecord> listKnowledgeRelations(String tenantId, String viewerRole, String viewerSubjectId);

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
