package com.doob.mathagent.securityrisk;

import com.doob.mathagent.securityrisk.service.CapabilityAuditEvent;
import com.doob.mathagent.securityrisk.service.CapabilityAuditSink;
import com.doob.mathagent.securityrisk.service.CapabilityTokenService;
import com.doob.mathagent.securityrisk.service.InMemoryCapabilityTokenStore;
import java.time.Clock;

public final class CapabilityTokenServiceFixture {

    private CapabilityTokenServiceFixture() {
    }

    public static CapabilityTokenService service(Clock clock) {
        return service(clock, new DisabledAuditSink());
    }

    public static CapabilityTokenService service(Clock clock, CapabilityAuditSink auditSink) {
        return new CapabilityTokenService(new InMemoryCapabilityTokenStore(), clock, auditSink);
    }

    private static final class DisabledAuditSink implements CapabilityAuditSink {
        @Override
        public void record(CapabilityAuditEvent event) {
            // Audit is explicitly disabled by this test fixture.
        }
    }
}
