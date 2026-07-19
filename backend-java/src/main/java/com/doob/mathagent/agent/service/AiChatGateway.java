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

    /**
     * Runs one model request with optional incremental delivery.
     *
     * <p>Existing non-streaming gateways stay compatible: they return their real final response as one terminal
     * content delta. Production gateways override this method to forward provider SSE chunks immediately.</p>
     */
    default AiChatResult stream(AiChatRequest request, AiChatStreamListener listener) {
        AiChatResult result = call(request);
        if (listener != null && result.generatedContent() != null && !result.generatedContent().isBlank()) {
            listener.onDelta(new AiChatStreamDelta(
                    result.providerName(),
                    result.modelCode(),
                    "",
                    result.generatedContent(),
                    result.promptTokens(),
                    result.completionTokens(),
                    result.totalTokens()));
        }
        return result;
    }
}
