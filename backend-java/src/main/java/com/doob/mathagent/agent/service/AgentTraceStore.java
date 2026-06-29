package com.doob.mathagent.agent.service;

import java.util.Optional;
import java.util.List;

/**
 * Storage boundary for agent traces; later stages can replace this with MyBatis persistence.
 */
public interface AgentTraceStore {

    /**
     * Saves one trace record.
     *
     * @param record trace record
     * @return saved record
     */
    AgentTraceRecord save(AgentTraceRecord record);

    /**
     * Finds a trace by id.
     *
     * @param traceId trace id
     * @return trace when present
     */
    Optional<AgentTraceRecord> find(String traceId);

    /**
     * Searches traces by backend-scoped criteria.
     *
     * @param criteria search criteria
     * @return matching traces
     */
    List<AgentTraceRecord> search(AgentTraceSearchCriteria criteria);
}
