package com.doob.mathagent.retrieval;

import java.util.Optional;

/**
 * 检索审计查询端口，用于按 queryId 查看一次检索的审计详情。
 */
public interface RetrievalAuditLookup {

    Optional<RetrievalAuditEvent> findByQueryId(String queryId);
}
