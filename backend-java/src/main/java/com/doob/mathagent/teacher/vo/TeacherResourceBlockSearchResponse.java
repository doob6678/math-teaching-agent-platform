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
     * @param permissionScope source document permission scope
     * @param blockId parsed block id used by citations
     * @param blockType normalized block type
     * @param blockOrder order inside the source document
     * @param chapter inferred chapter heading
     * @param section inferred section heading
     * @param pageNo extracted source page number
     * @param snippet compact text snippet around the match
     * @param score lexical relevance score
     */
    public record Hit(
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
    }
}
