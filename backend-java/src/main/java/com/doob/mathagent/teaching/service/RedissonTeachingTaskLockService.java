package com.doob.mathagent.teaching.service;

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Service;

/**
 * Redisson lock adapter for single-flight teaching task execution.
 *
 * <p>Later persistence stages can call this service before expensive DAG steps to prevent cache breakdown and duplicate
 * generation for the same idempotency key.</p>
 */
@Service
@ConditionalOnBean(RedissonClient.class)
public class RedissonTeachingTaskLockService {

    private final RedissonClient redissonClient;

    /**
     * Injects the official Redisson client.
     *
     * @param redissonClient Redisson client configured by the starter
     */
    public RedissonTeachingTaskLockService(RedissonClient redissonClient) {
        this.redissonClient = redissonClient;
    }

    /**
     * Runs work while holding a lease-based distributed lock.
     *
     * @param lockKey logical lock key
     * @param leaseTime maximum lock lease time
     * @param supplier protected work
     * @param <T> result type
     * @return protected work result
     */
    public <T> T withTaskLock(String lockKey, Duration leaseTime, Supplier<T> supplier) {
        RLock lock = redissonClient.getLock("math-agent:teaching:lock:" + lockKey);
        lock.lock(leaseTime.toMillis(), TimeUnit.MILLISECONDS);
        try {
            return supplier.get();
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }
}
