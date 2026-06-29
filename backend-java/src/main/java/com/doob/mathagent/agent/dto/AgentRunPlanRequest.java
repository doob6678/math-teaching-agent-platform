package com.doob.mathagent.agent.dto;

import java.util.List;

/**
 * Request body for planning an AI agent run before any model call is made.
 *
 * @param agentCode requested agent code such as StudentTutorAgent or CoursewareAgent
 * @param taskType task type used by model routing, such as question_solving or courseware_generation
 * @param userVipLevel caller tier used for token and model budget limits
 * @param estimatedInputTokens estimated prompt tokens before clipping by policy
 * @param estimatedOutputTokens estimated completion tokens before clipping by policy
 * @param hasImage whether the task needs multimodal or OCR-assisted routing
 * @param hasFormula whether the task includes formulas and needs stronger reasoning
 * @param difficulty normalized difficulty label such as easy, medium, or hard
 * @param latencyRequirement latency preference such as low or normal
 * @param costBudget caller-visible cost budget for this run
 * @param previousFailureCount number of recent model failures used for fallback routing
 * @param requiredJsonSchema whether the response must satisfy a structured JSON schema
 * @param requestedToolScopes tool scopes requested by the workflow
 * @param disabledToolScopes per-request user preference that removes tools from dynamic injection without granting access
 * @param requestedDataScopes data scopes requested by the workflow
 * @param highValueOperation whether this run can spend high-value model/tool budget
 * @param preferredProviderName optional provider preference selected in UI; backend validates it against enabled providers
 * @param preferredModelCode optional model preference selected in UI; backend validates it against a provider allow-list
 */
public record AgentRunPlanRequest(
        String agentCode,
        String taskType,
        String userVipLevel,
        int estimatedInputTokens,
        int estimatedOutputTokens,
        boolean hasImage,
        boolean hasFormula,
        String difficulty,
        String latencyRequirement,
        double costBudget,
        int previousFailureCount,
        boolean requiredJsonSchema,
        List<String> requestedToolScopes,
        List<String> disabledToolScopes,
        List<String> requestedDataScopes,
        boolean highValueOperation,
        String preferredProviderName,
        String preferredModelCode) {

    /**
     * Backward-compatible constructor for callers that do not expose model preference controls yet.
     */
    public AgentRunPlanRequest(
            String agentCode,
            String taskType,
            String userVipLevel,
            int estimatedInputTokens,
            int estimatedOutputTokens,
            boolean hasImage,
            boolean hasFormula,
            String difficulty,
            String latencyRequirement,
            double costBudget,
            int previousFailureCount,
            boolean requiredJsonSchema,
            List<String> requestedToolScopes,
            List<String> disabledToolScopes,
            List<String> requestedDataScopes,
            boolean highValueOperation) {
        this(
                agentCode,
                taskType,
                userVipLevel,
                estimatedInputTokens,
                estimatedOutputTokens,
                hasImage,
                hasFormula,
                difficulty,
                latencyRequirement,
                costBudget,
                previousFailureCount,
                requiredJsonSchema,
                requestedToolScopes,
                disabledToolScopes,
                requestedDataScopes,
                highValueOperation,
                "",
                "");
    }

    /**
     * Returns a request with stable defaults and null-safe lists.
     */
    public AgentRunPlanRequest normalize() {
        return new AgentRunPlanRequest(
                defaultText(agentCode, ""),
                defaultText(taskType, "question_solving"),
                defaultText(userVipLevel, "free"),
                Math.max(0, estimatedInputTokens),
                Math.max(0, estimatedOutputTokens),
                hasImage,
                hasFormula,
                defaultText(difficulty, "medium").toLowerCase(java.util.Locale.ROOT),
                defaultText(latencyRequirement, "normal").toLowerCase(java.util.Locale.ROOT),
                Math.max(0.0d, costBudget),
                Math.max(0, previousFailureCount),
                requiredJsonSchema,
                normalizeList(requestedToolScopes),
                normalizeList(disabledToolScopes),
                normalizeList(requestedDataScopes),
                highValueOperation,
                defaultText(preferredProviderName, "").toLowerCase(java.util.Locale.ROOT),
                defaultText(preferredModelCode, ""));
    }

    /**
     * Normalizes a text field without changing intentional agent-code casing.
     */
    private static String defaultText(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value.strip();
    }

    /**
     * Removes blank and duplicate scope values while preserving caller order.
     */
    private static List<String> normalizeList(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        return values.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(String::strip)
                .distinct()
                .toList();
    }
}
