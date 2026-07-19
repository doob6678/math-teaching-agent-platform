package com.doob.mathagent.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class RedisFixedWindowRateLimiterTest {

    @Test
    void incrementsWindowCounterWithRedisKeyAndExpiry() {
        CapturingRedisRateLimitCounter counter = new CapturingRedisRateLimitCounter(1);
        RedisFixedWindowRateLimiter rateLimiter =
                new RedisFixedWindowRateLimiter(counter, "math-agent:test:rl");

        RateLimitUsage usage = rateLimiter.check(
                "default:/api/retrieval/textbooks/search:device-1",
                30,
                Duration.ofMinutes(1),
                Instant.parse("2026-06-28T10:15:37Z"));

        assertThat(usage.limit()).isEqualTo(30);
        assertThat(usage.used()).isEqualTo(1);
        assertThat(usage.exceeded()).isFalse();
        assertThat(counter.calls()).singleElement().satisfies(call -> {
            assertThat(call.key())
                    .isEqualTo("math-agent:test:rl:default:/api/retrieval/textbooks/search:device-1:1782641700000");
            assertThat(call.ttl()).isEqualTo(Duration.ofSeconds(61));
        });
    }

    @Test
    void marksRequestExceededWhenRedisCounterCrossesLimit() {
        CapturingRedisRateLimitCounter counter = new CapturingRedisRateLimitCounter(3);
        RedisFixedWindowRateLimiter rateLimiter =
                new RedisFixedWindowRateLimiter(counter, "math-agent:test:rl");

        RateLimitUsage usage = rateLimiter.check(
                "default:/api/retrieval/textbooks/search:device-1",
                2,
                Duration.ofMinutes(1),
                Instant.parse("2026-06-28T10:15:37Z"));

        assertThat(usage.limit()).isEqualTo(2);
        assertThat(usage.used()).isEqualTo(3);
        assertThat(usage.exceeded()).isTrue();
    }

    private record RedisCounterCall(String key, Duration ttl) {
    }

    private static class CapturingRedisRateLimitCounter implements RedisRateLimitCounter {
        private final long nextValue;
        private final List<RedisCounterCall> calls = new ArrayList<>();

        CapturingRedisRateLimitCounter(long nextValue) {
            this.nextValue = nextValue;
        }

        @Override
        public long incrementAndExpireOnFirstUse(String key, Duration ttl) {
            calls.add(new RedisCounterCall(key, ttl));
            return nextValue;
        }

        List<RedisCounterCall> calls() {
            return calls;
        }
    }
}
