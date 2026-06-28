package com.doob.mathagent.securityrisk.config;

import com.doob.mathagent.securityrisk.service.CapabilityAuditSink;
import com.doob.mathagent.securityrisk.service.RecentCapabilityAuditStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Capability audit configuration.
 */
@Configuration
public class CapabilityAuditConfiguration {

    /**
     * Provides a bounded local audit sink until MySQL-backed security audit tables are introduced.
     *
     * @return recent capability audit store
     */
    @Bean
    public CapabilityAuditSink capabilityAuditSink() {
        return new RecentCapabilityAuditStore(500);
    }
}
