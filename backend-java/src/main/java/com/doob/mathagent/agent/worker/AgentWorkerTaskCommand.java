package com.doob.mathagent.agent.worker;

/** Token-free RabbitMQ envelope; the Worker reads protected routing and payload data only after claiming the task. */
public record AgentWorkerTaskCommand(String taskId) {
}
