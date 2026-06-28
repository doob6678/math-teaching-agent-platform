package com.doob.mathagent.teacher.service;

import com.doob.mathagent.infrastructure.security.RequestSubject;
import com.doob.mathagent.securityrisk.service.CapabilityTokenService;
import org.springframework.stereotype.Service;

/**
 * Teacher resource capability verifier backed by the security-risk capability token service.
 */
@Service
public class CapabilityTeacherResourceVerifier implements TeacherResourceCapabilityVerifier {

    private final CapabilityTokenService tokenService;

    /**
     * Creates the verifier.
     *
     * @param tokenService capability token service
     */
    public CapabilityTeacherResourceVerifier(CapabilityTokenService tokenService) {
        this.tokenService = tokenService;
    }

    /**
     * Consumes the one-time token for a teacher resource mutation.
     */
    @Override
    public boolean verify(String token, String action, String path, String requestHash, RequestSubject subject) {
        return tokenService.consume(token, action, path, requestHash, subject).allowed();
    }
}
