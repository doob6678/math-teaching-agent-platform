package com.doob.mathagent.teaching.service;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Runtime policy for the teaching AI draft stage.
 */
@Component
@ConfigurationProperties(prefix = "math-agent.teaching.ai-draft")
public class TeachingAiDraftProperties {

    private static final int MAX_ALLOWED_RETRIES = 3;

    /** Maximum repair/retry attempts per provider after the first model call. */
    private int maxRetries = 1;

    /**
     * Returns the configured retry count.
     *
     * @return configured retry count
     */
    public int getMaxRetries() {
        return maxRetries;
    }

    /**
     * Sets the configured retry count.
     *
     * @param maxRetries configured retry count
     */
    public void setMaxRetries(int maxRetries) {
        this.maxRetries = maxRetries;
    }

    /**
     * Returns a bounded retry count so a bad env value cannot create runaway model calls.
     *
     * @return retry count in the supported range
     */
    public int resolvedMaxRetries() {
        return Math.max(0, Math.min(maxRetries, MAX_ALLOWED_RETRIES));
    }
}
