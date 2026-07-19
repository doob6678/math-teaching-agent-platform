package com.doob.mathagent.agent.worker;

import java.time.Instant;
import java.util.List;

/** Durable, control-plane view of one independently deployed Agent Worker. */
public record AgentWorkerNode(
        String workerId, String workerVersion, List<String> supportedAgents, int maxConcurrency, int currentLoad,
        String status, Instant lastHeartbeatAt, long completedTaskCount, long failedTaskCount, String lastErrorSummary) {
}
