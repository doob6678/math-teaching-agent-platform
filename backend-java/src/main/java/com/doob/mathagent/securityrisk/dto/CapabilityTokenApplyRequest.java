package com.doob.mathagent.securityrisk.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;

/**
 * Request for applying a one-time capability token before a high-value operation.
 *
 * @param action high-value action code, such as teaching:submit
 * @param path target API path bound to the token
 * @param requestHash SHA-256 or stable hash of the exact high-value request body
 * @param idempotencyKey business idempotency key used to correlate retries
 * @param maxCost maximum estimated cost accepted by the caller
 */
public record CapabilityTokenApplyRequest(
        @NotBlank String action,
        @NotBlank String path,
        @NotBlank String requestHash,
        @NotBlank String idempotencyKey,
        @PositiveOrZero double maxCost) {
}
