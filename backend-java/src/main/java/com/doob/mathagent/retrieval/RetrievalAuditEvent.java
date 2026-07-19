package com.doob.mathagent.retrieval;

import java.util.List;
import java.util.stream.IntStream;

/**
 * 一次教材检索审计事件，对应 retrieval_query_log 和 retrieval_hit_log 两类记录。
 */
public record RetrievalAuditEvent(
        /** 查询审计 ID；同时返回给前端作为本次检索的 trace id。 */
        String queryId,
        /** 租户标识；当前默认 default，后续用于多机构数据隔离。 */
        String tenantId,
        /** 主体类型；例如 guest、student、teacher、admin、api_key。 */
        String subjectType,
        /** 主体 ID；未接入登录态时为空。 */
        String subjectId,
        /** 用户输入的原始检索词。 */
        String queryText,
        /** 总检索策略，例如 local_bm25_first。 */
        String retrievalStrategy,
        /** 用户请求的 Top K 上限。 */
        int requestedLimit,
        /** 实际返回命中数量。 */
        int hitCount,
        /** 检索耗时毫秒数，用于质量和性能审计。 */
        int elapsedMs,
        /** 请求上下文；写入 request_context_json，保存 IP、设备、UA、endpoint 等线索。 */
        RetrievalRequestContext requestContext,
        /** 命中证据列表；按 rankNo 写入 retrieval_hit_log。 */
        List<RetrievalAuditHit> hits) {

    public static RetrievalAuditEvent from(
            String queryId,
            TextbookSearchRequest request,
            TextbookSearchResponse response,
            int elapsedMs,
            RetrievalRequestContext requestContext) {
        RetrievalRequestContext normalizedContext = requestContext == null
                ? RetrievalRequestContext.defaultTextbookSearch()
                : requestContext.normalize();
        List<RetrievalAuditHit> auditHits = IntStream.range(0, response.hits().size())
                .mapToObj(index -> RetrievalAuditHit.from(index + 1, response.hits().get(index)))
                .toList();
        return new RetrievalAuditEvent(
                queryId,
                normalizedContext.tenantId(),
                normalizedContext.subjectType(),
                normalizedContext.subjectId(),
                request.query(),
                response.retrievalStrategy(),
                request.limit(),
                response.hits().size(),
                Math.max(elapsedMs, 0),
                normalizedContext,
                auditHits);
    }
}
