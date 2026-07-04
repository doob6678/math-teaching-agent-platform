package com.doob.mathagent.vector.service;

/**
 * Runtime vector index status without exposing credentials.
 */
public record VectorIndexStatusResponse(
        boolean enabled,
        boolean configured,
        String collectionName,
        int dimension,
        String embeddingModel,
        String milvusUri,
        String collectionState,
        String indexState,
        String loadState,
        long rowCount,
        String status) {
}
