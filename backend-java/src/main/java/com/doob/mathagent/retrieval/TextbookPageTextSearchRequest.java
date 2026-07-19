package com.doob.mathagent.retrieval;

import java.util.List;

/**
 * Request for local BGE textbook-page semantic coarse retrieval.
 *
 * <p>{@code docIds} is a caller-selected textbook scope, never a keyword-derived guess. This preserves the ability
 * to make document selection deterministic when the user or agent already knows the active textbook edition.</p>
 */
public record TextbookPageTextSearchRequest(String query, int limit, List<String> docIds) {

    public TextbookPageTextSearchRequest {
        query = query == null ? "" : query.strip();
        limit = Math.max(1, limit);
        docIds = docIds == null ? List.of() : docIds.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(String::strip)
                .distinct()
                .toList();
    }
}
