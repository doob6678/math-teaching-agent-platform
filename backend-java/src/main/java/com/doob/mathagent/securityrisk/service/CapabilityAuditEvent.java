package com.doob.mathagent.securityrisk.service;

import java.time.Instant;

/**
 * Audit event for capability-token lifecycle decisions.
 *
 * @param eventId stable audit event id
 * @param occurredAt event timestamp
 * @param tenantId backend resolved tenant id
 * @param subjectType backend resolved subject type
 * @param subjectId backend resolved subject id
 * @param action high-value action code
 * @param path API path bound to the capability
 * @param requestHash request body hash bound to the capability
 * @param idempotencyKey business idempotency key
 * @param token capability token value; kept for local audit and should be hashed before long-term persistence
 * @param decision issued, consumed, rejected, or denied
 * @param reason human-readable decision reason
 */
public record CapabilityAuditEvent(
        String eventId,
        Instant occurredAt,
        String tenantId,
        String subjectType,
        String subjectId,
        String action,
        String path,
        String requestHash,
        String idempotencyKey,
        String token,
        String decision,
        String reason) {
}
