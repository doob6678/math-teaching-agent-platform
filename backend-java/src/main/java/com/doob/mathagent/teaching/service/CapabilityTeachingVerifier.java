package com.doob.mathagent.teaching.service;

import com.doob.mathagent.infrastructure.security.RequestSubject;
import com.doob.mathagent.securityrisk.service.CapabilityTokenService;
import org.springframework.stereotype.Service;

/**
 * Teaching capability verifier backed by the security-risk capability token service.
 */
@Service
public class CapabilityTeachingVerifier implements TeachingCapabilityVerifier {

    private final CapabilityTokenService tokenService;

    /**
     * Creates the verifier.
     *
     * @param tokenService capability token service
     */
    public CapabilityTeachingVerifier(CapabilityTokenService tokenService) {
        this.tokenService = tokenService;
    }

    /**
     * Consumes the one-time token for a teaching action.
     */
    @Override
    public boolean verify(String token, String action, String path, String requestHash, RequestSubject subject) {
        return tokenService.consume(token, action, path, requestHash, subject).allowed();
    }
}
