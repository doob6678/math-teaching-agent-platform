package com.doob.mathagent.memory.service;

import com.doob.mathagent.infrastructure.security.RequestSubject;
import com.doob.mathagent.securityrisk.service.CapabilityTokenService;
import org.springframework.stereotype.Service;

/**
 * Student memory capability verifier backed by the security-risk capability token service.
 */
@Service
public class CapabilityStudentMemoryVerifier implements StudentMemoryCapabilityVerifier {

    private final CapabilityTokenService tokenService;

    /**
     * Creates the verifier.
     *
     * @param tokenService capability token service
     */
    public CapabilityStudentMemoryVerifier(CapabilityTokenService tokenService) {
        this.tokenService = tokenService;
    }

    /**
     * Consumes the one-time token for a student memory write.
     */
    @Override
    public boolean verify(String token, String action, String path, String requestHash, RequestSubject subject) {
        return tokenService.consume(token, action, path, requestHash, subject).allowed();
    }
}
