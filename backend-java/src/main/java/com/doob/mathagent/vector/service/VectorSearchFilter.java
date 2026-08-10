package com.doob.mathagent.vector.service;

import java.util.List;

/**
 * Optional metadata filters for vector search.
 *
 * @param tenantIds tenant ids admitted by the MySQL visibility query
 * @param documentIds source document ids admitted by the MySQL visibility query
 * @param permissionScopes resource scopes admitted by the MySQL visibility query
 * @param sourceTypes persisted resource categories admitted by the MySQL visibility query
 */
public record VectorSearchFilter(
        List<String> tenantIds,
        List<String> documentIds,
        List<String> permissionScopes,
        List<String> sourceTypes) {

    public static final VectorSearchFilter EMPTY = new VectorSearchFilter(List.of(), List.of(), List.of(), List.of());

    /**
     * Retains the former call shape for non-tenant-specific compatibility callers.
     *
     * <p>Teacher-resource search always uses the four-field constructor. This overload exists only so unrelated
     * vector callers can migrate without accidentally widening an already explicit filter.</p>
     */
    public VectorSearchFilter(List<String> documentIds, List<String> permissionScopes) {
        this(List.of(), documentIds, permissionScopes, List.of());
    }

    /**
     * Returns whether no vector metadata filter should be applied.
     */
    public boolean empty() {
        return tenantIds.isEmpty()
                && documentIds.isEmpty()
                && permissionScopes.isEmpty()
                && sourceTypes.isEmpty();
    }
}
