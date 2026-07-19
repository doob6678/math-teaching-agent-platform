package com.doob.mathagent.agent.service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Local single-process agent concurrency guard for development and tests.
 */
public class InMemoryAgentConcurrencyGuard implements AgentConcurrencyGuard {

    private final Set<String> activeKeys = ConcurrentHashMap.newKeySet();

    /**
     * Atomically acquires all keys in this JVM and rolls back partial acquisition on conflict.
     */
    @Override
    public synchronized Optional<AgentConcurrencyLease> tryAcquire(
            List<String> keys,
            String traceId,
            Duration leaseTime) {
        List<String> normalizedKeys = normalizeKeys(keys);
        List<String> acquired = new ArrayList<>();
        for (String key : normalizedKeys) {
            if (!activeKeys.add(key)) {
                activeKeys.removeAll(acquired);
                return Optional.empty();
            }
            acquired.add(key);
        }
        return Optional.of(() -> release(acquired));
    }

    /**
     * Releases previously acquired keys.
     */
    private synchronized void release(List<String> acquired) {
        activeKeys.removeAll(acquired);
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
