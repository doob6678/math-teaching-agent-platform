package com.doob.mathagent.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Proxy;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.redisson.api.RRateLimiter;
import org.redisson.api.RateType;
import org.redisson.api.RedissonClient;

/**
 * RedissonApiRateLimiter 单元测试：不连真实 Redis，用 JDK 动态代理伪造
 * RedissonClient→RRateLimiter 链路（本仓库测试惯例是手写 fake，不引入 Mockito），
 * 验证限流器命名、trySetRate 参数与 acquire/拒绝两条路径的 used 语义。
 */
class RedissonApiRateLimiterTest {

    private static final Instant NOW = Instant.parse("2026-09-01T10:00:00Z");

    @Test
    void namesLimiterWithPrefixKeyLimitAndWindowSecondsAndSetsOverAllRate() {
        RateLimiterDouble fake = new RateLimiterDouble();

        new RedissonApiRateLimiter(fake.client(), "math-agent:test:rl")
                .check("default:/api/retrieval/textbooks/search:device-1", 30, Duration.ofMinutes(1), NOW);

        // limit 与窗口秒数编进名字：配置变更后新名字立即生效，旧名字限流器空闲后由 keepAlive 过期。
        assertThat(fake.limiterNames).containsExactly(
                "math-agent:test:rl:default:/api/retrieval/textbooks/search:device-1:30:60");
        assertThat(fake.trySetRateCalls).singleElement().satisfies(args -> {
            assertThat(args[0]).isEqualTo(RateType.OVERALL);
            assertThat(args[1]).isEqualTo(30L);
            assertThat(args[2]).isEqualTo(Duration.ofMinutes(1));
            // keepAlive = 2*window：限流器 key 空闲两窗口后自动过期，替代旧实现的显式 EXPIRE。
            assertThat(args[3]).isEqualTo(Duration.ofMinutes(2));
        });
    }

    @Test
    void computesUsedFromRemainingPermitsWhenAcquired() {
        RateLimiterDouble fake = new RateLimiterDouble();
        fake.acquired = true;
        fake.availablePermits = 27;

        RateLimitUsage usage = new RedissonApiRateLimiter(fake.client(), "math-agent:test:rl")
                .check("device-1", 30, Duration.ofMinutes(1), NOW);

        assertThat(usage.limit()).isEqualTo(30);
        assertThat(usage.used()).isEqualTo(3);
        assertThat(usage.exceeded()).isFalse();
    }

    @Test
    void clampsUsedIntoOneThroughLimitAroundConcurrentPermitRecycling() {
        RateLimiterDouble fake = new RateLimiterDouble();
        fake.acquired = true;

        // availablePermits 与本次 acquire 之间可能有并发令牌回收：越界值一律夹回 [1, limit]。
        fake.availablePermits = 30;
        assertThat(new RedissonApiRateLimiter(fake.client(), "p").check("k", 30, Duration.ofMinutes(1), NOW).used())
                .isEqualTo(1);
        fake.availablePermits = -5;
        assertThat(new RedissonApiRateLimiter(fake.client(), "p").check("k", 30, Duration.ofMinutes(1), NOW).used())
                .isEqualTo(30);
        fake.availablePermits = 0;
        assertThat(new RedissonApiRateLimiter(fake.client(), "p").check("k", 30, Duration.ofMinutes(1), NOW).used())
                .isEqualTo(30);
    }

    @Test
    void reportsLimitPlusOneSoContractUsageExceededHoldsWhenDenied() {
        RateLimiterDouble fake = new RateLimiterDouble();
        fake.acquired = false;

        RateLimitUsage usage = new RedissonApiRateLimiter(fake.client(), "math-agent:test:rl")
                .check("device-1", 2, Duration.ofMinutes(1), NOW);

        // 拒绝路径不消耗令牌；used=limit+1 保证 RateLimitUsage.exceeded() 为 true，
        // 与旧实现超限计数的响应头语义近似一致。
        assertThat(usage.limit()).isEqualTo(2);
        assertThat(usage.used()).isEqualTo(3);
        assertThat(usage.exceeded()).isTrue();
    }

    /**
     * RRateLimiter / RedissonClient 的轻量代理替身：只实现被测代码用到的三个方法，
     * 其余方法直接抛出不支持异常，保证实现若偷偷扩大 API 依赖会立刻暴露。
     */
    private static final class RateLimiterDouble {

        final List<String> limiterNames = new ArrayList<>();
        final List<Object[]> trySetRateCalls = new ArrayList<>();
        boolean acquired = true;
        long availablePermits;

        RedissonClient client() {
            RRateLimiter limiter = (RRateLimiter) Proxy.newProxyInstance(
                    RateLimiterDouble.class.getClassLoader(),
                    new Class<?>[]{RRateLimiter.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "trySetRate" -> {
                            trySetRateCalls.add(Arrays.copyOf(args, args.length));
                            yield true;
                        }
                        case "tryAcquire" -> acquired;
                        case "availablePermits" -> availablePermits;
                        default -> throw new UnsupportedOperationException(method.getName());
                    });
            return (RedissonClient) Proxy.newProxyInstance(
                    RateLimiterDouble.class.getClassLoader(),
                    new Class<?>[]{RedissonClient.class},
                    (proxy, method, args) -> {
                        if ("getRateLimiter".equals(method.getName())) {
                            limiterNames.add((String) args[0]);
                            return limiter;
                        }
                        throw new UnsupportedOperationException(method.getName());
                    });
        }
    }
}
