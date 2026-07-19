package com.doob.mathagent.infrastructure.security;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * 进程内固定窗口限流器：用于本地开发和单机阶段的次数限制。
 */
public class FixedWindowRateLimiter implements ApiRateLimiter {

    private final Map<String, WindowCounter> counters = new HashMap<>();

    /**
     * 创建空限流器。
     */
    public static FixedWindowRateLimiter empty() {
        return new FixedWindowRateLimiter();
    }

    /**
     * 记录一次访问并返回窗口内使用情况。
     */
    @Override
    public synchronized RateLimitUsage check(String key, int limit, Duration window, Instant now) {
        long windowStart = now.toEpochMilli() / window.toMillis() * window.toMillis();
        WindowCounter counter = counters.get(key);
        if (counter == null || counter.windowStartMillis() != windowStart) {
            counter = new WindowCounter(windowStart, 0);
        }
        counter = new WindowCounter(windowStart, counter.used() + 1);
        counters.put(key, counter);
        return new RateLimitUsage(limit, counter.used());
    }

    /**
     * 单个固定窗口计数器。
     *
     * @param windowStartMillis 固定窗口开始时间戳，单位毫秒。
     * @param used 当前窗口已经使用的请求次数。
     */
    private record WindowCounter(long windowStartMillis, int used) {
    }
}
