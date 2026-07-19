package com.doob.mathagent.student.service;

import com.doob.mathagent.infrastructure.security.RequestSubject;

/**
 * Verifies one-time capability tokens before refreshing persisted student learning snapshots.
 */
@FunctionalInterface
public interface StudentLearningSnapshotCapabilityVerifier {

    /**
     * Verifies whether a snapshot refresh may proceed.
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
