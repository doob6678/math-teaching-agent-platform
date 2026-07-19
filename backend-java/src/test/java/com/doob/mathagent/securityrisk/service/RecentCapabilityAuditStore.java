package com.doob.mathagent.securityrisk.service;

import com.doob.mathagent.securityrisk.dto.CapabilityAuditQuery;
import com.doob.mathagent.securityrisk.vo.CapabilityAuditLogResponse;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;

/**
 * Test-only bounded capability audit store.
 */
public class RecentCapabilityAuditStore implements CapabilityAuditSink, CapabilityAuditLookup {

    private final int capacity;
    private final Deque<CapabilityAuditEvent> events = new ArrayDeque<>();

    /**
     * Creates a bounded recent audit store.
     *
     * @param capacity maximum events retained in memory
     */
    public RecentCapabilityAuditStore(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("Capability audit capacity must be positive");
        }
        this.capacity = capacity;
    }

    /**
     * Records one event and evicts the oldest event when the store exceeds capacity.
     *
     * @param event event to record
     */
    @Override
    public synchronized void record(CapabilityAuditEvent event) {
        if (event == null) {
            return;
        }
        events.addLast(event);
        while (events.size() > capacity) {
            events.removeFirst();
        }
    }

    /**
     * Returns a snapshot of recent events in insertion order.
     *
     * @return audit event snapshot
     */
    public synchronized List<CapabilityAuditEvent> events() {
        return new ArrayList<>(events);
    }

    /**
     * Searches retained events by tenant and optional audit filters.
     *
     * @param query query conditions; tenant is always required after normalization
     * @return audit rows in newest-first order
     */
    @Override
    public synchronized List<CapabilityAuditLogResponse> search(CapabilityAuditQuery query) {
        CapabilityAuditQuery normalized = query.normalize();
        List<CapabilityAuditEvent> newestFirst = new ArrayList<>(events);
        Collections.reverse(newestFirst);
        return newestFirst.stream()
                .filter(event -> normalized.tenantId().equals(event.tenantId()))
                .filter(event -> matches(normalized.subjectType(), event.subjectType()))
                .filter(event -> matches(normalized.subjectId(), event.subjectId()))
                .filter(event -> matches(normalized.action(), event.action()))
                .filter(event -> matches(normalized.decision(), event.decision()))
                .limit(normalized.limit())
                .map(CapabilityAuditResponses::fromEvent)
                .toList();
    }

    private static boolean matches(String expected, String actual) {
        return expected == null || expected.equals(actual);
    }
}
