package com.doob.mathagent.infrastructure.security;

import java.time.Duration;
import java.time.Instant;

/**
 * Redis 固定窗口限流器：适合多实例部署时共享接口次数统计，避免单机内存限流被横向扩容绕过。
 */
public class RedisFixedWindowRateLimiter implements ApiRateLimiter {

    private final RedisRateLimitCounter counter;
    private final String keyPrefix;

    /**
     * 创建 Redis 固定窗口限流器。
     *
     * @param counter Redis 计数端口，生产环境由 StringRedisTemplate 实现。
     * @param keyPrefix Redis key 前缀，用于区分环境、应用和限流用途。
     */
    public RedisFixedWindowRateLimiter(RedisRateLimitCounter counter, String keyPrefix) {
        this.counter = counter;
        this.keyPrefix = keyPrefix;
    }

    /**
     * 使用窗口起点拼接 Redis key，递增计数并返回本窗口使用情况。
     */
    @Override
    public RateLimitUsage check(String key, int limit, Duration window, Instant now) {
        long windowStartMillis = now.toEpochMilli() / window.toMillis() * window.toMillis();
        String redisKey = "%s:%s:%d".formatted(keyPrefix, key, windowStartMillis);
        long used = counter.incrementAndExpireOnFirstUse(redisKey, window.plusSeconds(1));
        return new RateLimitUsage(limit, used > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) used);
    }
}
