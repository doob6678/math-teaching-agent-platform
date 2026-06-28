package com.doob.mathagent.retrieval;

/**
 * 数据库未启用时使用的审计 Sink，保证本地检索和单元测试不依赖 MySQL。
 */
public final class NoopRetrievalAuditSink implements RetrievalAuditSink {

    @Override
    public void record(RetrievalAuditEvent event) {
        // Intentionally empty: audit persistence is optional until MATH_AGENT_DB_ENABLED=true.
    }
}
