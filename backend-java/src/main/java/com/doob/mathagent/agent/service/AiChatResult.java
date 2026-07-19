package com.doob.mathagent.agent.service;

/**
 * Safe chat result returned by a provider gateway.
 *
 * @param providerName actual provider that answered
 * @param modelCode actual model that answered
 * @param promptTokens provider-reported prompt tokens
 * @param completionTokens provider-reported completion tokens
 * @param totalTokens provider-reported total tokens
 * @param safeMessage short status message without raw model output
 * @param generatedContent model-generated content for the caller; trace stores may choose not to persist it
 */
public record AiChatResult(
        String providerName,
        String modelCode,
        int promptTokens,
        int completionTokens,
        int totalTokens,
        String safeMessage,
        String generatedContent) {

    /**
     * Creates a result without returning model content to older callers.
     */
    public AiChatResult(
            String providerName,
            String modelCode,
            int promptTokens,
            int completionTokens,
            int totalTokens,
            String safeMessage) {
        this(providerName, modelCode, promptTokens, completionTokens, totalTokens, safeMessage, "");
    }
}
