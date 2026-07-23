package com.doob.mathagent.teaching.mq;

import java.time.Instant;

/** Durable event that carries only the authoritative teaching task identifier. */
public record LectureTaskOutboxEvent(String eventId, String taskId, Instant createdAt) {
}
