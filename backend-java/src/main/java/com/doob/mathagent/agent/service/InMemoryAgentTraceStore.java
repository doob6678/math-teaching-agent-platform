package com.doob.mathagent.agent.service;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;

/**
 * In-memory trace store used until trace persistence is moved to MySQL.
 */
@Service
public class InMemoryAgentTraceStore implements AgentTraceStore {

    private final Map<String, AgentTraceRecord> records = new ConcurrentHashMap<>();

    /**
     * Saves or replaces a trace record by trace id.
     */
    @Override
    public AgentTraceRecord save(AgentTraceRecord record) {
        records.put(record.traceId(), record);
        return record;
    }

    /**
     * Finds a trace record by trace id.
     */
    @Override
    public Optional<AgentTraceRecord> find(String traceId) {
        return Optional.ofNullable(records.get(traceId));
    }
}
