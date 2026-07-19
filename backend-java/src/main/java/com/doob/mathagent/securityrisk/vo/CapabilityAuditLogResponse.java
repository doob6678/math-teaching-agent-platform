package com.doob.mathagent.securityrisk.vo;

import java.time.Instant;

/**
 * Capability audit row returned to security reviewers.
 *
 * @param eventId stable audit event id
 * @param occurredAt event timestamp
 * @param tenantId tenant that owns the event
 * @param subjectType backend resolved requester role
 * @param subjectId backend resolved requester id
 * @param action high-value action code
 * @param path API path bound to the capability
 * @param requestHash hash of the high-value request body
 * @param idempotencyKey client idempotency key
 * @param tokenHash SHA-256 token hash; raw tokens are never returned
 * @param decision lifecycle decision, such as issued, consumed, rejected, or denied
 * @param reason human-readable decision reason
 */
public record CapabilityAuditLogResponse(
        String eventId,
        Instant occurredAt,
        String tenantId,
        String subjectType,
        String subjectId,
        String action,
        String path,
        String requestHash,
        String idempotencyKey,
        String tokenHash,
        String decision,
        String reason) {
}
