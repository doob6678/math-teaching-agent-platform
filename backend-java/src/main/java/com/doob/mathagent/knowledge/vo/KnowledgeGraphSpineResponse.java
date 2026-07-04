package com.doob.mathagent.knowledge.vo;

import java.util.List;

/**
 * Frontend-ready display graph for the curated high-school math spine.
 *
 * @param version curated graph version
 * @param tenantId backend tenant id used for visibility filtering
 * @param viewerRole backend-resolved viewer role
 * @param nodeCount number of visible graph nodes
 * @param edgeCount number of visible graph edges
 * @param nodes visible graph nodes
 * @param edges visible graph edges
 */
public record KnowledgeGraphSpineResponse(
        String version,
        String tenantId,
        String viewerRole,
        int nodeCount,
        int edgeCount,
        List<Node> nodes,
        List<Edge> edges) {

    /**
     * One display graph node.
     *
     * @param id knowledge point id
     * @param label display label
     * @param nodeType MODULE, TOPIC, or METHOD
     * @param chapterPath source chapter path
     * @param permissionScope data permission scope
     * @param sourceSummary short source and meaning summary
     */
    public record Node(
            String id,
            String label,
            String nodeType,
            String chapterPath,
            String permissionScope,
            String sourceSummary) {
    }

    /**
     * One display graph edge.
     *
     * @param id relation id
     * @param source source node id
     * @param target target node id
     * @param relationType relation type for frontend styling
     * @param evidenceSummary short edge evidence summary
     */
    public record Edge(
            String id,
            String source,
            String target,
            String relationType,
            String evidenceSummary) {
    }
}
