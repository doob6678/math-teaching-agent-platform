package com.doob.mathagent.securityrisk.config;

import com.doob.mathagent.securityrisk.service.RecentCapabilityAuditStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Capability audit configuration.
 */
@Configuration
public class CapabilityAuditConfiguration {

    /**
     * Provides a bounded local audit sink when MySQL-backed security audit tables are disabled.
     *
     * @return recent capability audit store
     */
    @Bean
    @ConditionalOnProperty(prefix = "math-agent.database", name = "enabled", havingValue = "false", matchIfMissing = true)
    public RecentCapabilityAuditStore capabilityAuditSink() {
        return new RecentCapabilityAuditStore(500);
    }
}
