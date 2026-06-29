package com.doob.mathagent.agent.service;

/**
 * Store-level criteria for searching agent traces after backend identity scoping.
 *
 * @param tenantId tenant id
 * @param subjectType optional subject role; null lets admins search tenant-wide
 * @param subjectId optional subject id; null lets admins search tenant-wide
 * @param agentCode optional agent code
 * @param status optional execution status
 * @param limit maximum rows
 */
public record AgentTraceSearchCriteria(
        String tenantId,
        String subjectType,
        String subjectId,
        String agentCode,
        String status,
        int limit) {

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
