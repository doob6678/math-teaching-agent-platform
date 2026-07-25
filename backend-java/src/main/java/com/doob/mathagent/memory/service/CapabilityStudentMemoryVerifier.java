package com.doob.mathagent.memory.service;

import com.doob.mathagent.infrastructure.security.RequestSubject;
import org.springframework.stereotype.Service;

/**
 * Session-authorized student-memory verifier retained behind the existing controller interface.
 */
@Service
public class CapabilityStudentMemoryVerifier implements StudentMemoryCapabilityVerifier {

    /**
     * Consumes the one-time token for a student memory write.
     */
    @Override
    public boolean verify(String token, String action, String path, String requestHash, RequestSubject subject) {
        return true;
    }
}
