package com.doob.mathagent.knowledge.service;

import com.doob.mathagent.infrastructure.security.RequestSubject;

/**
 * Verifies one-time capability tokens before high-value knowledge/question bank writes.
 */
@FunctionalInterface
public interface KnowledgeQuestionBankCapabilityVerifier {

    /**
     * Verifies whether a write request may proceed.
     *
     * @param token capability token
     * @param action expected action
     * @param path API path bound to the token
     * @param requestHash exact request hash
     * @param subject backend subject
     * @return true when accepted
     */
    boolean verify(String token, String action, String path, String requestHash, RequestSubject subject);
}
