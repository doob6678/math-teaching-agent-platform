package com.doob.mathagent.knowledge.vo;

/**
 * Knowledge graph relation returned to teacher/admin consoles.
 *
 * @param relationId stable relation id
 * @param tenantId backend tenant id
 * @param sourceKnowledgePointId source knowledge point id
 * @param targetKnowledgePointId target knowledge point id
 * @param relationType relation type such as PREREQUISITE_FOR or RELATED_TO
 * @param evidenceSummary evidence summary explaining the edge
 * @param status active, draft, or archived
 */
public record KnowledgeRelationResponse(
        String relationId,
        String tenantId,
        String sourceKnowledgePointId,
        String targetKnowledgePointId,
        String relationType,
        String evidenceSummary,
        String status) {
}
