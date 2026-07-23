package com.doob.mathagent.agent.worker;

import com.doob.mathagent.agent.dto.AgentWorkerHeartbeatRequest;
import com.doob.mathagent.agent.dto.AgentWorkerRegistrationRequest;
import com.doob.mathagent.agent.service.AgentWorkerRegistryService;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Registers the standalone process and periodically proves liveness to the shared control-plane database. */
@Component
@ConditionalOnProperty(prefix = "math-agent.agent-worker.runtime", name = "enabled", havingValue = "true")
public class AgentWorkerRuntimeHeartbeat {
    private final AgentWorkerRegistryService registry; private final Environment environment;
    public AgentWorkerRuntimeHeartbeat(AgentWorkerRegistryService registry, Environment environment) { this.registry=registry; this.environment=environment; register(); }
    private void register() { registry.register(new AgentWorkerRegistrationRequest(workerId(), environment.getProperty("math-agent.agent-worker.runtime.version", "0.1.0"), AgentWorkerRabbitConfiguration.SUPPORTED_AGENT_CODES, Integer.parseInt(environment.getProperty("math-agent.agent-worker.runtime.max-concurrency", "1")))); }
    @Scheduled(fixedDelayString = "${math-agent.agent-worker.runtime.heartbeat-milliseconds:15000}")
    public void heartbeat() { registry.heartbeat(workerId(), new AgentWorkerHeartbeatRequest(0, 0, 0, null)); }
    private String workerId() { return environment.getProperty("math-agent.agent-worker.runtime.worker-id", "local-agent-worker"); }
}
