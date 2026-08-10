package com.doob.mathagent.agent.worker;

import com.doob.mathagent.agent.service.AgentWorkerRegistryService;
import java.time.Duration;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Control-plane safety net that removes dead Workers from scheduling eligibility after the configured heartbeat gap. */
@Component
@ConditionalOnProperty(name = "math-agent.rabbitmq.listeners-enabled", havingValue = "true")
public class AgentWorkerNodeLivenessMonitor {
    private final AgentWorkerRegistryService registry; private final Environment environment;
    public AgentWorkerNodeLivenessMonitor(AgentWorkerRegistryService registry, Environment environment) { this.registry=registry; this.environment=environment; }
    @Scheduled(fixedDelayString = "${math-agent.agent-worker.liveness-check-milliseconds:15000}")
    public void markStaleNodesOffline() { registry.markOffline(Duration.ofMillis(Long.parseLong(environment.getProperty("math-agent.agent-worker.heartbeat-timeout-milliseconds", "45000")))); }
}
