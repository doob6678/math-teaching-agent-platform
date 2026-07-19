package com.doob.mathagent.securityrisk.service;

/**
 * Sink for capability-token audit events.
 */
public interface CapabilityAuditSink {

    /**
     * Records one capability audit event.
     *
     * @param event event to record
     */
    void record(CapabilityAuditEvent event);
}
