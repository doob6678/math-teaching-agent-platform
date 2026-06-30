package com.doob.mathagent.student.service;

import com.doob.mathagent.infrastructure.security.RequestSubject;
import com.doob.mathagent.securityrisk.service.CapabilityTokenService;
import org.springframework.stereotype.Service;

/**
 * Capability verifier backed by the shared one-time token service.
 */
@Service
public class CapabilityStudentLearningSnapshotVerifier implements StudentLearningSnapshotCapabilityVerifier {

    private final CapabilityTokenService tokenService;

    /**
     * Creates a capability verifier for student learning snapshot refreshes.
     *
     * @param tokenService shared capability token service
     */
    public CapabilityStudentLearningSnapshotVerifier(CapabilityTokenService tokenService) {
        this.tokenService = tokenService;
    }

    /**
     * Consumes a matching one-time capability token.
     */
    @Override
    public boolean verify(String token, String action, String path, String requestHash, RequestSubject subject) {
        return tokenService.consume(token, action, path, requestHash, subject).allowed();
    }
}
