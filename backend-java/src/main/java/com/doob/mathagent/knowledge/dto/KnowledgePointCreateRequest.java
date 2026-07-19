package com.doob.mathagent.knowledge.dto;

/**
 * Request body for creating a standard knowledge point.
 *
 * @param knowledgePointName display name
 * @param chapterPath textbook or curriculum chapter path
 * @param permissionScope requested permission scope; backend downgrades unsafe scopes
 * @param sourceSummary source summary such as manual, feishu, or textbook
 */
public record KnowledgePointCreateRequest(
        String knowledgePointName,
        String chapterPath,
        String permissionScope,
        String sourceSummary) {
}
