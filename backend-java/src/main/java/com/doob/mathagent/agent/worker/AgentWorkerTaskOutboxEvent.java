package com.doob.mathagent.agent.worker;

import java.time.Instant;

/** One durable, versioned broker dispatch event for an opaque Agent Worker command. */
public record AgentWorkerTaskOutboxEvent(
        String eventId,
        String taskId,
        int dispatchVersion,
        String agentCode,
        String stageCode,
        String status,
        int publishAttempt,
        Instant nextAttemptAt,
        Instant publishLeaseUntil,
        String lockedBy,
        String lastError,
        Instant createdAt) {
}
