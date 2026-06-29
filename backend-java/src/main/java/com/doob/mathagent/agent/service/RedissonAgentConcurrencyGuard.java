package com.doob.mathagent.agent.service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Service;

/**
 * Redisson-backed distributed concurrency guard for agent execution.
 */
@Service
@ConditionalOnBean(RedissonClient.class)
public class RedissonAgentConcurrencyGuard implements AgentConcurrencyGuard {

    private static final String DEFAULT_PREFIX = "math-agent:agent:concurrency";

    private final RedissonClient redissonClient;
    private final String keyPrefix;

    /**
     * Creates a Redisson-backed guard with the default key prefix.
     *
     * @param redissonClient Redisson client
     */
    public RedissonAgentConcurrencyGuard(RedissonClient redissonClient) {
        this(redissonClient, DEFAULT_PREFIX);
    }

    /**
     * Creates a Redisson-backed guard with an explicit key prefix.
     *
     * @param redissonClient Redisson client
     * @param keyPrefix Redis key prefix
     */
    public RedissonAgentConcurrencyGuard(RedissonClient redissonClient, String keyPrefix) {
        this.redissonClient = redissonClient;
        this.keyPrefix = keyPrefix == null || keyPrefix.isBlank() ? DEFAULT_PREFIX : keyPrefix.strip();
    }

    /**
     * Tries to lock every concurrency key with zero wait and a bounded lease.
     */
    @Override
    public Optional<AgentConcurrencyLease> tryAcquire(List<String> keys, String traceId, Duration leaseTime) {
        List<RLock> acquired = new ArrayList<>();
        for (String key : normalizeKeys(keys)) {
            RLock lock = redissonClient.getLock(keyPrefix + ":" + key);
            try {
                boolean locked = lock.tryLock(0L, leaseTime.toMillis(), TimeUnit.MILLISECONDS);
                if (!locked) {
                    unlockAll(acquired);
                    return Optional.empty();
                }
                acquired.add(lock);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                unlockAll(acquired);
                return Optional.empty();
            }
        }
        return Optional.of(() -> unlockAll(acquired));
    }

    /**
     * Unlocks all held locks for this thread.
     */
    private static void unlockAll(List<RLock> locks) {
        for (int index = locks.size() - 1; index >= 0; index -= 1) {
            RLock lock = locks.get(index);
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    /**
     * Deduplicates and strips planned concurrency keys.
     */
    private static List<String> normalizeKeys(List<String> keys) {
        if (keys == null || keys.isEmpty()) {
            return List.of();
        }
        Set<String> normalized = new LinkedHashSet<>();
        for (String key : keys) {
            if (key != null && !key.isBlank()) {
                normalized.add(key.strip());
            }
        }
        return List.copyOf(normalized);
    }
}
