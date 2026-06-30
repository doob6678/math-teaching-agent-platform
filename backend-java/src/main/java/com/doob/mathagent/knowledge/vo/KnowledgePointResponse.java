package com.doob.mathagent.knowledge.vo;

/**
 * Knowledge point row returned to teacher/admin consoles.
 *
 * @param knowledgePointId stable knowledge point id
 * @param tenantId backend tenant id
 * @param ownerSubjectId creator subject id
 * @param permissionScope effective permission scope
 * @param knowledgePointName display name
 * @param chapterPath curriculum chapter path
 * @param status active, draft, or archived
 * @param sourceSummary source summary
 */
public record KnowledgePointResponse(
        String knowledgePointId,
        String tenantId,
        String ownerSubjectId,
        String permissionScope,
        String knowledgePointName,
        String chapterPath,
        String status,
        String sourceSummary) {
}
