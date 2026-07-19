package com.doob.mathagent.protocol.controller;

import com.doob.mathagent.protocol.service.ProtocolDiscoveryService;
import com.doob.mathagent.protocol.vo.A2aAgentCardResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * A2A Agent Card discovery API. This controller exposes platform metadata only.
 */
@RestController
public class A2aAgentCardController {

    private final ProtocolDiscoveryService discoveryService;

    /**
     * Creates the A2A discovery controller.
     *
     * @param discoveryService metadata service
     */
    public A2aAgentCardController(ProtocolDiscoveryService discoveryService) {
        this.discoveryService = discoveryService;
    }

    /**
     * Returns the public A2A Agent Card for the math teaching platform.
     */
    @GetMapping("/api/a2a/.well-known/agent-card.json")
    public A2aAgentCardResponse agentCard() {
        return discoveryService.a2aAgentCard();
    }
}
