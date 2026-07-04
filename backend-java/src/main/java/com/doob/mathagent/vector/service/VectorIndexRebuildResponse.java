package com.doob.mathagent.vector.service;

/**
 * Result of one vector index rebuild attempt.
 */
public record VectorIndexRebuildResponse(
        String status,
        String documentId,
        String collectionName,
        int blockCount,
        int embeddedCount,
        int upsertedCount,
        String embeddingModel,
        int promptTokens,
        String message) {
}
