package com.doob.mathagent.teacher.vo;

import java.util.List;

/**
 * Search response for parsed teacher resource document blocks.
 *
 * @param queryId server-generated query id for audit correlation
 * @param query normalized user query
 * @param limit maximum requested hit count after server-side clamping
 * @param retrievalMode retrieval implementation used by this response
 * @param hitCount returned hit count
 * @param hits matched document blocks visible to the backend subject
 */
public record TeacherResourceBlockSearchResponse(
        String queryId,
        String query,
        int limit,
        String retrievalMode,
        int hitCount,
        List<Hit> hits) {

    /**
     * Single parsed teacher document block search hit.
     *
     * @param documentId source document id
     * @param documentTitle source document title
     * @param sourceType source document type such as feishu, local_path, public_textbook, qq_bundle, gaokao, or mock_exam
     * @param permissionScope source document permission scope
     * @param blockId parsed block id used by citations
     * @param blockType normalized block type
     * @param blockOrder order inside the source document
     * @param chapter inferred chapter heading
     * @param section inferred section heading
     * @param pageNo extracted source page number
     * @param sourcePath relative source path inside the document package or staging folder
     * @param blockRole coarse semantic role used by stage-two rerank
     * @param graphTags normalized graph tag names aligned to the block
     * @param evidenceBlockIds primary block plus expanded neighbor evidence window block ids
     * @param evidenceText merged evidence text after neighbor expansion
     * @param snippet compact text snippet around the match
     * @param score lexical relevance score
     * @param imageAssetIds opaque asset ids parsed from block imageRefs
     * @param assetRefs visible backend-controlled asset references
     */
    public record Hit(
            String documentId,
            String documentTitle,
            String sourceType,
            String permissionScope,
            String blockId,
            String blockType,
            int blockOrder,
            String chapter,
            String section,
            Integer pageNo,
            String sourcePath,
            String blockRole,
            List<String> graphTags,
            List<String> evidenceBlockIds,
            String evidenceText,
            String snippet,
            double score,
            List<String> imageAssetIds,
            List<AssetRef> assetRefs) {

        /**
         * Backward-compatible constructor for older callers that only return the original hit fields.
         */
        public Hit(
                String documentId,
                String documentTitle,
                String permissionScope,
                String blockId,
                String blockType,
                int blockOrder,
                String chapter,
                String section,
                Integer pageNo,
                String snippet,
                double score) {
            this(
                    documentId,
                    documentTitle,
                    "",
                    permissionScope,
                    blockId,
                    blockType,
                    blockOrder,
                    chapter,
                    section,
                    pageNo,
                    "",
                    "reference",
                    List.of(),
                    List.of(blockId),
                    snippet,
                    snippet,
                    score,
                    List.of(),
                    List.of());
        }

        public Hit withAssetRefs(List<AssetRef> visibleAssetRefs) {
            return new Hit(
                    documentId,
                    documentTitle,
                    sourceType,
                    permissionScope,
                    blockId,
                    blockType,
                    blockOrder,
                    chapter,
                    section,
                    pageNo,
                    sourcePath,
                    blockRole,
                    graphTags,
                    evidenceBlockIds,
                    evidenceText,
                    snippet,
                    score,
                    imageAssetIds,
                    visibleAssetRefs == null ? List.of() : List.copyOf(visibleAssetRefs));
        }
    }

    public record AssetRef(
            String assetId,
            String assetUri,
            String mimeType,
            String fileName,
            String sourcePath,
            Integer pageNo) {
    }
}
