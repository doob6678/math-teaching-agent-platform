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
 */
public record AiChatResult(
        String providerName,
        String modelCode,
        int promptTokens,
        int completionTokens,
        int totalTokens,
        String safeMessage) {
}
