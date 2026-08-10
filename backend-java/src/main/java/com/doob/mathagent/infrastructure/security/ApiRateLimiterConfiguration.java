package com.doob.mathagent.infrastructure.security;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * API 限流器配置：默认使用本地内存，显式启用 Redis 后切换为分布式计数。
 */
@Configuration
@EnableConfigurationProperties(RedisRateLimitProperties.class)
public class ApiRateLimiterConfiguration {

    /**
     * 本地内存限流器：适合单机开发和无 Redis 的测试环境。
     */
    /**
     * Redis 分布式限流器：适合部署阶段共享请求次数统计。
     */
    @Bean
    @ConditionalOnProperty(name = "math-agent.redis.rate-limit.enabled", havingValue = "true")
    public ApiRateLimiter redisApiRateLimiter(
            StringRedisTemplate redisTemplate,
            RedisRateLimitProperties properties) {
        return new RedisFixedWindowRateLimiter(
                new StringRedisRateLimitCounter(redisTemplate),
                properties.keyPrefix());
    }

    /**
     * 本地开发和无 Redis 测试环境的明确兜底实现。
     *
     * <p>之前只声明了 Redis 分支；当配置把 Redis 限流关闭时，Spring 无法创建
     * {@link ApiAccessControlService} 所需的 {@link ApiRateLimiter}，应用会在启动阶段因缺少 Bean 失败。
     * 这个分支仍按用户主体执行限流，只把计数存放在当前进程，且不会改变生产环境显式启用 Redis 时的选择。</p>
     */
    @Bean
    @ConditionalOnProperty(
            name = "math-agent.redis.rate-limit.enabled",
            havingValue = "false",
            matchIfMissing = true)
    public ApiRateLimiter localApiRateLimiter() {
        return FixedWindowRateLimiter.empty();
    }
}
