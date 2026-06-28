package com.doob.mathagent.securityrisk.dto;

/**
 * Query conditions for capability audit review.
 *
 * @param tenantId backend resolved tenant id; callers cannot override it from request parameters
 * @param subjectType optional audited subject role filter
 * @param subjectId optional audited subject id filter
 * @param action optional capability action filter, such as teaching:submit
 * @param decision optional lifecycle decision filter, such as issued, consumed, rejected, or denied
 * @param limit maximum rows returned to the reviewer
 */
public record CapabilityAuditQuery(
        String tenantId,
        String subjectType,
        String subjectId,
        String action,
        String decision,
        int limit) {

    private static final int DEFAULT_LIMIT = 50;
    private static final int MAX_LIMIT = 200;

    /**
     * Normalizes blank filters and clamps the result limit for stable audit queries.
     *
     * @return normalized query
     */
    public CapabilityAuditQuery normalize() {
        return new CapabilityAuditQuery(
                textOrDefault(tenantId, "default"),
                blankToNull(subjectType),
                blankToNull(subjectId),
                blankToNull(action),
                blankToNull(decision),
                normalizedLimit(limit));
    }

    /**
     * Returns text when present, otherwise the default value.
     */
    private static String textOrDefault(String value, String defaultValue) {
        String text = blankToNull(value);
        return text == null ? defaultValue : text;
    }

    /**
     * Converts blank request parameters to null so filters remain optional.
     */
    private static String blankToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.strip();
    }

    /**
     * Clamps query size to avoid unbounded audit export from the review endpoint.
     */
    private static int normalizedLimit(int limit) {
        if (limit <= 0) {
            return DEFAULT_LIMIT;
        }
        return Math.min(limit, MAX_LIMIT);
    }
}
