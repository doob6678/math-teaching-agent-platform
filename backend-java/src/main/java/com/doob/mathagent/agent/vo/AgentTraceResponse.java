package com.doob.mathagent.agent.vo;

import java.time.Instant;
import java.util.List;

/**
 * Safe agent trace response for status recovery and monitoring.
 *
 * @param traceId trace id
 * @param planId linked plan id
 * @param createdAt trace creation time
 * @param tenantId backend tenant id
 * @param subjectType backend subject role
 * @param subjectId backend subject id
 * @param agentCode executed agent code
 * @param providerName selected provider
 * @param modelCode selected model
 * @param status execution status
 * @param estimatedCost estimated local cost
 * @param allowedToolScopes allowed tool scopes recorded for audit
 * @param allowedDataScopes allowed data scopes recorded for audit
 * @param evidenceRefs evidence references used by this run
 * @param stageTimings persisted stage timings for task recovery
 * @param actualUsage provider-reported token usage for recovered runs
 * @param message safe execution message without raw prompt or model output
 * @param diagnosticEvents safe retry/fallback/parse events for trace monitoring
 */
public record AgentTraceResponse(
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
        String message,
        List<DiagnosticEvent> diagnosticEvents) {

    /**
     * Backward-compatible constructor for older tests and trace sources.
     */
    public AgentTraceResponse(
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
        this(traceId, planId, createdAt, tenantId, subjectType, subjectId, agentCode, providerName, modelCode, status,
                estimatedCost, allowedToolScopes, allowedDataScopes, evidenceRefs, stageTimings, actualUsage, message,
                List.of());
    }

    /**
     * One safe diagnostic event returned to the frontend.
     *
     * @param eventType stable event code
     * @param providerName provider involved in the event
     * @param modelCode model involved in the event
     * @param attemptNo zero-based attempt number when applicable
     * @param retryable whether backend still had retry/fallback capacity after this event
     * @param message short safe message with no raw prompt or model output
     */
    public record DiagnosticEvent(
            String eventType,
            String providerName,
            String modelCode,
            int attemptNo,
            boolean retryable,
            String message) {
    }
}
