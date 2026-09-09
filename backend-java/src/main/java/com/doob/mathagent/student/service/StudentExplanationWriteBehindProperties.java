package com.doob.mathagent.student.service;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

/**
 * Redis settings for the student-explanation SSE event write-behind buffer.
 *
 * <p>Every public stream delta currently costs one MySQL insert on the request path (up to dozens per second per
 * active run), which pressures the 25-connection Hikari pool. The buffer moves those writes to Redis and drains them
 * in batches; these values bound the buffer size, the drain cadence and how long an un-drained buffer survives in
 * Redis before it is assumed lost.</p>
 */
@ConfigurationProperties(prefix = "math-agent.redis.student-explanation-write-behind")
public record StudentExplanationWriteBehindProperties(
        boolean enabled,
        String keyPrefix,
        int batchSize,
        Duration bufferTtl,
        int maxBufferedEvents) {

    @ConstructorBinding
    public StudentExplanationWriteBehindProperties {
    }

    /** Namespace for the sequence key, the per-run event lists and the active-run index set. */
    public String normalizedKeyPrefix() {
        return keyPrefix == null || keyPrefix.isBlank()
                ? "math-agent:student:explanation-events:v1"
                : keyPrefix.strip();
    }

    /** Events taken from one run's Redis list per flush round; smaller values spread MySQL work, larger cut round count. */
    public int normalizedBatchSize() {
        return batchSize <= 0 ? 200 : batchSize;
    }

    /**
     * TTL refreshed on every buffered append. It only needs to outlive a JVM restart so the boot flush can drain the
     * buffer; a buffer older than this is treated as abandoned (its run cannot still be replaying after 5 minutes).
     */
    public Duration normalizedBufferTtl() {
        return bufferTtl == null || bufferTtl.isNegative() || bufferTtl.isZero() ? Duration.ofHours(6) : bufferTtl;
    }

    /**
     * Hard per-run buffer ceiling. When the flusher cannot keep up (MySQL slow, Redis-only backlog) the buffer refuses
     * further entries and the store degrades that append to a direct MySQL write, so a backlog never grows unbounded.
     */
    public int normalizedMaxBufferedEvents() {
        return maxBufferedEvents <= 0 ? 5_000 : Math.max(maxBufferedEvents, normalizedBatchSize());
    }
}
