package com.doob.mathagent.agent.service;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * Guard for acquiring agent execution concurrency keys before expensive model/tool work starts.
 */
public interface AgentConcurrencyGuard {

    /**
     * Tries to acquire all concurrency keys atomically.
     *
     * @param keys planned concurrency keys from agent run planning
     * @param traceId trace id that owns the lease
     * @param leaseTime maximum lease time
     * @return lease when all keys are acquired; empty when any key is busy
     */
    Optional<AgentConcurrencyLease> tryAcquire(List<String> keys, String traceId, Duration leaseTime);
}
