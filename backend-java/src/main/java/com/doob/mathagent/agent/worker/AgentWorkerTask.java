package com.doob.mathagent.agent.worker;

import java.time.Instant;

/** Lease-protected command metadata; its payload stays in MySQL while MQ transfers only opaque identifiers. */
public record AgentWorkerTask(
        String taskId, String workflowId, String tenantId, String agentCode, String stageCode, String status,
        int attempt, int dispatchVersion, String leaseToken, Instant leaseExpiresAt, String workerId, String requestJson, String errorSummary,
        Instant createdAt, Instant updatedAt) {
}
