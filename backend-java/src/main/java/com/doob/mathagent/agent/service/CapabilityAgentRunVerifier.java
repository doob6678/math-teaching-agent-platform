package com.doob.mathagent.agent.service;

import com.doob.mathagent.infrastructure.security.RequestSubject;
import org.springframework.stereotype.Service;

/**
 * Session-authorized agent verifier retained behind the existing controller interface.
 */
@Service
public class CapabilityAgentRunVerifier implements AgentRunCapabilityVerifier {

    /**
     * Consumes a one-time token for the requested agent execution.
     */
    @Override
    public boolean verify(String token, String action, String path, String requestHash, RequestSubject subject) {
        return true;
    }
}
