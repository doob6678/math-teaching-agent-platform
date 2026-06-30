package com.doob.mathagent.student.vo;

import java.util.List;

/**
 * Student learning dashboard response.
 *
 * @param tenantId tenant id that owns the dashboard data
 * @param studentId student id whose learning data is shown
 * @param viewerRole role of the current viewer
 * @param viewerSubjectId current viewer subject id
 * @param isAdminView whether this response was opened by an admin or teacher for another student
 * @param knowledgeProgress knowledge graph progress cards
 * @param weakPoints weak knowledge points inferred from exercises, exams, and teaching tasks
 * @param recentQuestions recoverable historical question records
 * @param scoreTrend exam score trend points
 * @param resourceScopes resource scopes currently allowed for this student
 * @param knowledgeGraph graph nodes and edges used by the frontend mastery visualization
 */
public record StudentDashboardResponse(
        String tenantId,
        String studentId,
        String viewerRole,
        String viewerSubjectId,
        boolean isAdminView,
        List<KnowledgeProgress> knowledgeProgress,
        List<WeakPoint> weakPoints,
        List<RecentQuestion> recentQuestions,
        List<ScorePoint> scoreTrend,
        List<ResourceScope> resourceScopes,
        KnowledgeGraph knowledgeGraph) {

    /**
     * Knowledge graph progress item.
     *
     * @param knowledgePointId knowledge point id
     * @param knowledgePointName knowledge point display name
     * @param textbookAnchor textbook chapter/page anchor
     * @param feishuDocUrl Feishu knowledge document URL
     * @param progressPercent completion percent, 0 to 100
     */
    public record KnowledgeProgress(
            String knowledgePointId,
            String knowledgePointName,
            String textbookAnchor,
            String feishuDocUrl,
            int progressPercent) {
    }

    /**
     * Student weak point item.
     *
     * @param knowledgePointId knowledge point id
     * @param knowledgePointName knowledge point display name
     * @param weaknessLevel weakness level from 1 to 5
     * @param evidenceSummary evidence summary from questions or exams
     */
    public record WeakPoint(
            String knowledgePointId,
            String knowledgePointName,
            int weaknessLevel,
            String evidenceSummary) {
    }

    /**
     * Recoverable recent question record.
     *
     * @param recordId question record id
     * @param sourceType source type such as teaching_task, uploaded_image, or exam_paper
     * @param questionTitle question title
     * @param knowledgePointName linked knowledge point
     * @param status current workflow status
     */
    public record RecentQuestion(
            String recordId,
            String sourceType,
            String questionTitle,
            String knowledgePointName,
            String status) {
    }

    /**
     * Exam score trend point.
     *
     * @param examName exam display name
     * @param score exam score
     * @param rankInGrade grade rank
     * @param extractedWeakPointCount weak point count extracted from the paper
     */
    public record ScorePoint(
            String examName,
            int score,
            int rankInGrade,
            int extractedWeakPointCount) {
    }

    /**
     * Resource scope available to the student.
     *
     * @param scopeCode scope code used by permission checks
     * @param scopeName display name
     * @param accessPolicy access policy summary
     */
    public record ResourceScope(
            String scopeCode,
            String scopeName,
            String accessPolicy) {
    }

    /**
     * Student knowledge graph assembled for one dashboard response.
     *
     * @param nodes visible knowledge point nodes with mastery and evidence
     * @param edges visible prerequisite or related-topic edges
     * @param generatedFrom source summary used for audit and frontend display
     */
    public record KnowledgeGraph(
            List<KnowledgeGraphNode> nodes,
            List<KnowledgeGraphEdge> edges,
            String generatedFrom) {
    }

    /**
     * Knowledge graph node with a stable id, progress, and evidence links.
     *
     * @param knowledgePointId stable knowledge point id used by graph edges
     * @param knowledgePointName knowledge point display name
     * @param chapterPath textbook chapter path for grouping
     * @param masteryPercent student mastery percent from the progress model
     * @param riskLevel risk level derived from weak point evidence
     * @param evidenceLinks textbook, Feishu, and parsed-resource links visible to the viewer
     */
    public record KnowledgeGraphNode(
            String knowledgePointId,
            String knowledgePointName,
            String chapterPath,
            int masteryPercent,
            String riskLevel,
            List<KnowledgeEvidenceLink> evidenceLinks) {
    }

    /**
     * Directed relation between two knowledge points.
     *
     * @param edgeId stable graph edge id
     * @param sourceKnowledgePointId source knowledge point id
     * @param targetKnowledgePointId target knowledge point id
     * @param relationType relation type such as PREREQUISITE_FOR or RELATED_TO
     * @param evidenceSummary human-readable evidence behind the relation
     */
    public record KnowledgeGraphEdge(
            String edgeId,
            String sourceKnowledgePointId,
            String targetKnowledgePointId,
            String relationType,
            String evidenceSummary) {
    }

    /**
     * Evidence link for one graph node.
     *
     * @param sourceType source type such as textbook, feishu, or teacher_resource
     * @param title link title displayed in the frontend
     * @param url stable source URL or local retrieval path
     * @param permissionScope scope required to open the evidence
     */
    public record KnowledgeEvidenceLink(
            String sourceType,
            String title,
            String url,
            String permissionScope) {
    }
}
