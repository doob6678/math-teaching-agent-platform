package com.doob.mathagent.teaching.mq;

import java.time.Duration;
import java.time.Instant;

/** Atomic lease state machine protecting lecture DAG execution from AMQP redelivery. */
public interface LectureTaskLeaseStore {
    LectureTaskLease tryAcquire(String taskId, String workerId, Instant now, Duration leaseDuration);
    boolean complete(LectureTaskLease lease);
    /** @return true when a retry is permitted; false means the task is terminal and must be dead-lettered. */
    boolean failOrRetry(LectureTaskLease lease, String error, int maximumAttempts);
}
