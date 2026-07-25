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
 * @param imageDataUrl optional owner-validated image bytes for a multimodal model call; never persisted
 */
public record AiChatRequest(
        String providerName,
        String modelCode,
        String agentCode,
        String userInputSummary,
        List<String> evidenceRefs,
        String imageDataUrl) {

    /** Preserves text-only callers while keeping image context explicit at the gateway boundary. */
    public AiChatRequest(
            String providerName,
            String modelCode,
            String agentCode,
            String userInputSummary,
            List<String> evidenceRefs) {
        this(providerName, modelCode, agentCode, userInputSummary, evidenceRefs, "");
    }
}
