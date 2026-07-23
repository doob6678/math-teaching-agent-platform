package com.doob.mathagent.teaching.mq;

import java.time.Instant;

/** Opaque ownership token returned only by an atomic lease acquisition. */
public record LectureTaskLease(String taskId, String token, String workerId, int retryCount, Instant expiresAt) {
}
