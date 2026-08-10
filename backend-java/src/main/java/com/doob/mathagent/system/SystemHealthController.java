package com.doob.mathagent.system;

import java.util.Map;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class SystemHealthController {

    private final SystemRuntimeStatusService runtimeStatusService;

    @Autowired
    public SystemHealthController(SystemRuntimeStatusService runtimeStatusService) {
        this.runtimeStatusService = Objects.requireNonNull(runtimeStatusService, "runtimeStatusService");
    }

    @GetMapping("/api/system/health")
    public Map<String, String> health() {
        try {
            SystemRuntimeStatusResponse runtime = runtimeStatusService.status();
            SystemRuntimeStatusResponse.DeploymentStatus deployment = runtime.deployment();
            return Map.of(
                    "status", deployment.ready() ? "UP" : "DOWN",
                    "service", "math-agent-rag-backend",
                    "mode", deployment.mode(),
                    "blockingIssues", String.join(",", deployment.blockingIssues()),
                    "mysql", Boolean.toString(runtime.dependencies().mysql()),
                    "redis", Boolean.toString(runtime.dependencies().redis()),
                    "rabbitmq", Boolean.toString(runtime.dependencies().rabbitmq()),
                    "aiWorker", Boolean.toString(runtime.dependencies().worker()),
                    "flyway", Boolean.toString(runtime.dependencies().flyway()));
        } catch (RuntimeException exception) {
            // A dependency probe must fail closed: a probe exception is not evidence that the service is healthy.
            return Map.of(
                    "status", "DOWN",
                    "service", "math-agent-rag-backend",
                    "mode", "probe_failed",
                    "blockingIssues", "HEALTH_PROBE_FAILED");
        }
    }

    /** Liveness intentionally has no dependency checks; orchestration can distinguish a live process from readiness. */
    @GetMapping("/api/system/liveness")
    public Map<String, String> liveness() {
        return Map.of("status", "UP", "service", "math-agent-rag-backend", "mode", "liveness");
    }

    /** Readiness is the dependency-aware health response. */
    @GetMapping("/api/system/readiness")
    public Map<String, String> readiness() {
        return health();
    }

    @GetMapping("/api/system/runtime")
    public SystemRuntimeStatusResponse runtime() {
        return runtimeStatusService.status();
    }
}
