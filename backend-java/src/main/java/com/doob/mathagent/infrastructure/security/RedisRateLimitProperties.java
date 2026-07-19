package com.doob.mathagent.infrastructure.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Redis 限流配置。
 *
 * @param enabled 是否启用 Redis 分布式限流；默认 false，未部署 Redis 时保持本地开发可运行。
 * @param keyPrefix Redis key 前缀，用于隔离应用、环境和限流数据。
 */
@ConfigurationProperties(prefix = "math-agent.redis.rate-limit")
public record RedisRateLimitProperties(boolean enabled, String keyPrefix) {
}
