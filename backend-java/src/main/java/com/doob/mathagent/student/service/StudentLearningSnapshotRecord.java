package com.doob.mathagent.student.service;

/**
 * Persisted student learning snapshot loaded from MySQL.
 *
 * @param snapshotId stable snapshot id
 * @param tenantId tenant id used for data isolation
 * @param studentId student id that owns the snapshot
 * @param gradeName grade name stored with the snapshot
 * @param knowledgeProgressJson JSON array of knowledge progress items
 * @param knowledgeGraphJson JSON object containing graph nodes, edges, and source summary
 * @param weakPointsJson JSON array of weak points
 * @param recentQuestionsJson JSON array of recoverable question records
 * @param scoreTrendJson JSON array of exam score points
 * @param resourceScopesJson JSON array of visible resource scopes
 * @param sourceSummary source summary for audit and fallback graph metadata
 */
public record StudentLearningSnapshotRecord(
        String snapshotId,
        String tenantId,
        String studentId,
        String gradeName,
        String knowledgeProgressJson,
        String knowledgeGraphJson,
        String weakPointsJson,
        String recentQuestionsJson,
        String scoreTrendJson,
        String resourceScopesJson,
        String sourceSummary) {
}
