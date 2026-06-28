package com.doob.mathagent.teaching.service;

import com.doob.mathagent.infrastructure.security.RequestSubject;

/**
 * Verifies one-time capability tokens before high-value teaching operations.
 */
@FunctionalInterface
public interface TeachingCapabilityVerifier {

    /**
     * Verifies whether a teaching request may proceed.
     *
     * @param token capability token
     * @param action expected action
     * @param path API path
     * @param requestHash request hash
     * @param subject backend subject
     * @return true when accepted
     */
    boolean verify(String token, String action, String path, String requestHash, RequestSubject subject);
}
