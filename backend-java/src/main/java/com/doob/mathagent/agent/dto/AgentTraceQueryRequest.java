package com.doob.mathagent.agent.dto;

/**
 * Query parameters for listing agent execution traces.
 *
 * @param agentCode optional agent code filter
 * @param status optional execution status filter
 * @param planId optional exact plan id filter, used by teaching task id linked traces
 * @param planIdPrefix optional plan id prefix filter, used by grouped workflow traces
 * @param limit maximum rows to return, clipped by backend policy
 */
public record AgentTraceQueryRequest(
        String agentCode,
        String status,
        String planId,
        String planIdPrefix,
        Integer limit) {

    /**
     * Backward-compatible constructor for callers that do not filter by plan id.
     */
    public AgentTraceQueryRequest(String agentCode, String status, Integer limit) {
        this(agentCode, status, null, null, limit);
    }

    /**
     * Backward-compatible constructor for callers that filter by exact plan id only.
     */
    public AgentTraceQueryRequest(String agentCode, String status, String planId, Integer limit) {
        this(agentCode, status, planId, null, limit);
    }

    /**
     * Returns a null-safe query request with bounded limit.
     */
    public AgentTraceQueryRequest normalize() {
        int normalizedLimit = limit == null ? 20 : Math.max(1, Math.min(limit, 100));
        return new AgentTraceQueryRequest(
                blankToNull(agentCode),
                blankToNull(status),
                blankToNull(planId),
                blankToNull(planIdPrefix),
                normalizedLimit);
    }

    /**
     * Converts blank text to null for optional filters.
     */
    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
