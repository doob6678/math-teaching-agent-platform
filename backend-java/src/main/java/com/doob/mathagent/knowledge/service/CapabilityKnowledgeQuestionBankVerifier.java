package com.doob.mathagent.knowledge.service;

import com.doob.mathagent.infrastructure.security.RequestSubject;
import com.doob.mathagent.securityrisk.service.CapabilityTokenService;
import org.springframework.stereotype.Service;

/**
 * Capability verifier backed by the shared one-time capability token service.
 */
@Service
public class CapabilityKnowledgeQuestionBankVerifier implements KnowledgeQuestionBankCapabilityVerifier {

    private final CapabilityTokenService tokenService;

    /**
     * Creates a verifier.
     *
     * @param tokenService shared capability token service
     */
    public CapabilityKnowledgeQuestionBankVerifier(CapabilityTokenService tokenService) {
        this.tokenService = tokenService;
    }

    /**
     * Consumes a matching one-time token.
     */
    @Override
    public boolean verify(String token, String action, String path, String requestHash, RequestSubject subject) {
        return tokenService.consume(token, action, path, requestHash, subject).allowed();
    }
}
