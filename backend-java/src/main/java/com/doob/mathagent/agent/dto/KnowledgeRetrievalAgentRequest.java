package com.doob.mathagent.agent.dto;

/** Input contract for the marketplace knowledge-retrieval specialist. */
public record KnowledgeRetrievalAgentRequest(String query, int limit) {
    public KnowledgeRetrievalAgentRequest normalize() {
        String normalizedQuery = query == null ? "" : query.strip();
        if (normalizedQuery.isBlank()) {
            throw new IllegalArgumentException("query is required for KnowledgeRetrievalAgent");
        }
        return new KnowledgeRetrievalAgentRequest(normalizedQuery, Math.max(1, Math.min(limit <= 0 ? 8 : limit, 20)));
    }
}
