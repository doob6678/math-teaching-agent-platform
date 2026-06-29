package com.doob.mathagent.agent.vo;

import java.util.List;

/**
 * Baseline agent execution trace response.
 *
 * @param traceId trace id used for later execution detail lookup
 * @param planId plan id linked to the execution
 * @param tenantId backend resolved tenant id
 * @param subjectType backend resolved subject role
 * @param subjectId backend resolved subject id
 * @param agentCode executed agent code
 * @param providerName selected provider name copied from the validated plan
 * @param modelCode selected model code copied from the validated plan
 * @param status baseline execution status
 * @param estimatedCost estimated local cost copied from the plan for monitoring
 * @param allowedToolScopes tool scopes allowed by the plan and recorded for audit
 * @param allowedDataScopes data scopes allowed by the plan and recorded for audit
 * @param concurrencyKeys concurrency keys acquired for this execution
 * @param stageTimings execution stage timing rows
 * @param message safe status message; raw prompt and raw model output are intentionally omitted
 */
public record AgentRunExecuteResponse(
        String traceId,
        String planId,
        String tenantId,
        String subjectType,
        String subjectId,
        String agentCode,
        String providerName,
        String modelCode,
        String status,
        double estimatedCost,
        List<String> allowedToolScopes,
        List<String> allowedDataScopes,
        List<String> concurrencyKeys,
        List<StageTiming> stageTimings,
        String message) {

    /**
     * Execution stage timing for monitoring dashboards.
     *
     * @param stage stable stage code
     * @param elapsedMs elapsed milliseconds for the stage
     */
    public record StageTiming(String stage, long elapsedMs) {
    }
}
