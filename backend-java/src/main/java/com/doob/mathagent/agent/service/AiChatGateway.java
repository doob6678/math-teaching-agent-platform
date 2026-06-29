package com.doob.mathagent.agent.service;

/**
 * Boundary for model calls made by agent execution.
 */
public interface AiChatGateway {

    /**
     * Calls one configured chat provider.
     *
     * @param request sanitized model call request
     * @return safe model call result without raw prompt persistence
     */
    AiChatResult call(AiChatRequest request);
}
