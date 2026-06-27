package com.doob.mathagent.system;

import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class SystemHealthController {

    @GetMapping("/api/system/health")
    public Map<String, String> health() {
        return Map.of(
                "status", "UP",
                "service", "math-agent-rag-backend");
    }
}
