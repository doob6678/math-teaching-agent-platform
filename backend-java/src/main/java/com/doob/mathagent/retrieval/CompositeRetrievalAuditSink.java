package com.doob.mathagent.retrieval;

import java.util.List;

/**
 * 组合审计写入端口。
 *
 * <p>用于同时写近期内存缓存和 MySQL 持久表；任一 delegate 失败会向上抛出，避免静默丢审计。
 */
public class CompositeRetrievalAuditSink implements RetrievalAuditSink {

    private final List<RetrievalAuditSink> delegates;

    public CompositeRetrievalAuditSink(List<RetrievalAuditSink> delegates) {
        this.delegates = List.copyOf(delegates);
    }

    @Override
    public void record(RetrievalAuditEvent event) {
        for (RetrievalAuditSink delegate : delegates) {
            delegate.record(event);
        }
    }
}
