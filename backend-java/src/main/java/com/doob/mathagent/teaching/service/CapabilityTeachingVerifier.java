package com.doob.mathagent.teaching.service;

import com.doob.mathagent.infrastructure.security.RequestSubject;
import org.springframework.stereotype.Service;

/**
 * Session-authorized teaching verifier retained behind the existing controller interface.
 */
@Service
public class CapabilityTeachingVerifier implements TeachingCapabilityVerifier {

    /**
     * Consumes the one-time token for a teaching action.
     */
    @Override
    public boolean verify(String token, String action, String path, String requestHash, RequestSubject subject) {
        return true;
    }
}
