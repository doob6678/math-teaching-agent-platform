package com.doob.mathagent.knowledge.service;

import com.doob.mathagent.infrastructure.security.RequestSubject;
import com.doob.mathagent.knowledge.vo.KnowledgeGraphSpineResponse;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * Builds a frontend-safe graph from visible curated knowledge graph rows.
 */
@Service
public class KnowledgeGraphSpineService {

    private static final String VERSION = "v0.1";
    private static final String SOURCE_TAG = "display_spine_v0.1";

    private final KnowledgeQuestionBankStore store;

    /**
     * Creates the display graph service.
     *
     * @param store knowledge graph store
     */
    public KnowledgeGraphSpineService(KnowledgeQuestionBankStore store) {
        this.store = store;
    }

    /**
     * Returns the curated graph visible to the backend-resolved viewer.
     */
    public KnowledgeGraphSpineResponse displaySpine(
            String tenantId,
            String viewerRole,
            String viewerSubjectId) {
        String normalizedTenantId = textOrDefault(tenantId, RequestSubject.DEFAULT_TENANT_ID);
        String normalizedRole = textOrDefault(viewerRole, "student").toLowerCase();
        String normalizedSubjectId = textOrDefault(viewerSubjectId, "");
        List<KnowledgePointRecord> points = store.listKnowledgePoints(
                        normalizedTenantId,
                        normalizedRole,
                        normalizedSubjectId)
                .stream()
                .filter(KnowledgeGraphSpineService::isSpineRow)
                .sorted(Comparator.comparing(KnowledgeGraphSpineService::nodeSortKey)
                        .thenComparing(KnowledgePointRecord::knowledgePointName))
                .toList();
        Set<String> pointIds = points.stream()
                .map(KnowledgePointRecord::knowledgePointId)
                .collect(java.util.stream.Collectors.toSet());
        List<KnowledgeRelationRecord> relations = store.listKnowledgeRelations(
                        normalizedTenantId,
                        normalizedRole,
                        normalizedSubjectId)
                .stream()
                .filter(KnowledgeGraphSpineService::isSpineRelation)
                .filter(relation -> pointIds.contains(relation.sourceKnowledgePointId()))
                .filter(relation -> pointIds.contains(relation.targetKnowledgePointId()))
                .sorted(Comparator.comparing(KnowledgeRelationRecord::relationType)
                        .thenComparing(KnowledgeRelationRecord::relationId))
                .toList();
        List<KnowledgeGraphSpineResponse.Node> nodes = points.stream()
                .map(KnowledgeGraphSpineService::toNode)
                .toList();
        List<KnowledgeGraphSpineResponse.Edge> edges = relations.stream()
                .map(KnowledgeGraphSpineService::toEdge)
                .toList();
        return new KnowledgeGraphSpineResponse(
                VERSION,
                normalizedTenantId,
                normalizedRole,
                nodes.size(),
                edges.size(),
                nodes,
                edges);
    }

    /**
     * Converts one knowledge point record to a display graph node.
     */
    private static KnowledgeGraphSpineResponse.Node toNode(KnowledgePointRecord record) {
        return new KnowledgeGraphSpineResponse.Node(
                record.knowledgePointId(),
                record.knowledgePointName(),
                nodeType(record.sourceSummary()),
                record.chapterPath(),
                record.permissionScope(),
                record.sourceSummary());
    }

    /**
     * Converts one relation record to a display graph edge.
     */
    private static KnowledgeGraphSpineResponse.Edge toEdge(KnowledgeRelationRecord record) {
        return new KnowledgeGraphSpineResponse.Edge(
                record.relationId(),
                record.sourceKnowledgePointId(),
                record.targetKnowledgePointId(),
                record.relationType(),
                record.evidenceSummary());
    }

    /**
     * Returns whether a point came from the curated display spine.
     */
    private static boolean isSpineRow(KnowledgePointRecord record) {
        return record.sourceSummary() != null && record.sourceSummary().contains(SOURCE_TAG);
    }

    /**
     * Returns whether a relation came from the curated display spine.
     */
    private static boolean isSpineRelation(KnowledgeRelationRecord record) {
        return record.evidenceSummary() != null && record.evidenceSummary().contains(SOURCE_TAG);
    }

    /**
     * Produces a stable sort key by node type.
     */
    private static String nodeSortKey(KnowledgePointRecord record) {
        return switch (nodeType(record.sourceSummary())) {
            case "MODULE" -> "1";
            case "TOPIC" -> "2";
            case "METHOD" -> "3";
            default -> "9";
        };
    }

    /**
     * Extracts the node type tag written by the seed service.
     */
    private static String nodeType(String sourceSummary) {
        if (sourceSummary == null || sourceSummary.isBlank()) {
            return "UNKNOWN";
        }
        for (String part : sourceSummary.split(";")) {
            String stripped = part.strip();
            if (stripped.startsWith("nodeType=")) {
                return stripped.substring("nodeType=".length()).strip();
            }
        }
        return "UNKNOWN";
    }

    /**
     * Returns stripped text or default value.
     */
    private static String textOrDefault(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value.strip();
    }
}
