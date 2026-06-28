package com.doob.mathagent.infrastructure.security;

import java.time.Duration;
import java.time.Instant;

/**
 * API 固定窗口限流器接口：隐藏本地内存、Redis 等不同计数后端，业务层只关心限流结果。
 */
public interface ApiRateLimiter {

    /**
     * 记录一次访问并返回当前固定窗口内的使用量。
     *
     * @param key 限流维度键，通常由租户、接口、主体、设备等字段组合而成。
     * @param limit 固定窗口允许的最大访问次数。
     * @param window 固定窗口长度。
     * @param now 当前时间，用于计算固定窗口起点。
     * @return 当前窗口的访问次数与是否超过限制。
     */
    RateLimitUsage check(String key, int limit, Duration window, Instant now);
}
