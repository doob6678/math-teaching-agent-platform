package com.doob.mathagent.agent.worker;

/** Token-free RabbitMQ envelope; the Worker reads the protected payload only after claiming the durable task. */
public record AgentWorkerTaskCommand(String taskId, String workflowId, String stageCode, String leaseToken) {
}
