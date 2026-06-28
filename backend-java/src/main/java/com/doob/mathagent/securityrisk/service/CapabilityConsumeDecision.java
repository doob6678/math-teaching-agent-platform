package com.doob.mathagent.securityrisk.service;

/**
 * Result of capability token consumption.
 *
 * @param allowed whether the high-value call may proceed
 * @param reason decision reason for audit and response
 */
public record CapabilityConsumeDecision(boolean allowed, String reason) {

    /**
     * Allows the call.
     *
     * @return allowed decision
     */
    public static CapabilityConsumeDecision allow() {
        return new CapabilityConsumeDecision(true, "Capability token accepted");
    }

    /**
     * Denies the call.
     *
     * @param reason denial reason
     * @return denied decision
     */
    public static CapabilityConsumeDecision deny(String reason) {
        return new CapabilityConsumeDecision(false, reason);
    }
}
