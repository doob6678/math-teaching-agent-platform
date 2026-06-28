package com.doob.mathagent.infrastructure.security;

import java.time.Duration;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * 基于 Spring Data Redis 的计数器实现：使用 Redis INCR 原子自增支撑分布式限流。
 */
public class StringRedisRateLimitCounter implements RedisRateLimitCounter {

    private final StringRedisTemplate redisTemplate;

    /**
     * 注入字符串 Redis 模板，避免业务侧直接依赖序列化细节。
     */
    public StringRedisRateLimitCounter(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * Redis INCR 是原子操作；当返回 1 时说明 key 是本窗口首次创建，此时设置 TTL。
     */
    @Override
    public long incrementAndExpireOnFirstUse(String key, Duration ttl) {
        Long used = redisTemplate.opsForValue().increment(key);
        if (used == null) {
            throw new IllegalStateException("Redis rate limit increment returned null for key: " + key);
        }
        if (used == 1L) {
            redisTemplate.expire(key, ttl);
        }
        return used;
    }
}
