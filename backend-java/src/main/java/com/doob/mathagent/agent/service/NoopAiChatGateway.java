package com.doob.mathagent.agent.service;

import org.springframework.stereotype.Component;

/**
 * Offline-safe model gateway used until a live Spring AI gateway is explicitly wired.
 */
@Component
public class NoopAiChatGateway implements AiChatGateway {

    /**
     * Rejects accidental live calls when no real gateway is configured.
     *
     * @param request sanitized model call request
     * @return never returns
     */
    @Override
    public AiChatResult call(AiChatRequest request) {
        throw new IllegalStateException("Live AI gateway is not configured");
    }
}
