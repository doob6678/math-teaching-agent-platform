package com.doob.mathagent.vector.service;

/**
 * One Milvus vector-search hit for a teacher resource block.
 *
 * @param rootDocumentId durable ROOT resource identity
 * @param fileDocumentId durable physical FILE document identity
 * @param documentId indexed document id; FILE rows use the fileDocumentId
 * @param blockId parsed document block id from indexed metadata
 * @param sourcePath file path persisted with the indexed block
 * @param providerItemId stable provider file identity when the source is a Feishu file
 * @param blockOrder order inside the physical FILE document
 * @param splitFingerprint parser split version used for this block
 * @param text indexed normalized text returned by Milvus
 * @param score vector similarity score returned by Milvus
 */
public record VectorSearchHit(
        String rootDocumentId,
        String fileDocumentId,
        String documentId,
        String blockId,
        String sourcePath,
        String providerItemId,
        int blockOrder,
        String splitFingerprint,
        String text,
        double score) {

    /** Compatibility constructor for callers built before ROOT/FILE metadata was returned. */
    public VectorSearchHit(
            String documentId,
            String blockId,
            String sourcePath,
            String providerItemId,
            String text,
            double score) {
        this("", documentId, documentId, blockId, sourcePath, providerItemId, 0, "", text, score);
    }

    /** Compatibility constructor for local lexical test fixtures. */
    public VectorSearchHit(String documentId, String blockId, String text, double score) {
        this("", documentId, documentId, blockId, "", "", 0, "", text, score);
    }
}
