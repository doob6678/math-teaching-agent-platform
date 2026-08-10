package com.doob.mathagent.vector.service;

/**
 * One Milvus vector-search hit for a teacher resource block.
 *
 * @param documentId source document id from indexed metadata
 * @param blockId parsed document block id from indexed metadata
 * @param sourcePath file path persisted with the indexed block
 * @param providerItemId stable provider file identity when the source is a Feishu file
 * @param text indexed normalized text returned by Milvus
 * @param score vector similarity score returned by Milvus
 */
public record VectorSearchHit(
        String documentId,
        String blockId,
        String sourcePath,
        String providerItemId,
        String text,
        double score) {

    /** Compatibility constructor for callers built before file-level Milvus metadata was returned. */
    public VectorSearchHit(String documentId, String blockId, String text, double score) {
        this(documentId, blockId, "", "", text, score);
    }
}
