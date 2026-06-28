package com.doob.mathagent.infrastructure.security.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Project-owned Redisson configuration.
 *
 * <p>The Redisson starter auto-configuration is excluded because it opens a Redis connection during application
 * startup. This configuration keeps local tests offline and creates Redisson only when the Redis feature switch is
 * enabled.</p>
 */
@Configuration
@ConditionalOnProperty(prefix = "math-agent.redis.redisson", name = "enabled", havingValue = "true")
public class RedissonClientConfiguration {

    /**
     * Binds Redisson connection properties from application.yml and environment variables.
     *
     * @return Redisson connection properties
     */
    @Bean
    @ConfigurationProperties("math-agent.redis.redisson")
    RedissonClientProperties redissonClientProperties() {
        return new RedissonClientProperties();
    }

    /**
     * Creates the Redisson client used for distributed locks and cache protection.
     *
     * @param properties Redis connection properties
     * @return Redisson client
     */
    @Bean(destroyMethod = "shutdown")
    RedissonClient redissonClient(RedissonClientProperties properties) {
        Config config = new Config();
        config.useSingleServer().setAddress(properties.getAddress());
        return Redisson.create(config);
    }
}
