package com.doob.mathagent.securityrisk.service;

import java.time.Instant;

/**
 * Stored capability token record used for replay protection.
 *
 * @param token opaque token value
 * @param tenantId tenant id bound to the requester
 * @param subjectType requester role
 * @param subjectId requester user id
 * @param action high-value action code
 * @param path target API path
 * @param requestHash hash of exact request body
 * @param idempotencyKey business idempotency key
 * @param maxCost maximum estimated cost
 * @param expiresAt expiry instant
 * @param consumed whether the token has already been used
 */
public record CapabilityTokenRecord(
        String token,
        String tenantId,
        String subjectType,
        String subjectId,
        String action,
        String path,
        String requestHash,
        String idempotencyKey,
        double maxCost,
        Instant expiresAt,
        boolean consumed) {

    /**
     * Returns a consumed copy.
     *
     * @return consumed record
     */
    public CapabilityTokenRecord consume() {
        return new CapabilityTokenRecord(
                token,
                tenantId,
                subjectType,
                subjectId,
                action,
                path,
                requestHash,
                idempotencyKey,
                maxCost,
                expiresAt,
                true);
    }
}
