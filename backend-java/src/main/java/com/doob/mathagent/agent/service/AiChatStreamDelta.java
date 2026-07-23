package com.doob.mathagent.agent.service;

/**
 * One real OpenAI-compatible server-sent event delta.
 *
 * <p>Reasoning and content are kept separate so product surfaces can collapse reasoning without delaying the visible
 * answer text. Token counts are populated only when the provider sends a usage event.</p>
 */
public record AiChatStreamDelta(
        String providerName,
        String modelCode,
        String reasoningDelta,
        String contentDelta,
        int promptTokens,
        int completionTokens,
        int totalTokens) {

    /** Binds the provider chosen by the backend without trusting a provider response field. */
    public AiChatStreamDelta withProviderName(String nextProviderName) {
        return new AiChatStreamDelta(
                nextProviderName == null ? "" : nextProviderName,
                modelCode,
                reasoningDelta,
                contentDelta,
                promptTokens,
                completionTokens,
                totalTokens);
    }
}
