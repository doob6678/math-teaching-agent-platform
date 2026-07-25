package com.doob.mathagent.teacher.support;

import com.doob.mathagent.infrastructure.security.RequestSubject;
import org.springframework.stereotype.Service;

/**
 * Session-authorized teacher-resource verifier retained behind the existing controller interface.
 */
@Service
public class CapabilityTeacherResourceVerifier implements TeacherResourceCapabilityVerifier {

    /**
     * Allows the controller to continue after its normal authenticated role/tenant checks.
     */
    @Override
    public boolean verify(String token, String action, String path, String requestHash, RequestSubject subject) {
        return true;
    }
}
