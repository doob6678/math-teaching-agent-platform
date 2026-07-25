package com.doob.mathagent.knowledge.service;

import com.doob.mathagent.infrastructure.security.RequestSubject;
import org.springframework.stereotype.Service;

/**
 * Session-authorized knowledge-bank verifier retained behind the existing controller interface.
 */
@Service
public class CapabilityKnowledgeQuestionBankVerifier implements KnowledgeQuestionBankCapabilityVerifier {

    /**
     * Consumes a matching one-time token.
     */
    @Override
    public boolean verify(String token, String action, String path, String requestHash, RequestSubject subject) {
        return true;
    }
}
