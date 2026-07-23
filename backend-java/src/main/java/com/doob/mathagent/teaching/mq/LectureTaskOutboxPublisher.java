package com.doob.mathagent.teaching.mq;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Publishes durable pending events; failed sends remain pending for the next scan. */
public class LectureTaskOutboxPublisher {
    private static final Logger log = LoggerFactory.getLogger(LectureTaskOutboxPublisher.class);
    private final LectureTaskOutboxStore store;
    private final LectureTaskPublisher publisher;
    private final int batchSize;

    public LectureTaskOutboxPublisher(LectureTaskOutboxStore store, LectureTaskPublisher publisher, int batchSize) {
        this.store = store;
        this.publisher = publisher;
        this.batchSize = batchSize;
    }

    /** Sends one bounded batch and marks an event published only after the broker call returns successfully. */
    public void publishPendingEvents() {
        for (LectureTaskOutboxEvent event : store.findPending(batchSize)) {
            try {
                publisher.publish(event.taskId());
                store.markPublished(event.eventId());
            } catch (RuntimeException exception) {
                // Failure is intentionally non-terminal: retaining PENDING is the durable compensation contract.
                log.warn("lecture outbox publish deferred taskId={} eventId={} reason={}", event.taskId(), event.eventId(), exception.getMessage());
            }
        }
    }
}
