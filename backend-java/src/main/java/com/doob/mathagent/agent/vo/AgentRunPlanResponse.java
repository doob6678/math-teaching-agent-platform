package com.doob.mathagent.agent.vo;

import java.util.List;

/**
 * Safe execution plan returned before running an AI agent.
 *
 * @param planId unique plan id for audit and later trace linking
 * @param tenantId backend resolved tenant id
 * @param subjectType backend resolved subject type
 * @param subjectId backend resolved subject id
 * @param agentCode selected agent code
 * @param providerName selected model provider
 * @param modelCode selected model code
 * @param modelLevel selected model capability level
 * @param allowedToolScopes requested tool scopes accepted by agent policy
 * @param deniedToolScopes requested tool scopes rejected by agent policy
 * @param allowedDataScopes requested data scopes accepted by agent policy
 * @param deniedDataScopes requested data scopes rejected by agent policy
 * @param capabilityRequired whether the caller must acquire a high-value capability token before execution
 * @param capabilityAction capability action to use when capabilityRequired is true
 * @param maxInputTokens policy-clipped input token limit
 * @param maxOutputTokens policy-clipped output token limit
 * @param estimatedTotalTokens estimated tokens after policy clipping
 * @param estimatedCost deterministic local cost estimate for budget checks
 * @param withinBudget whether estimatedCost is inside request costBudget
 * @param routeReason human-readable routing decision for audit
 * @param stageTimings planning stage timings
 * @param concurrencyKeys Redis-style concurrency dimensions to acquire before execution
 */
public record AgentRunPlanResponse(
        String planId,
        String tenantId,
        String subjectType,
        String subjectId,
        String agentCode,
        String providerName,
        String modelCode,
        String modelLevel,
        List<String> allowedToolScopes,
        List<String> deniedToolScopes,
        List<String> allowedDataScopes,
        List<String> deniedDataScopes,
        boolean capabilityRequired,
        String capabilityAction,
        int maxInputTokens,
        int maxOutputTokens,
        long estimatedTotalTokens,
        double estimatedCost,
        boolean withinBudget,
        String routeReason,
        List<StageTiming> stageTimings,
        List<String> concurrencyKeys) {

    /**
     * Planning stage timing for route-performance monitoring.
     *
     * @param stage stable stage code
     * @param elapsedMs elapsed milliseconds for the stage
     */
    public record StageTiming(String stage, long elapsedMs) {
    }
}
