package com.doob.mathagent.agent.service;

import java.util.List;

/**
 * Sanitized chat request sent to an AI provider.
 *
 * @param providerName backend-selected provider name
 * @param modelCode backend-selected model code
 * @param agentCode agent code for prompt framing
 * @param userInputSummary short user task summary
 * @param evidenceRefs evidence references, not raw document content
 */
public record AiChatRequest(
        String providerName,
        String modelCode,
        String agentCode,
        String userInputSummary,
        List<String> evidenceRefs) {
}
