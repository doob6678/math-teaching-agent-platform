package com.doob.mathagent.agent.service;

import java.time.Instant;
import java.util.List;
import com.doob.mathagent.agent.vo.AgentRunExecuteResponse;

/**
 * Persistable agent execution trace snapshot.
 *
 * @param traceId trace id
 * @param planId source plan id
 * @param createdAt trace creation time
 * @param tenantId backend tenant id
 * @param subjectType backend subject role
 * @param subjectId backend subject id
 * @param agentCode executed agent code
 * @param providerName selected provider
 * @param modelCode selected model
 * @param status execution status
 * @param estimatedCost local cost estimate
 * @param allowedToolScopes tool scopes recorded for audit
 * @param allowedDataScopes data scopes recorded for audit
 * @param evidenceRefs evidence ids used by this run
 * @param stageTimings execution stage timings safe for monitoring
 * @param actualUsage provider-reported token usage
 * @param message safe execution message without raw prompt or model output
 */
public record AgentTraceRecord(
        String traceId,
        String planId,
        Instant createdAt,
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
        List<String> evidenceRefs,
        List<AgentRunExecuteResponse.StageTiming> stageTimings,
        AgentRunExecuteResponse.TokenUsage actualUsage,
        String message) {
}
