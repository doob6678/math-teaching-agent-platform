package com.doob.mathagent.infrastructure.security;

import org.redisson.api.RedissonClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * API 限流器配置：默认使用本地内存，显式启用 Redis 后切换为分布式限流。
 */
@Configuration
@EnableConfigurationProperties(RedisRateLimitProperties.class)
public class ApiRateLimiterConfiguration {

    /**
     * 本地内存限流器：适合单机开发和无 Redis 的测试环境。
     */
    /**
     * Redis 分布式限流器：基于 Redisson RRateLimiter 的原子 Lua 令牌限流，适合部署阶段多实例
     * 共享请求次数统计。复用 {@code RedissonClientConfiguration} 提供的 RedissonClient
     * （与讲义并发守卫同一客户端，不新建连接池），因此该分支要求
     * {@code math-agent.redis.redisson.enabled=true} 与之同时打开；若只开限流不开 Redisson，
     * 应用会因缺少 RedissonClient Bean 在启动期快速失败，而不是运行期限流失效静默放行。
     */
    @Bean
    @ConditionalOnProperty(name = "math-agent.redis.rate-limit.enabled", havingValue = "true")
    public ApiRateLimiter redisApiRateLimiter(
            RedissonClient redissonClient,
            RedisRateLimitProperties properties) {
        return new RedissonApiRateLimiter(redissonClient, properties.keyPrefix());
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
