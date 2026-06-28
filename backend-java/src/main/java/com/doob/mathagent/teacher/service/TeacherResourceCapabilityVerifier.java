package com.doob.mathagent.teacher.service;

import com.doob.mathagent.infrastructure.security.RequestSubject;

/**
 * Verifies one-time capability tokens before high-value teacher resource mutations.
 */
@FunctionalInterface
public interface TeacherResourceCapabilityVerifier {

    /**
     * Verifies whether a teacher resource mutation may proceed.
     *
     * @param token capability token
     * @param action expected action
     * @param path API path bound to the capability
     * @param requestHash exact request hash supplied by the client
     * @param subject backend resolved subject
     * @return true when accepted
     */
    boolean verify(String token, String action, String path, String requestHash, RequestSubject subject);
}
