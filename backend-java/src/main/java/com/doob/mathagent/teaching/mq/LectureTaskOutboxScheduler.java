package com.doob.mathagent.teaching.mq;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Scheduled recovery path that closes the database-to-broker crash window. */
@Component
public class LectureTaskOutboxScheduler {
    private final LectureTaskOutboxPublisher publisher;
    public LectureTaskOutboxScheduler(LectureTaskOutboxStore store, LectureTaskPublisher publisher) { this.publisher = new LectureTaskOutboxPublisher(store, publisher, 100); }
    @Scheduled(fixedDelayString = "${math-agent.teaching.lecture-task.outbox-fixed-delay-ms:1000}")
    public void publishPendingEvents() { publisher.publishPendingEvents(); }
}
