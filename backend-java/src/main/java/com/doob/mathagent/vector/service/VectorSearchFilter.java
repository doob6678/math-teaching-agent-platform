package com.doob.mathagent.vector.service;

import java.util.List;

/**
 * Optional metadata filters for vector search.
 *
 * @param documentIds source document ids allowed by caller visibility and search filters
 * @param permissionScopes resource scopes allowed by caller filters
 */
public record VectorSearchFilter(List<String> documentIds, List<String> permissionScopes) {

    public static final VectorSearchFilter EMPTY = new VectorSearchFilter(List.of(), List.of());

    /**
     * Returns whether no vector metadata filter should be applied.
     */
    public boolean empty() {
        return documentIds.isEmpty() && permissionScopes.isEmpty();
    }
}
