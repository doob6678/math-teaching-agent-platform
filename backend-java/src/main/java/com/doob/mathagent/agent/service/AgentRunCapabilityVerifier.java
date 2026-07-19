package com.doob.mathagent.agent.service;

import com.doob.mathagent.infrastructure.security.RequestSubject;

/**
 * Verifies one-time capability tokens before high-value agent executions.
 */
@FunctionalInterface
public interface AgentRunCapabilityVerifier {

    /**
     * Verifies whether an agent execution may proceed.
     *
     * @param token capability token
     * @param action expected action
     * @param path API path
     * @param requestHash request body hash
     * @param subject backend subject
     * @return true when accepted
     */
    boolean verify(String token, String action, String path, String requestHash, RequestSubject subject);
}
