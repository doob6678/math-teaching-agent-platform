package com.doob.mathagent.teacher.service;

import com.doob.mathagent.teacher.vo.TeacherResourceBlockSearchResponse;
import java.util.List;

/**
 * Audit event for one teacher resource block search.
 *
 * @param queryId response query id used by UI and MCP callers for lookup
 * @param tenantId backend-resolved tenant id
 * @param subjectType backend-resolved caller role
 * @param subjectId backend-resolved caller id
 * @param query normalized search query
 * @param limit bounded hit limit used by the search service
 * @param retrievalMode lexical retrieval mode used for this query
 * @param hitCount number of visible hits returned
 * @param elapsedMs service-side elapsed milliseconds
 * @param endpoint logical endpoint or MCP tool path that initiated the query
 * @param hits compact hit audit rows without raw local paths or secrets
 */
public record TeacherResourceBlockSearchAuditEvent(
        String queryId,
        String tenantId,
        String subjectType,
        String subjectId,
        String query,
        int limit,
        String retrievalMode,
        int hitCount,
        long elapsedMs,
        String endpoint,
        List<Hit> hits) {

    /**
     * Builds an audit event from a search response.
     */
    public static TeacherResourceBlockSearchAuditEvent from(
            String tenantId,
            String subjectType,
            String subjectId,
            String endpoint,
            TeacherResourceBlockSearchResponse response,
            long elapsedMs) {
        return new TeacherResourceBlockSearchAuditEvent(
                response.queryId(),
                tenantId,
                subjectType,
                subjectId,
                response.query(),
                response.limit(),
                response.retrievalMode(),
                response.hitCount(),
                Math.max(0, elapsedMs),
                endpoint,
                response.hits().stream()
                        .map(hit -> new Hit(
                                hit.documentId(),
                                hit.documentTitle(),
                                hit.permissionScope(),
                                hit.blockId(),
                                hit.blockType(),
                                hit.blockOrder(),
                                hit.pageNo(),
                                hit.score()))
                        .toList());
    }

    /**
     * One compact hit row in the audit event.
     *
     * @param documentId source document id
     * @param documentTitle source document display title
     * @param permissionScope document permission scope visible at query time
     * @param blockId parsed block id
     * @param blockType parsed block type
     * @param blockOrder parsed block order in the document
     * @param pageNo source page number when present
     * @param score lexical match score
     */
    public record Hit(
            String documentId,
            String documentTitle,
            String permissionScope,
            String blockId,
            String blockType,
            int blockOrder,
            Integer pageNo,
            double score) {
    }
}
