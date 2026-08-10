package com.doob.mathagent.teaching.mq;

import java.time.Duration;
import java.time.Instant;

/** Atomic lease state machine protecting lecture DAG execution from AMQP redelivery. */
public interface LectureTaskLeaseStore {
    LectureTaskLease tryAcquire(String taskId, String workerId, Instant now, Duration leaseDuration);
    /** Extends an active lease only when the caller still owns its token. */
    default boolean renew(LectureTaskLease lease, Instant expiresAt) { return false; }
    boolean complete(LectureTaskLease lease);
    /** Reclaims expired work through a new durable outbox generation after a worker process stops. */
    default java.util.List<String> reclaimExpired(Instant now, int limit) { return java.util.List.of(); }
    /** @return true when a retry is permitted; false means the task is terminal and must be dead-lettered. */
    boolean failOrRetry(LectureTaskLease lease, String error, int maximumAttempts);
}
