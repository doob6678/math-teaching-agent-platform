package com.doob.mathagent.agent.worker;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/** Durable state machine for Agent Worker broker publication. */
public interface AgentWorkerTaskOutboxStore {
    void enqueue(AgentWorkerTask task);
    List<AgentWorkerTaskOutboxEvent> claimReady(String publisherId, Instant now, Duration leaseDuration, int limit);
    boolean markPublished(AgentWorkerTaskOutboxEvent event, Instant publishedAt);
    void releaseForRetry(AgentWorkerTaskOutboxEvent event, Instant nextAttemptAt, String errorSummary);
    int recoverExpiredPublishing(Instant now);
    List<AgentWorkerTask> findOrphanQueued(Instant olderThan, int limit);
    long pendingCount();
    Instant oldestPendingCreatedAt();
}
