package com.doob.mathagent.vector.service;

import java.util.List;

/**
 * Optional metadata filters for vector search.
 *
 * @param tenantIds tenant ids enforced directly by Milvus metadata
 * @param documentIds explicitly requested source document ids, enforced directly by Milvus metadata
 * @param permissionScopes explicitly requested resource scopes, enforced directly by Milvus metadata
 * @param sourceTypes persisted resource categories admitted by the MySQL visibility query
 * @param physicalFilesOnly restricts vector recall to persisted FILE documents
 */
public record VectorSearchFilter(
        List<String> tenantIds,
        List<String> documentIds,
        List<String> permissionScopes,
        List<String> sourceTypes,
        boolean physicalFilesOnly) {

    public static final VectorSearchFilter EMPTY = new VectorSearchFilter(List.of(), List.of(), List.of(), List.of(), false);

    /** Retains the former call shape for non-file vector callers. */
    public VectorSearchFilter(
            List<String> tenantIds,
            List<String> documentIds,
            List<String> permissionScopes,
            List<String> sourceTypes) {
        this(tenantIds, documentIds, permissionScopes, sourceTypes, false);
    }

    /**
     * Retains the former call shape for non-tenant-specific compatibility callers.
     *
     * <p>Teacher-resource search always uses the four-field constructor. This overload exists only so unrelated
     * vector callers can migrate without accidentally widening an already explicit filter.</p>
     */
    public VectorSearchFilter(List<String> documentIds, List<String> permissionScopes) {
        this(List.of(), documentIds, permissionScopes, List.of(), false);
    }

    /** Returns whether no vector metadata filter should be applied. */
    public boolean empty() {
        return tenantIds.isEmpty()
                && documentIds.isEmpty()
                && permissionScopes.isEmpty()
                && sourceTypes.isEmpty()
                && !physicalFilesOnly;
    }
}
