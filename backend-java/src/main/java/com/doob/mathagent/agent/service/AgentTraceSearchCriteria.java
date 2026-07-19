package com.doob.mathagent.agent.service;

/**
 * Store-level criteria for searching agent traces after backend identity scoping.
 *
 * @param tenantId tenant id
 * @param subjectType optional subject role; null lets admins search tenant-wide
 * @param subjectId optional subject id; null lets admins search tenant-wide
 * @param agentCode optional agent code
 * @param status optional execution status
 * @param planId optional exact plan id, used to link traces to teaching task ids
 * @param planIdPrefix optional plan id prefix, used to recover grouped workflow traces
 * @param limit maximum rows
 */
public record AgentTraceSearchCriteria(
        String tenantId,
        String subjectType,
        String subjectId,
        String agentCode,
        String status,
        String planId,
        String planIdPrefix,
        int limit) {

    /**
     * Backward-compatible constructor for callers that do not filter by plan id.
     */
    public AgentTraceSearchCriteria(
            String tenantId,
            String subjectType,
            String subjectId,
            String agentCode,
            String status,
            int limit) {
        this(tenantId, subjectType, subjectId, agentCode, status, null, null, limit);
    }

    /**
     * Backward-compatible constructor for callers that filter by exact plan id only.
     */
    public AgentTraceSearchCriteria(
            String tenantId,
            String subjectType,
            String subjectId,
            String agentCode,
            String status,
            String planId,
            int limit) {
        this(tenantId, subjectType, subjectId, agentCode, status, planId, null, limit);
    }

    /**
     * Returns criteria with safe defaults and clipped limit.
     */
    public AgentTraceSearchCriteria normalize() {
        return new AgentTraceSearchCriteria(
                textOrDefault(tenantId, "default"),
                blankToNull(subjectType),
                blankToNull(subjectId),
                blankToNull(agentCode),
                blankToNull(status),
                blankToNull(planId),
                blankToNull(planIdPrefix),
                Math.max(1, Math.min(limit, 100)));
    }

    /**
     * Returns stripped text or fallback.
     */
    private static String textOrDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.strip();
    }

    /**
     * Returns stripped text or null.
     */
    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
