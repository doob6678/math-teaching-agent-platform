package com.doob.mathagent.agent.service;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * In-memory trace store used until trace persistence is moved to MySQL.
 */
@Service
@ConditionalOnProperty(prefix = "math-agent.database", name = "enabled", havingValue = "false", matchIfMissing = true)
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

    /**
     * Searches trace records using backend-scoped criteria.
     */
    @Override
    public List<AgentTraceRecord> search(AgentTraceSearchCriteria criteria) {
        AgentTraceSearchCriteria normalized = criteria.normalize();
        return records.values().stream()
                .filter(trace -> normalized.tenantId().equals(trace.tenantId()))
                .filter(trace -> normalized.subjectType() == null || normalized.subjectType().equals(trace.subjectType()))
                .filter(trace -> normalized.subjectId() == null || normalized.subjectId().equals(trace.subjectId()))
                .filter(trace -> normalized.agentCode() == null || normalized.agentCode().equals(trace.agentCode()))
                .filter(trace -> normalized.status() == null || normalized.status().equals(trace.status()))
                .sorted(Comparator.comparing(AgentTraceRecord::createdAt).reversed())
                .limit(normalized.limit())
                .toList();
    }
}
