package com.doob.mathagent.agent.dto;

import java.util.List;

/**
 * Request for a backend-owned multi-agent writing workflow.
 *
 * @param writingGoal short writing goal such as teacher handout or student blank handout
 * @param questionText source math question or teaching topic
 * @param evidenceRefs evidence anchors selected by RAG, Feishu, textbook, or question bank retrieval
 * @param dryRun must be false; production entrypoints reject trace-only workflows
 * @param preferredProviderName optional provider preference validated by backend model policy
 * @param preferredModelCode optional model preference validated by backend model policy
 */
public record MultiAgentWritingRequest(
        String writingGoal,
        String questionText,
        List<String> evidenceRefs,
        boolean dryRun,
        String preferredProviderName,
        String preferredModelCode) {

    /**
     * Returns a null-safe request without trusting any frontend identity field.
     *
     * @return normalized request
     */
    public MultiAgentWritingRequest normalize() {
        String normalizedGoal = safeText(writingGoal);
        String normalizedQuestion = safeText(questionText);
        if (normalizedQuestion.isBlank()) {
            normalizedQuestion = normalizedGoal;
        }
        String normalizedProvider = safeText(preferredProviderName).toLowerCase(java.util.Locale.ROOT);
        String normalizedModel = safeText(preferredModelCode);
        // Preserve an explicit model selection so the planner can audit and execute the exact route the caller chose.
        // Provider policy remains the authority: unknown or disabled models are rejected by preferredProvider rather
        // than silently changing the user's request into a different model.
        return new MultiAgentWritingRequest(
                normalizedGoal,
                normalizedQuestion,
                evidenceRefs == null ? List.of() : evidenceRefs.stream().map(MultiAgentWritingRequest::safeText).toList(),
                dryRun,
                normalizedProvider,
                normalizedModel);
    }

    /**
     * Returns stripped text or an empty string.
     */
    private static String safeText(String value) {
        return value == null || value.isBlank() ? "" : value.strip();
    }
}
