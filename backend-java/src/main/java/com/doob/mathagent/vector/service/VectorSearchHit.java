package com.doob.mathagent.vector.service;

/**
 * One Milvus vector-search hit for a teacher resource block.
 *
 * @param documentId source document id from indexed metadata
 * @param blockId parsed document block id from indexed metadata
 * @param text indexed normalized text returned by Milvus
 * @param score vector similarity score returned by Milvus
 */
public record VectorSearchHit(
        String documentId,
        String blockId,
        String text,
        double score) {
}
