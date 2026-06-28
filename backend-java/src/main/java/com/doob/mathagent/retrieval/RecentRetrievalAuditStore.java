package com.doob.mathagent.retrieval;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 近期检索审计内存存储。
 *
 * <p>字段含义：capacity 表示最多保留的审计事件数；eventsByQueryId 按 queryId 保存详情；
 * queryOrder 保存插入顺序，用于容量超限时淘汰最旧事件。该存储用于本地开发和数据库未启用时的
 * queryId 排查，不替代 MySQL 持久审计表。
 */
public class RecentRetrievalAuditStore implements RetrievalAuditSink, RetrievalAuditLookup {

    private final int capacity;
    private final Map<String, RetrievalAuditEvent> eventsByQueryId = new LinkedHashMap<>();
    private final Deque<String> queryOrder = new ArrayDeque<>();

    public RecentRetrievalAuditStore(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("Recent retrieval audit capacity must be positive");
        }
        this.capacity = capacity;
    }

    @Override
    public synchronized void record(RetrievalAuditEvent event) {
        if (event == null || event.queryId() == null || event.queryId().isBlank()) {
            return;
        }
        String queryId = event.queryId();
        if (eventsByQueryId.containsKey(queryId)) {
            queryOrder.remove(queryId);
        }
        eventsByQueryId.put(queryId, event);
        queryOrder.addLast(queryId);
        while (eventsByQueryId.size() > capacity) {
            String oldest = queryOrder.removeFirst();
            eventsByQueryId.remove(oldest);
        }
    }

    @Override
    public synchronized Optional<RetrievalAuditEvent> findByQueryId(String queryId) {
        if (queryId == null || queryId.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(eventsByQueryId.get(queryId.strip()));
    }
}
