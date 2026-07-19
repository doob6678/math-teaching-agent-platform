package com.doob.mathagent.retrieval;

import java.util.List;

/**
 * 教材检索响应，返回前端展示和审计追踪需要的结构化结果。
 */
public record TextbookSearchResponse(
        /** 本次检索审计 ID；对应 retrieval_query_log.query_id，可用于排查和追踪。 */
        String queryId,
        /** 实际执行的检索词。 */
        String query,
        /** 后端采用的 Top K 上限。 */
        int limit,
        /** 总检索策略，例如 local_bm25_first。 */
        String retrievalStrategy,
        /** Chinese statement of whether the pipeline produced verifiable textbook evidence. */
        String retrievalDescription,
        /** User-visible stage diagnostics for the actual BGE/CLIP/rerank path. */
        List<TextbookRetrievalStage> retrievalStages,
        /** 实际返回命中数量。 */
        int total,
        /** 按相关性排序后的教材证据列表。 */
        List<TextbookSearchHit> hits) {

    /** Preserves existing internal callers while new HTTP clients consume the richer diagnostics. */
    public TextbookSearchResponse(String queryId, String query, int limit, String retrievalStrategy, int total, List<TextbookSearchHit> hits) {
        this(queryId, query, limit, retrievalStrategy, "未提供链路说明。", List.of(), total, hits);
    }
}
