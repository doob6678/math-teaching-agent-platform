package com.doob.mathagent.knowledge.service;

/**
 * Service-layer record for a directed relation between two knowledge points.
 *
 * @param relationId stable relation id
 * @param tenantId tenant that owns the relation
 * @param sourceKnowledgePointId source knowledge point id
 * @param targetKnowledgePointId target knowledge point id
 * @param relationType relation type such as PREREQUISITE_FOR or RELATED_TO
 * @param evidenceSummary evidence summary explaining why this relation exists
 * @param status active, archived, or draft
 */
public record KnowledgeRelationRecord(
        String relationId,
        String tenantId,
        String sourceKnowledgePointId,
        String targetKnowledgePointId,
        String relationType,
        String evidenceSummary,
        String status) {
}
