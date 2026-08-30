package com.doob.mathagent.teacher.service;

import com.doob.mathagent.knowledge.service.KnowledgePointRecord;
import com.doob.mathagent.knowledge.service.KnowledgeQuestionBankStore;
import com.doob.mathagent.knowledge.service.KnowledgeRelationRecord;
import com.doob.mathagent.teacher.vo.TeacherResourceDocumentResponse;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Normalizes teacher-resource text against the existing knowledge graph.
 *
 * <p>This service is intentionally retrieval-oriented rather than "tagging-oriented":</p>
 *
 * <ul>
 *     <li>During sync it writes stable graph node ids and canonical graph tag names onto each block so stage one can
 *     narrow candidate documents more reliably.</li>
 *     <li>During search it normalizes the user query into the same graph space, including one-hop parent/child
 *     expansion, so stage two can break ties between sibling blocks inside the right document.</li>
 * </ul>
 *
 * <p>Do not turn this into a manual per-document labeling system. The contract is automatic normalization from the
 * already-managed knowledge graph and question-bank spine.</p>
 */
@Service
public class TeacherResourceGraphAlignmentService {

    private static final int PRIMARY_MATCH_LIMIT = 3;
    private static final int EXPANDED_TAG_LIMIT = 8;

    private final KnowledgeQuestionBankStore store;

    @Autowired
    public TeacherResourceGraphAlignmentService(KnowledgeQuestionBankStore store) {
        this.store = Objects.requireNonNull(store, "store is required");
    }

    private TeacherResourceGraphAlignmentService() {
        this.store = null;
    }

    /**
     * Provides an explicit disabled variant for unit tests that do not need graph behavior.
     */
    public static TeacherResourceGraphAlignmentService disabled() {
        return new TeacherResourceGraphAlignmentService();
    }

    /**
     * Aligns one parsed block to visible knowledge-graph nodes.
     *
     * <p>Primary node ids stay narrow and deterministic; expanded tag names include one-hop parent/child labels so a
     * block about a method can still help document-level recall for its containing topic/module.</p>
     */
    public GraphAlignment alignBlock(
            String tenantId,
            String viewerRole,
            String viewerSubjectId,
            TeacherResourceDocumentResponse document,
            String sourcePath,
            String blockRole,
            String chapter,
            String section,
            String rawText,
            String normalizedText) {
        GraphView view = graphView(tenantId, viewerRole, viewerSubjectId);
        if (view.empty()) {
            return GraphAlignment.EMPTY;
        }
        AlignmentText text = new AlignmentText(
                normalizeText(document == null ? "" : document.title()),
                normalizeText(sourcePath),
                normalizeText(chapter),
                normalizeText(section),
                normalizeText(blockRole),
                normalizeText(rawText),
                normalizeText(normalizedText));
        return toAlignment(matchPoints(view, text, blockRole));
    }

    /**
     * Normalizes a user query into graph ids and expanded names for retrieval scoring.
     */
    public QueryGraphContext alignQuery(
            String tenantId,
            String viewerRole,
            String viewerSubjectId,
            String query) {
        GraphView view = graphView(tenantId, viewerRole, viewerSubjectId);
        if (view.empty()) {
            return QueryGraphContext.EMPTY;
        }
        AlignmentText text = new AlignmentText(
                "",
                "",
                "",
                "",
                "",
                normalizeText(query),
                normalizeText(query));
        List<ScoredPoint> scored = matchPoints(view, text, "reference");
        if (scored.isEmpty()) {
            return QueryGraphContext.EMPTY;
        }
        LinkedHashSet<String> primaryNodeIds = new LinkedHashSet<>();
        LinkedHashSet<String> expandedNodeIds = new LinkedHashSet<>();
        LinkedHashSet<String> primaryTagNames = new LinkedHashSet<>();
        LinkedHashSet<String> expandedTagNames = new LinkedHashSet<>();
        for (ScoredPoint point : scored) {
            primaryNodeIds.add(point.point().id());
            expandedNodeIds.add(point.point().id());
            primaryTagNames.add(point.point().name());
            expandedTagNames.add(point.point().name());
            for (GraphPoint related : view.expandHierarchy(point.point().id())) {
                expandedNodeIds.add(related.id());
                if (expandedTagNames.size() < EXPANDED_TAG_LIMIT) {
                    expandedTagNames.add(related.name());
                }
            }
        }
        return new QueryGraphContext(
                List.copyOf(primaryNodeIds),
                List.copyOf(expandedNodeIds),
                List.copyOf(primaryTagNames),
                List.copyOf(expandedTagNames));
    }

    private GraphAlignment toAlignment(List<ScoredPoint> scored) {
        if (scored.isEmpty()) {
            return GraphAlignment.EMPTY;
        }
        LinkedHashSet<String> nodeIds = new LinkedHashSet<>();
        LinkedHashSet<String> tagNames = new LinkedHashSet<>();
        GraphView view = scored.getFirst().view();
        for (ScoredPoint point : scored) {
            nodeIds.add(point.point().id());
            tagNames.add(point.point().name());
            for (GraphPoint related : view.expandHierarchy(point.point().id())) {
                if (tagNames.size() >= EXPANDED_TAG_LIMIT) {
                    break;
                }
                tagNames.add(related.name());
            }
        }
        return new GraphAlignment(List.copyOf(nodeIds), List.copyOf(tagNames));
    }

    private List<ScoredPoint> matchPoints(GraphView view, AlignmentText text, String blockRole) {
        List<ScoredPoint> scored = new ArrayList<>();
        for (GraphPoint point : view.points()) {
            double score = pointScore(point, text, blockRole);
            if (score >= 5.0d) {
                scored.add(new ScoredPoint(point, score, view));
            }
        }
        return scored.stream()
                .sorted((left, right) -> {
                    int byScore = Double.compare(right.score(), left.score());
                    if (byScore != 0) {
                        return byScore;
                    }
                    int bySpecificity = Integer.compare(right.point().normalizedName().length(), left.point().normalizedName().length());
                    if (bySpecificity != 0) {
                        return bySpecificity;
                    }
                    return left.point().name().compareTo(right.point().name());
                })
                .limit(PRIMARY_MATCH_LIMIT)
                .toList();
    }

    private static double pointScore(GraphPoint point, AlignmentText text, String blockRole) {
        double score = 0;
        score += weightedContains(text.section(), point.normalizedName(), 8.0d);
        score += weightedContains(text.chapter(), point.normalizedName(), 6.0d);
        score += weightedContains(text.metadata(), point.normalizedName(), 5.0d);
        score += weightedContains(text.body(), point.normalizedName(), 4.0d);
        for (String alias : point.aliases()) {
            score += weightedContains(text.section(), alias, 4.0d);
            score += weightedContains(text.chapter(), alias, 3.0d);
            score += weightedContains(text.metadata(), alias, 2.5d);
            score += weightedContains(text.body(), alias, 1.5d);
        }
        String normalizedRole = normalizeText(blockRole);
        if ("METHOD".equals(point.nodeType()) && containsAny(normalizedRole, "method", "boardwork", "template", "tip", "analysis")) {
            score += 1.5d;
        }
        if (("TOPIC".equals(point.nodeType()) || "MODULE".equals(point.nodeType()))
                && containsAny(normalizedRole, "lesson", "question", "reference", "analysis")) {
            score += 0.75d;
        }
        return score;
    }

    private GraphView graphView(String tenantId, String viewerRole, String viewerSubjectId) {
        if (store == null) {
            return GraphView.EMPTY;
        }
        List<KnowledgePointRecord> points = store.listKnowledgePoints(
                textOrDefault(tenantId, "school-a"),
                textOrDefault(viewerRole, "teacher").toLowerCase(Locale.ROOT),
                textOrDefault(viewerSubjectId, ""));
        if (points.isEmpty()) {
            return GraphView.EMPTY;
        }
        Map<String, GraphPoint> pointsById = new LinkedHashMap<>();
        Map<String, LinkedHashSet<String>> hierarchyNeighbors = new LinkedHashMap<>();
        for (KnowledgePointRecord point : points) {
            GraphPoint graphPoint = GraphPoint.from(point);
            pointsById.put(graphPoint.id(), graphPoint);
            hierarchyNeighbors.putIfAbsent(graphPoint.id(), new LinkedHashSet<>());
        }
        for (KnowledgeRelationRecord relation : store.listKnowledgeRelations(
                textOrDefault(tenantId, "school-a"),
                textOrDefault(viewerRole, "teacher").toLowerCase(Locale.ROOT),
                textOrDefault(viewerSubjectId, ""))) {
            if (!isHierarchyRelation(relation.relationType())) {
                continue;
            }
            if (!pointsById.containsKey(relation.sourceKnowledgePointId())
                    || !pointsById.containsKey(relation.targetKnowledgePointId())) {
                continue;
            }
            hierarchyNeighbors.get(relation.sourceKnowledgePointId()).add(relation.targetKnowledgePointId());
            hierarchyNeighbors.get(relation.targetKnowledgePointId()).add(relation.sourceKnowledgePointId());
        }
        return new GraphView(List.copyOf(pointsById.values()), pointsById, hierarchyNeighbors);
    }

    private static boolean isHierarchyRelation(String relationType) {
        String normalized = textOrDefault(relationType, "").toUpperCase(Locale.ROOT);
        return "CONTAINS_TOPIC".equals(normalized) || "METHOD_FOR".equals(normalized);
    }

    private static double weightedContains(String haystack, String needle, double weight) {
        if (needle == null || needle.isBlank() || haystack == null || haystack.isBlank()) {
            return 0.0d;
        }
        return haystack.contains(needle) ? weight : 0.0d;
    }

    private static boolean containsAny(String haystack, String... needles) {
        String normalizedHaystack = normalizeText(haystack);
        for (String needle : needles) {
            if (!needle.isBlank() && normalizedHaystack.contains(normalizeText(needle))) {
                return true;
            }
        }
        return false;
    }

    private static String normalizeText(String value) {
        return textOrDefault(value, "").toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").strip();
    }

    private static String textOrDefault(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value.strip();
    }

    private static List<String> aliasTerms(KnowledgePointRecord point) {
        LinkedHashSet<String> values = new LinkedHashSet<>();
        values.add(normalizeText(point.knowledgePointName()));
        values.add(normalizeText(point.chapterPath()));
        for (String segment : point.chapterPath().split("/")) {
            String normalized = normalizeText(segment);
            if (!normalized.isBlank()) {
                values.add(normalized);
            }
        }
        /*
         * These aliases mirror the curated graph-spine seed so retrieval can normalize queries and block text to the
         * same canonical node even when the seed keeps a more display-friendly node name.
         */
        copyAlias(values, point.knowledgePointName(), "函数基础", "函数概念与表示");
        copyAlias(values, point.knowledgePointName(), "导数隐零点", "隐零点");
        copyAlias(values, point.knowledgePointName(), "解三角形", "正弦定理与余弦定理");
        copyAlias(values, point.knowledgePointName(), "数列基础", "等差等比数列");
        copyAlias(values, point.knowledgePointName(), "数列求和", "数列求通项与求和");
        copyAlias(values, point.knowledgePointName(), "函数图像", "函数图像变换");
        copyAlias(values, point.knowledgePointName(), "导数单调性", "导数研究函数");
        copyAlias(values, point.knowledgePointName(), "参数范围", "导数综合");
        copyAlias(values, point.knowledgePointName(), "立体几何角度距离", "空间向量");
        return values.stream().filter(value -> !value.isBlank()).toList();
    }

    private static void copyAlias(
            Set<String> values,
            String pointName,
            String alias,
            String canonicalName) {
        if (normalizeText(pointName).equals(normalizeText(canonicalName))) {
            values.add(normalizeText(alias));
        }
    }

    public record GraphAlignment(List<String> nodeIds, List<String> tagNames) {
        public static final GraphAlignment EMPTY = new GraphAlignment(List.of(), List.of());
    }

    public record QueryGraphContext(
            List<String> primaryNodeIds,
            List<String> expandedNodeIds,
            List<String> primaryTagNames,
            List<String> expandedTagNames) {

        public static final QueryGraphContext EMPTY =
                new QueryGraphContext(List.of(), List.of(), List.of(), List.of());

        public boolean empty() {
            return primaryNodeIds.isEmpty() && expandedTagNames.isEmpty();
        }
    }

    private record AlignmentText(
            String title,
            String sourcePath,
            String chapter,
            String section,
            String blockRole,
            String rawText,
            String normalizedText) {

        private String metadata() {
            return String.join(" ", title, sourcePath, chapter, section, blockRole).strip();
        }

        private String body() {
            return String.join(" ", rawText, normalizedText).strip();
        }
    }

    private record ScoredPoint(GraphPoint point, double score, GraphView view) {
    }

    private record GraphPoint(
            String id,
            String name,
            String normalizedName,
            String nodeType,
            List<String> aliases) {

        private static GraphPoint from(KnowledgePointRecord point) {
            return new GraphPoint(
                    point.knowledgePointId(),
                    point.knowledgePointName(),
                    normalizeText(point.knowledgePointName()),
                    nodeType(point.sourceSummary()),
                    aliasTerms(point));
        }

        private static String nodeType(String sourceSummary) {
            String summary = textOrDefault(sourceSummary, "");
            for (String part : summary.split(";")) {
                String stripped = part.strip();
                if (stripped.startsWith("nodeType=")) {
                    return stripped.substring("nodeType=".length()).strip().toUpperCase(Locale.ROOT);
                }
            }
            return "UNKNOWN";
        }
    }

    private record GraphView(
            List<GraphPoint> points,
            Map<String, GraphPoint> pointsById,
            Map<String, LinkedHashSet<String>> hierarchyNeighbors) {

        private static final GraphView EMPTY =
                new GraphView(List.of(), Map.of(), Map.of());

        private boolean empty() {
            return points.isEmpty();
        }

        private List<GraphPoint> expandHierarchy(String nodeId) {
            LinkedHashSet<String> neighbors = hierarchyNeighbors.getOrDefault(nodeId, new LinkedHashSet<>());
            List<GraphPoint> expanded = new ArrayList<>();
            for (String neighborId : neighbors) {
                GraphPoint point = pointsById.get(neighborId);
                if (point != null) {
                    expanded.add(point);
                }
            }
            return expanded;
        }
    }
}
