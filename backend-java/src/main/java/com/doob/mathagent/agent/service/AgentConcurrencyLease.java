package com.doob.mathagent.agent.service;

/**
 * Lease returned after agent concurrency keys are acquired.
 */
@FunctionalInterface
public interface AgentConcurrencyLease extends AutoCloseable {

    /**
     * Releases all acquired concurrency keys.
     */
    @Override
    void close();
}
