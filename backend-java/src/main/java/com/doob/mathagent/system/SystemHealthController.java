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
        return Map.of(
                "status", "UP",
                "service", "math-agent-rag-backend");
    }

    @GetMapping("/api/system/runtime")
    public SystemRuntimeStatusResponse runtime() {
        return runtimeStatusService.status();
    }
}
