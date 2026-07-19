package com.doob.mathagent.agent.service;

import com.doob.mathagent.infrastructure.security.RequestSubject;
import com.doob.mathagent.securityrisk.service.CapabilityTokenService;
import org.springframework.stereotype.Service;

/**
 * Agent-run capability verifier backed by the shared capability token service.
 */
@Service
public class CapabilityAgentRunVerifier implements AgentRunCapabilityVerifier {

    private final CapabilityTokenService tokenService;

    /**
     * Creates the verifier.
     *
     * @param tokenService capability token service
     */
    public CapabilityAgentRunVerifier(CapabilityTokenService tokenService) {
        this.tokenService = tokenService;
    }

    /**
     * Consumes a one-time token for the requested agent execution.
     */
    @Override
    public boolean verify(String token, String action, String path, String requestHash, RequestSubject subject) {
        return tokenService.consume(token, action, path, requestHash, subject).allowed();
    }
}
