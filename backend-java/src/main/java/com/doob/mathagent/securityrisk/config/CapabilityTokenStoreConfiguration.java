package com.doob.mathagent.securityrisk.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.doob.mathagent.securityrisk.service.CapabilityTokenStore;
import com.doob.mathagent.securityrisk.service.RedissonCapabilityTokenStore;
import org.redisson.api.RedissonClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Selects the capability token store implementation.
 */
@Configuration
@EnableConfigurationProperties(CapabilityTokenStoreProperties.class)
public class CapabilityTokenStoreConfiguration {

    /**
     * Distributed store used for multi-instance deployments.
     *
     * @param redissonClient Redisson client
     * @param objectMapper Jackson mapper used to serialize token records
     * @param properties capability store properties
     * @return Redisson-backed capability token store
     */
    @Bean
    @ConditionalOnProperty(name = "math-agent.redis.capability-store.enabled", havingValue = "true")
    public CapabilityTokenStore redissonCapabilityTokenStore(
            RedissonClient redissonClient,
            ObjectMapper objectMapper,
            CapabilityTokenStoreProperties properties) {
        return new RedissonCapabilityTokenStore(redissonClient, objectMapper, properties.keyPrefix());
    }
}
