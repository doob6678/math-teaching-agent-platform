package com.doob.mathagent.securityrisk.service;

/**
 * Capability audit sink used when audit capture is intentionally disabled.
 */
public class NoopCapabilityAuditSink implements CapabilityAuditSink {

    /**
     * Ignores the event.
     *
     * @param event event to record
     */
    @Override
    public void record(CapabilityAuditEvent event) {
        // Intentionally empty.
    }
}
