package com.doob.mathagent.agent.vo;

import java.util.List;

/** A compact, source-traceable artifact produced by KnowledgeRetrievalAgent. */
public record KnowledgeEvidencePackResponse(String artifactType, String queryId, String query, List<Item> items) {
    /** Evidence is limited to safe source anchors and snippets, never a local corpus path. */
    public record Item(String sourceUri, String title, String snippet, double score) { }
}
