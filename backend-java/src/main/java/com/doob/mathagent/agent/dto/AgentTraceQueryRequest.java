package com.doob.mathagent.agent.dto;

/**
 * Query parameters for listing agent execution traces.
 *
 * @param agentCode optional agent code filter
 * @param status optional execution status filter
 * @param planId optional plan id filter, used by teaching task id linked traces
 * @param limit maximum rows to return, clipped by backend policy
 */
public record AgentTraceQueryRequest(
        String agentCode,
        String status,
        String planId,
        Integer limit) {

    /**
     * Backward-compatible constructor for callers that do not filter by plan id.
     */
    public AgentTraceQueryRequest(String agentCode, String status, Integer limit) {
        this(agentCode, status, null, limit);
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
                normalizedLimit);
    }

    /**
     * Converts blank text to null for optional filters.
     */
    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
