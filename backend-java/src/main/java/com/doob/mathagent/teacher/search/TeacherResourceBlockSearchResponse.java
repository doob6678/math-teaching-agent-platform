package com.doob.mathagent.teacher.search;

import java.util.List;
import java.util.Map;

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
        List<Hit> hits,
        CandidateFunnel candidateFunnel) {

    /** Compatibility constructor for callers that do not expose route diagnostics. */
    public TeacherResourceBlockSearchResponse(
            String queryId,
            String query,
            int limit,
            String retrievalMode,
            int hitCount,
            List<Hit> hits) {
        this(queryId, query, limit, retrievalMode, hitCount, hits, CandidateFunnel.EMPTY);
    }

    /**
     * Bounded per-request candidate trace. It contains only durable FILE/block ids and never source text or paths.
     */
    public record CandidateFunnel(
            List<String> vectorFileDocumentIds,
            List<String> lexicalFileDocumentIds,
            List<String> tagFileDocumentIds,
            List<String> fusedFileDocumentIds,
            List<String> finalFileDocumentIds,
            List<String> representativeBlockIds,
            int rerankCandidateCount,
            boolean sqlBoundedEvidence,
            Map<String, Double> fusedFileScores,
            List<FileCandidateTrace> fileCandidates,
            List<BlockEvidenceTrace> blockEvidence,
            String failureType) {

        /** Compatibility constructor for callers that only provide the original route lists. */
        public CandidateFunnel(
                List<String> vectorFileDocumentIds,
                List<String> lexicalFileDocumentIds,
                List<String> tagFileDocumentIds,
                List<String> fusedFileDocumentIds,
                List<String> finalFileDocumentIds,
                List<String> representativeBlockIds,
                int rerankCandidateCount,
                boolean sqlBoundedEvidence) {
            this(vectorFileDocumentIds, lexicalFileDocumentIds, tagFileDocumentIds, fusedFileDocumentIds,
                    finalFileDocumentIds, representativeBlockIds, rerankCandidateCount, sqlBoundedEvidence,
                    Map.of(), List.of(), List.of(), "none");
        }

        public static final CandidateFunnel EMPTY = new CandidateFunnel(
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), 0, false,
                Map.of(), List.of(), List.of(), "empty");
    }

    /** Durable FILE-level funnel trace. Scores are continuous weighted-RRF scores, never raw route scores. */
    public record FileCandidateTrace(
            String fileDocumentId,
            double fusedRrfScore,
            int fusedRank,
            int finalRank,
            boolean inFinalCandidates) {
    }

    /** Route-local block evidence retained for representative selection and post-run audit. */
    public record BlockEvidenceTrace(
            String fileDocumentId,
            String blockId,
            int blockOrder,
            int vectorRank,
            double vectorScore,
            int lexicalRank,
            double lexicalScore,
            int tagRank,
            double tagScore,
            List<String> routeSources,
            boolean representative) {
    }

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
     * @param fileName actual file name derived from sourcePath, never the Feishu root display name
     * @param sourcePath relative source path inside the document package or staging folder
     * @param blockRole coarse semantic role used by stage-two rerank
     * @param graphTags normalized graph tag names aligned to the block
     * @param evidenceBlockIds primary block plus expanded neighbor evidence window block ids
     * @param evidenceText merged evidence text after neighbor expansion
     * @param snippet compact text snippet around the match
     * @param score lexical relevance score
     * @param imageAssetIds opaque asset ids parsed from block imageRefs
     * @param assetRefs visible backend-controlled asset references
     * @param rootDocumentId durable ROOT resource identity when the hit is a physical FILE
     * @param fileDocumentId durable physical FILE document identity
     * @param providerItemId stable provider file identity
     * @param splitFingerprint parser split version used for the indexed file
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
            String fileName,
            String sourcePath,
            String blockRole,
            List<String> graphTags,
            List<String> evidenceBlockIds,
            String evidenceText,
            String snippet,
            double score,
            List<String> imageAssetIds,
            List<AssetRef> assetRefs,
            String rootDocumentId,
            String fileDocumentId,
            String providerItemId,
            String splitFingerprint) {

        /** Compatibility constructor for all existing teacher/textbook callers. */
        public Hit(
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
                String fileName,
                String sourcePath,
                String blockRole,
                List<String> graphTags,
                List<String> evidenceBlockIds,
                String evidenceText,
                String snippet,
                double score,
                List<String> imageAssetIds,
                List<AssetRef> assetRefs) {
            this(documentId, documentTitle, sourceType, permissionScope, blockId, blockType, blockOrder, chapter,
                    section, pageNo, fileName, sourcePath, blockRole, graphTags, evidenceBlockIds, evidenceText,
                    snippet, score, imageAssetIds, assetRefs, "", documentId, "", "");
        }

        /** Compatibility constructor for callers that predate the explicit fileName response field. */
        public Hit(
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
            this(documentId, documentTitle, sourceType, permissionScope, blockId, blockType, blockOrder, chapter,
                    section, pageNo, fileNameFromPath(sourcePath), sourcePath, blockRole, graphTags, evidenceBlockIds,
                    evidenceText, snippet, score, imageAssetIds, assetRefs);
        }

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
            return withImageAssetRefs(imageAssetIds, visibleAssetRefs);
        }

        public Hit withImageAssetRefs(List<String> visibleAssetIds, List<AssetRef> visibleAssetRefs) {
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
                    fileName,
                    sourcePath,
                    blockRole,
                    graphTags,
                    evidenceBlockIds,
                    evidenceText,
                    snippet,
                    score,
                    visibleAssetIds == null ? List.of() : List.copyOf(visibleAssetIds),
                    visibleAssetRefs == null ? List.of() : List.copyOf(visibleAssetRefs),
                    rootDocumentId,
                    fileDocumentId,
                    providerItemId,
                    splitFingerprint);
        }

        private static String fileNameFromPath(String sourcePath) {
            if (sourcePath == null || sourcePath.isBlank()) return "";
            String normalized = sourcePath.replace('\\', '/');
            int slash = normalized.lastIndexOf('/');
            return slash < 0 ? normalized : normalized.substring(slash + 1);
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

