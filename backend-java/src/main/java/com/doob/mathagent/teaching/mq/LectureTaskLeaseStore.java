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
    /**
     * 仅当前租约令牌可推进失败状态；显式结果避免把租约丢失误判为终态失败。
     */
    FailureOutcome failOrRetry(LectureTaskLease lease, String error, int maximumAttempts);

    /** 租约失败 CAS 的三种互斥结果，消费者据此决定是否写入任务快照或投递消息。 */
    enum FailureOutcome {
        RETRYING,
        TERMINAL_FAILURE,
        LEASE_LOST
    }
}
