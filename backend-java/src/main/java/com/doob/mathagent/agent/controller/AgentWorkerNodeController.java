package com.doob.mathagent.agent.controller;

import com.doob.mathagent.agent.dto.AgentWorkerHeartbeatRequest;
import com.doob.mathagent.agent.dto.AgentWorkerRegistrationRequest;
import com.doob.mathagent.agent.service.AgentWorkerRegistryService;
import com.doob.mathagent.agent.worker.AgentWorkerNode;
import java.util.List;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Worker-only registration APIs and an operator-visible node status endpoint. */
@RestController
public class AgentWorkerNodeController {
    private final AgentWorkerRegistryService registry; private final Environment environment;
    public AgentWorkerNodeController(AgentWorkerRegistryService registry, Environment environment) { this.registry=registry; this.environment=environment; }
    @PostMapping("/internal/agent-workers/register") public AgentWorkerNode register(@RequestHeader("X-Agent-Worker-Key") String key, @RequestBody AgentWorkerRegistrationRequest request) { authorize(key); return registry.register(request); }
    @PostMapping("/internal/agent-workers/{workerId}/heartbeat") public AgentWorkerNode heartbeat(@RequestHeader("X-Agent-Worker-Key") String key, @PathVariable String workerId, @RequestBody AgentWorkerHeartbeatRequest request) { authorize(key); return registry.heartbeat(workerId, request); }
    @GetMapping("/api/agents/workers") public List<AgentWorkerNode> nodes() { return registry.nodes(); }
    // Constant-time comparison mirrors AgentToolBrokerController.authorize: a plain String.equals leaks the matched
    // prefix length through timing, and this endpoint shares the same worker key, so both checks must use the same
    // MessageDigest.isEqual contract (changed in one place only would re-open the gap).
    private void authorize(String actual) { String expected=environment.getProperty("math-agent.agent-worker.shared-key", ""); if (expected.isBlank() || actual == null || !java.security.MessageDigest.isEqual(expected.getBytes(java.nio.charset.StandardCharsets.UTF_8), actual.getBytes(java.nio.charset.StandardCharsets.UTF_8))) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Agent Worker key is invalid"); }
}
