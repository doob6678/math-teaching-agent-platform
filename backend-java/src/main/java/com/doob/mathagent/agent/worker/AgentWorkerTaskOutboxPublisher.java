package com.doob.mathagent.agent.worker;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Bounded publisher for claimed outbox events; broker failures remain durable work rather than failed tasks. */
public class AgentWorkerTaskOutboxPublisher {
    private static final Logger log = LoggerFactory.getLogger(AgentWorkerTaskOutboxPublisher.class);
    private final AgentWorkerTaskOutboxStore store;
    private final AgentWorkerTaskPublisher publisher;
    private final String publisherId;
    private final int batchSize;
    private final Duration leaseDuration;
    private final Counter published;
    private final Counter failed;
    private final Counter retried;

    public AgentWorkerTaskOutboxPublisher(
            AgentWorkerTaskOutboxStore store,
            AgentWorkerTaskPublisher publisher,
            String publisherId,
            int batchSize,
            Duration leaseDuration,
            MeterRegistry meterRegistry) {
        this.store = store;
        this.publisher = publisher;
        this.publisherId = publisherId;
        this.batchSize = Math.max(1, batchSize);
        this.leaseDuration = leaseDuration;
        this.published = Counter.builder("agent_worker_outbox_publish_success_total").register(meterRegistry);
        this.failed = Counter.builder("agent_worker_outbox_publish_failure_total").register(meterRegistry);
        this.retried = Counter.builder("agent_worker_outbox_publish_retry_total").register(meterRegistry);
        Gauge.builder("agent_worker_outbox_pending_count", store, AgentWorkerTaskOutboxStore::pendingCount).register(meterRegistry);
        Gauge.builder("agent_worker_outbox_oldest_pending_age_seconds", store,
                value -> oldestPendingAgeSeconds(value, Instant.now())).register(meterRegistry);
    }

    /** Claims a bounded event batch, then marks each event published only after an ACK-confirmed broker send. */
    public void publishPendingEvents() {
        for (AgentWorkerTaskOutboxEvent event : store.claimReady(publisherId, Instant.now(), leaseDuration, batchSize)) {
            try {
                publisher.publish(event);
                if (store.markPublished(event, Instant.now())) {
                    published.increment();
                }
            } catch (RuntimeException exception) {
                failed.increment();
                retried.increment();
                Instant nextAttemptAt = Instant.now().plus(backoff(event.publishAttempt()));
                store.releaseForRetry(event, nextAttemptAt, exception.getMessage());
                log.warn("agent_worker_outbox_publish_deferred eventId={} taskId={} dispatchVersion={} reason={}",
                        event.eventId(), event.taskId(), event.dispatchVersion(), safe(exception));
            }
        }
    }

    private static Duration backoff(int publishAttempt) {
        long seconds = Math.min(300L, 1L << Math.min(8, Math.max(0, publishAttempt)));
        return Duration.ofSeconds(seconds);
    }

    private static double oldestPendingAgeSeconds(AgentWorkerTaskOutboxStore store, Instant now) {
        Instant createdAt = store.oldestPendingCreatedAt();
        return createdAt == null ? 0.0d : Math.max(0.0d, Duration.between(createdAt, now).toMillis() / 1_000.0d);
    }

    private static String safe(RuntimeException exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank() ? exception.getClass().getSimpleName()
                : message.substring(0, Math.min(300, message.length())).replaceAll("[\\r\\n]+", " ");
    }
}
