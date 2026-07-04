package com.doob.mathagent.student.service;

import com.doob.mathagent.knowledge.vo.KnowledgeGraphSpineResponse;
import com.doob.mathagent.student.vo.StudentDashboardResponse;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Assembles a student-visible knowledge graph from progress records and source evidence.
 */
final class StudentKnowledgeGraphAssembler {

    private StudentKnowledgeGraphAssembler() {
    }

    /**
     * Builds a graph-ready view from progress, weak point, textbook, and Feishu evidence.
     *
     * @param progress current knowledge progress records
     * @param weakPoints current weak point evidence
     * @param viewerRole backend-resolved viewer role
     * @return graph response for frontend visualization
     */
    static StudentDashboardResponse.KnowledgeGraph knowledgeGraph(
            List<StudentDashboardResponse.KnowledgeProgress> progress,
            List<StudentDashboardResponse.WeakPoint> weakPoints,
            String viewerRole,
            KnowledgeGraphSpineResponse spine) {
        Map<String, Integer> weaknessByKnowledgeId = new HashMap<>();
        for (StudentDashboardResponse.WeakPoint weakPoint : weakPoints) {
            weaknessByKnowledgeId.put(weakPoint.knowledgePointId(), weakPoint.weaknessLevel());
        }
        Set<String> visibleNodeIds = new HashSet<>();
        List<StudentDashboardResponse.KnowledgeGraphNode> nodes = progress.stream()
                .map(item -> {
                    visibleNodeIds.add(item.knowledgePointId());
                    return new StudentDashboardResponse.KnowledgeGraphNode(
                            item.knowledgePointId(),
                            item.knowledgePointName(),
                            chapterPath(item, spine),
                            item.progressPercent(),
                            riskLevel(item.progressPercent(), weaknessByKnowledgeId.get(item.knowledgePointId())),
                            evidenceLinks(item, viewerRole));
                })
                .toList();
        List<StudentDashboardResponse.KnowledgeGraphEdge> edges = visibleEdges(visibleNodeIds, spine);
        return new StudentDashboardResponse.KnowledgeGraph(
                nodes,
                edges,
                generatedFrom(spine));
    }

    /**
     * Builds a graph when only progress records are available and no curated relation graph is visible.
     */
    static StudentDashboardResponse.KnowledgeGraph knowledgeGraph(
            List<StudentDashboardResponse.KnowledgeProgress> progress,
            List<StudentDashboardResponse.WeakPoint> weakPoints,
            String viewerRole) {
        return knowledgeGraph(progress, weakPoints, viewerRole, null);
    }

    /**
     * Returns visible curated edges only when both endpoints exist in the current student graph.
     */
    private static List<StudentDashboardResponse.KnowledgeGraphEdge> visibleEdges(
            Set<String> visibleNodeIds,
            KnowledgeGraphSpineResponse spine) {
        if (spine == null || spine.edges() == null || spine.edges().isEmpty()) {
            return List.of();
        }
        return spine.edges().stream()
                .filter(edge -> visibleNodeIds.contains(edge.source()))
                .filter(edge -> visibleNodeIds.contains(edge.target()))
                .map(edge -> new StudentDashboardResponse.KnowledgeGraphEdge(
                        edge.id(),
                        edge.source(),
                        edge.target(),
                        edge.relationType(),
                        edge.evidenceSummary()))
                .toList();
    }

    /**
     * Builds a graph from backend-owned progress signals without inventing textbook or Feishu relations.
     *
     * @param progress current knowledge progress records
     * @param weakPoints current weak point evidence
     * @param generatedFrom source summary used for audit
     * @return graph with real nodes and no inferred relation edges
     */
    static StudentDashboardResponse.KnowledgeGraph knowledgeGraphFromProgressOnly(
            List<StudentDashboardResponse.KnowledgeProgress> progress,
            List<StudentDashboardResponse.WeakPoint> weakPoints,
            String generatedFrom) {
        Map<String, Integer> weaknessByKnowledgeId = new HashMap<>();
        for (StudentDashboardResponse.WeakPoint weakPoint : weakPoints) {
            weaknessByKnowledgeId.put(weakPoint.knowledgePointId(), weakPoint.weaknessLevel());
        }
        List<StudentDashboardResponse.KnowledgeGraphNode> nodes = progress.stream()
                .map(item -> new StudentDashboardResponse.KnowledgeGraphNode(
                        item.knowledgePointId(),
                        item.knowledgePointName(),
                        item.textbookAnchor(),
                        item.progressPercent(),
                        riskLevel(item.progressPercent(), weaknessByKnowledgeId.get(item.knowledgePointId())),
                        List.of()))
                .toList();
        return new StudentDashboardResponse.KnowledgeGraph(nodes, List.of(), generatedFrom);
    }

    /**
     * Converts progress and weak point evidence into a simple risk label.
     *
     * @param masteryPercent mastery percent from the dashboard progress model
     * @param weaknessLevel optional weakness level
     * @return risk label for graph styling
     */
    private static String riskLevel(int masteryPercent, Integer weaknessLevel) {
        int normalizedWeakness = weaknessLevel == null ? 0 : weaknessLevel;
        if (masteryPercent < 60 || normalizedWeakness >= 4) {
            return "high";
        }
        if (masteryPercent < 80 || normalizedWeakness >= 2) {
            return "medium";
        }
        return "low";
    }

    /**
     * Builds evidence links visible for one knowledge node.
     *
     * @param progress knowledge progress source item
     * @param viewerRole backend-resolved viewer role
     * @return evidence links filtered by viewer role
     */
    private static List<StudentDashboardResponse.KnowledgeEvidenceLink> evidenceLinks(
            StudentDashboardResponse.KnowledgeProgress progress,
            String viewerRole) {
        StudentDashboardResponse.KnowledgeEvidenceLink textbook =
                new StudentDashboardResponse.KnowledgeEvidenceLink(
                        "textbook",
                        progress.textbookAnchor(),
                        "/api/textbooks/search?query=" + progress.knowledgePointId(),
                        "PUBLIC_TEXTBOOK");
        StudentDashboardResponse.KnowledgeEvidenceLink feishu =
                new StudentDashboardResponse.KnowledgeEvidenceLink(
                        "feishu",
                        progress.knowledgePointName(),
                        progress.feishuDocUrl(),
                        "MATH_VIP");
        if ("admin".equals(viewerRole) || "teacher".equals(viewerRole)) {
            return List.of(
                    textbook,
                    feishu,
                    new StudentDashboardResponse.KnowledgeEvidenceLink(
                            "teacher_resource",
                            "Teacher parsed resource blocks",
                            "/api/teacher/resources/search?query=" + progress.knowledgePointId(),
                            "TEACHER_PRIVATE"));
        }
        return List.of(textbook, feishu);
    }

    /**
     * Returns chapter path from the curated spine when visible, otherwise keeps the progress anchor.
     */
    private static String chapterPath(
            StudentDashboardResponse.KnowledgeProgress progress,
            KnowledgeGraphSpineResponse spine) {
        if (spine == null || spine.nodes() == null) {
            return progress.textbookAnchor();
        }
        return spine.nodes().stream()
                .filter(node -> progress.knowledgePointId().equals(node.id()))
                .map(KnowledgeGraphSpineResponse.Node::chapterPath)
                .findFirst()
                .filter(value -> value != null && !value.isBlank())
                .orElse(progress.textbookAnchor());
    }

    /**
     * Builds an audit tag that reflects whether curated graph data was used.
     */
    private static String generatedFrom(KnowledgeGraphSpineResponse spine) {
        if (spine == null) {
            return "dashboard_progress+weak_points";
        }
        return "dashboard_progress+weak_points+" + spine.version();
    }
}
