package com.doob.mathagent.agent.controller;

import com.doob.mathagent.agent.service.AgentModelHealthService;
import com.doob.mathagent.agent.vo.AgentModelHealthResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * API for checking real backend AI provider reachability.
 */
@RestController
public class AgentModelHealthController {

    private final AgentModelHealthService healthService;

    /**
     * Creates the controller.
     *
     * @param healthService provider health service
     */
    public AgentModelHealthController(AgentModelHealthService healthService) {
        this.healthService = healthService;
    }

    /**
     * Runs a compact real health check for configured providers.
     *
     * @return safe provider health response
     */
    @GetMapping("/api/agents/model-health")
    public AgentModelHealthResponse modelHealth() {
        return healthService.checkHealth();
    }
}
