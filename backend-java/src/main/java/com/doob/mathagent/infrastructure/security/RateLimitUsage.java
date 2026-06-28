package com.doob.mathagent.infrastructure.security;

/**
 * 限流窗口使用情况。
 *
 * @param limit 当前窗口允许的最大请求次数。
 * @param used 当前窗口已经使用的请求次数，包含本次请求。
 */
public record RateLimitUsage(int limit, int used) {

    /**
     * 判断当前窗口是否已经超过限制。
     */
    public boolean exceeded() {
        return used > limit;
    }
}
