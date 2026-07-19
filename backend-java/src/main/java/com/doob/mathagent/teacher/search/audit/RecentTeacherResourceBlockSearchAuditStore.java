package com.doob.mathagent.teacher.search.audit;

import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * In-memory recent audit store for teacher resource block searches.
 *
 * <p>This is a bounded diagnostic store for UI/MCP queryId lookup. It does not replace
 * persistent capability audit tables and intentionally stores no raw MCP secrets or local paths.
 */
public class RecentTeacherResourceBlockSearchAuditStore
        implements TeacherResourceBlockSearchAuditSink, TeacherResourceBlockSearchAuditLookup {

    private static final int DEFAULT_CAPACITY = 200;

    private final int capacity;
    private final Map<String, TeacherResourceBlockSearchAuditEvent> eventsByQueryId = new LinkedHashMap<>();
    private final ArrayDeque<String> queryOrder = new ArrayDeque<>();

    /**
     * Creates the default recent audit store.
     */
    public RecentTeacherResourceBlockSearchAuditStore() {
        this(DEFAULT_CAPACITY);
    }

    /**
     * Creates a bounded recent audit store.
     *
     * @param capacity maximum retained query events
     */
    public RecentTeacherResourceBlockSearchAuditStore(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("Teacher resource search audit capacity must be positive");
        }
        this.capacity = capacity;
    }

    /**
     * Records or replaces one audit event by query id.
     */
    @Override
    public synchronized void record(TeacherResourceBlockSearchAuditEvent event) {
        if (event == null || event.queryId() == null || event.queryId().isBlank()) {
            return;
        }
        String queryId = event.queryId().strip();
        if (eventsByQueryId.containsKey(queryId)) {
            queryOrder.remove(queryId);
        }
        eventsByQueryId.put(queryId, event);
        queryOrder.addLast(queryId);
        while (queryOrder.size() > capacity) {
            String evicted = queryOrder.removeFirst();
            eventsByQueryId.remove(evicted);
        }
    }

    /**
     * Finds one retained event by query id.
     */
    @Override
    public synchronized Optional<TeacherResourceBlockSearchAuditEvent> findByQueryId(String queryId) {
        if (queryId == null || queryId.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(eventsByQueryId.get(queryId.strip()));
    }
}

