package com.doob.mathagent.agent.dto;
import java.util.List;
/** Worker-owned registration data; authentication is supplied separately through the worker key header. */
public record AgentWorkerRegistrationRequest(String workerId, String workerVersion, List<String> supportedAgents, int maxConcurrency) {}
