package com.doob.mathagent.securityrisk.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for capability token storage.
 *
 * @param enabled whether to use Redis/Redisson for distributed one-time token storage
 * @param keyPrefix Redis key prefix used to isolate capability token records by app and environment
 */
@ConfigurationProperties(prefix = "math-agent.redis.capability-store")
public record CapabilityTokenStoreProperties(boolean enabled, String keyPrefix) {

    /**
     * Returns a property instance with safe defaults when a field is omitted.
     */
    public CapabilityTokenStoreProperties {
        if (keyPrefix == null || keyPrefix.isBlank()) {
            keyPrefix = "math-agent:security:capability";
        }
    }
}
