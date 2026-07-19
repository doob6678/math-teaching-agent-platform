package com.doob.mathagent.infrastructure.security;

import java.time.Duration;

/**
 * Redis 限流计数器端口：负责一次原子递增，并在首次创建窗口 key 时设置过期时间。
 */
public interface RedisRateLimitCounter {

    /**
     * 对 Redis key 做自增，并确保第一次命中时设置 TTL，防止限流 key 无限增长。
     *
     * @param key Redis 中保存固定窗口计数的完整 key。
     * @param ttl key 的存活时间，通常略大于窗口长度。
     * @return 自增后的窗口计数。
     */
    long incrementAndExpireOnFirstUse(String key, Duration ttl);
}
