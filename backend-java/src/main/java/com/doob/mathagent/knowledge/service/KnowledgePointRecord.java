package com.doob.mathagent.knowledge.service;

/**
 * Service-layer record for one tenant-scoped knowledge point.
 *
 * @param knowledgePointId stable knowledge point id
 * @param tenantId tenant that owns the point
 * @param ownerSubjectId teacher/admin that created private content
 * @param permissionScope visibility scope such as TEACHER_PRIVATE or MATH_VIP
 * @param knowledgePointName display name
 * @param chapterPath textbook or curriculum chapter path
 * @param status active, archived, or draft
 * @param sourceSummary source summary such as manual, feishu, or textbook
 */
public record KnowledgePointRecord(
        String knowledgePointId,
        String tenantId,
        String ownerSubjectId,
        String permissionScope,
        String knowledgePointName,
        String chapterPath,
        String status,
        String sourceSummary) {
}
