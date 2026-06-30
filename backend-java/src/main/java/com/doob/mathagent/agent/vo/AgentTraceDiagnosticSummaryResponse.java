package com.doob.mathagent.agent.vo;

import java.util.List;

/**
 * Aggregated safe retry/fallback/parse diagnostics for visible agent traces.
 *
 * @param tenantId backend tenant id used for the aggregation
 * @param subjectType backend subject role used for visibility
 * @param subjectId backend subject id used for visibility
 * @param agentCode optional agent code filter
 * @param status optional status filter
 * @param runCount number of visible trace rows included
 * @param diagnosticEventCount total safe diagnostic events included
 * @param jsonParseFailureCount count of JSON parse failures
 * @param retryScheduledCount count of scheduled model retries
 * @param retryRecoveredCount count of traces that succeeded after at least one retry
 * @param providerRotationCount count of provider fallback rotations
 * @param modelCallFailureCount count of model gateway failures
 * @param modelDiagnostics provider/model-level diagnostic breakdown
 */
public record AgentTraceDiagnosticSummaryResponse(
        String tenantId,
        String subjectType,
        String subjectId,
        String agentCode,
        String status,
        int runCount,
        int diagnosticEventCount,
        int jsonParseFailureCount,
        int retryScheduledCount,
        int retryRecoveredCount,
        int providerRotationCount,
        int modelCallFailureCount,
        List<ModelDiagnostic> modelDiagnostics) {

    /**
     * Diagnostic bucket for one provider/model pair.
     *
     * @param providerName provider name recorded by backend events
     * @param modelCode model code recorded by backend events
     * @param runCount visible trace rows for this provider/model
     * @param diagnosticEventCount safe diagnostic events for this provider/model
     * @param jsonParseFailureCount JSON parse failures for this provider/model
     * @param retryScheduledCount scheduled retries for this provider/model
     * @param retryRecoveredCount traces recovered after retry for this provider/model
     * @param providerRotationCount provider rotations involving this provider/model
     * @param modelCallFailureCount model gateway failures for this provider/model
     * @param totalTokens provider-reported total tokens for visible trace rows
     */
    public record ModelDiagnostic(
            String providerName,
            String modelCode,
            int runCount,
            int diagnosticEventCount,
            int jsonParseFailureCount,
            int retryScheduledCount,
            int retryRecoveredCount,
            int providerRotationCount,
            int modelCallFailureCount,
            int totalTokens) {
    }
}
