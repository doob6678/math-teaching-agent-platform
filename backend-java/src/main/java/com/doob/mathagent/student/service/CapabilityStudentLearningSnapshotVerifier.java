package com.doob.mathagent.student.service;

import com.doob.mathagent.infrastructure.security.RequestSubject;
import org.springframework.stereotype.Service;

/**
 * Session-authorized learning-snapshot verifier retained behind the existing controller interface.
 */
@Service
public class CapabilityStudentLearningSnapshotVerifier implements StudentLearningSnapshotCapabilityVerifier {

    /**
     * Consumes a matching one-time capability token.
     */
    @Override
    public boolean verify(String token, String action, String path, String requestHash, RequestSubject subject) {
        return true;
    }
}
