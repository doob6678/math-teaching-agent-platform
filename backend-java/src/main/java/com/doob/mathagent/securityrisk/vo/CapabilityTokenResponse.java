package com.doob.mathagent.securityrisk.vo;

import java.time.Instant;

/**
 * Capability token response for a high-value operation.
 *
 * @param token opaque one-time token
 * @param action action bound to the token
 * @param path API path bound to the token
 * @param requestHash exact request hash bound to the token
 * @param expiresAt expiry instant
 * @param maxCost maximum estimated cost
 */
public record CapabilityTokenResponse(
        String token,
        String action,
        String path,
        String requestHash,
        Instant expiresAt,
        double maxCost) {
}
