package com.doob.mathagent.securityrisk.service;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * In-memory recent capability audit store for local development and tests.
 */
public class RecentCapabilityAuditStore implements CapabilityAuditSink {

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
}
