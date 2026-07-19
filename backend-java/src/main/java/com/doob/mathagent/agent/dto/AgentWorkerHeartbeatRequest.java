package com.doob.mathagent.agent.dto;
/** Periodic worker health data. */
public record AgentWorkerHeartbeatRequest(int currentLoad, long completedTaskCount, long failedTaskCount, String lastErrorSummary) {}
