package com.doob.mathagent.securityrisk;

import static org.assertj.core.api.Assertions.assertThat;

import com.doob.mathagent.securityrisk.service.CapabilityTokenRecord;
import com.doob.mathagent.securityrisk.service.RedissonCapabilityTokenStore;
import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;

class RedissonCapabilityTokenStoreTest {

    @Test
    void savesTokenRecordWithTtlAndReadsItByPrefixedKey() {
        FakeRedisson redisson = new FakeRedisson();
        RedissonCapabilityTokenStore store =
                new RedissonCapabilityTokenStore(redisson.client(), "math-agent:test:capability");
        CapabilityTokenRecord record = record("token-1", false);

        CapabilityTokenRecord saved = store.save(record);
        Optional<CapabilityTokenRecord> found = store.find("token-1");

        assertThat(saved).isEqualTo(record);
        assertThat(found).contains(record);
        assertThat(redisson.bucket("math-agent:test:capability:token-1").ttlMillis()).isPositive();
    }

    @Test
    void consumesTokenOnlyOnceWithCompareAndSet() {
        FakeRedisson redisson = new FakeRedisson();
        RedissonCapabilityTokenStore store =
                new RedissonCapabilityTokenStore(redisson.client(), "math-agent:test:capability");
        store.save(record("token-1", false));

        Optional<CapabilityTokenRecord> first = store.consumeIfUnused("token-1");
        Optional<CapabilityTokenRecord> second = store.consumeIfUnused("token-1");

        assertThat(first).isPresent();
        assertThat(first.orElseThrow().consumed()).isTrue();
        assertThat(second).isEmpty();
        assertThat(redisson.bucket("math-agent:test:capability:token-1").compareAndSetCalls()).hasValue(1L);
    }

    /**
     * Builds a token record with stable fields for store behavior tests.
     */
    private static CapabilityTokenRecord record(String token, boolean consumed) {
        return new CapabilityTokenRecord(
                token,
                "school-a",
                "student",
                "student-1",
                "teaching:submit",
                "/api/teaching/tasks",
                "hash-1",
                "client-1",
                0.2,
                Instant.now().plusSeconds(120),
                consumed);
    }

    /**
     * Lightweight Redisson proxy that records RBucket operations without connecting to Redis.
     */
    private static final class FakeRedisson {

        private final Map<String, FakeBucket> buckets = new ConcurrentHashMap<>();

        /**
         * Returns a RedissonClient proxy supporting getBucket only.
         */
        RedissonClient client() {
            return (RedissonClient) Proxy.newProxyInstance(
                    getClass().getClassLoader(),
                    new Class<?>[] {RedissonClient.class},
                    (proxy, method, args) -> {
                        if ("getBucket".equals(method.getName()) && args.length >= 1) {
                            return bucket((String) args[0]).proxy();
                        }
                        return defaultValue(method.getReturnType());
                    });
        }

        /**
         * Returns the fake bucket state for a Redis key.
         */
        FakeBucket bucket(String key) {
            return buckets.computeIfAbsent(key, ignored -> new FakeBucket());
        }
    }

    /**
     * In-memory RBucket proxy state used to verify Redisson API usage.
     */
    private static final class FakeBucket {

        private Object value;
        private long ttlMillis;
        private final AtomicLong compareAndSetCalls = new AtomicLong();

        /**
         * Returns an RBucket proxy implementing the methods used by the store.
         */
        RBucket<Object> proxy() {
            return (RBucket<Object>) Proxy.newProxyInstance(
                    getClass().getClassLoader(),
                    new Class<?>[] {RBucket.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "set" -> {
                            value = args[0];
                            ttlMillis = TimeUnit.MILLISECONDS.convert((long) args[1], (TimeUnit) args[2]);
                            yield null;
                        }
                        case "get" -> value;
                        case "compareAndSet" -> {
                            compareAndSetCalls.incrementAndGet();
                            boolean matches = value != null && value.equals(args[0]);
                            if (matches) {
                                value = args[1];
                            }
                            yield matches;
                        }
                        default -> defaultValue(method.getReturnType());
                    });
        }

        /**
         * Returns the TTL captured from set(value, ttl, unit).
         */
        long ttlMillis() {
            return ttlMillis;
        }

        /**
         * Returns the number of compareAndSet calls made by the store.
         */
        AtomicLong compareAndSetCalls() {
            return compareAndSetCalls;
        }
    }

    /**
     * Returns Java default values for unsupported proxy methods.
     */
    private static Object defaultValue(Class<?> returnType) {
        if (!returnType.isPrimitive()) {
            return null;
        }
        if (boolean.class.equals(returnType)) {
            return false;
        }
        if (long.class.equals(returnType)) {
            return 0L;
        }
        if (int.class.equals(returnType)) {
            return 0;
        }
        return null;
    }
}
