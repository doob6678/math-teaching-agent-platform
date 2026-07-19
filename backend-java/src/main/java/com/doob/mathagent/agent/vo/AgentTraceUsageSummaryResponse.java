package com.doob.mathagent.agent.vo;

import java.util.List;

/**
 * Aggregated provider-reported token usage for visible agent traces.
 *
 * @param tenantId backend tenant id used for the aggregation
 * @param subjectType backend subject role used for visibility
 * @param subjectId backend subject id used for visibility
 * @param agentCode optional agent code filter
 * @param status optional status filter
 * @param runCount number of visible trace rows included
 * @param totalUsage total provider-reported token usage
 * @param modelUsages provider/model-level usage breakdown
 */
public record AgentTraceUsageSummaryResponse(
        String tenantId,
        String subjectType,
        String subjectId,
        String agentCode,
        String status,
        int runCount,
        AgentRunExecuteResponse.TokenUsage totalUsage,
        List<ModelUsage> modelUsages) {

    /**
     * Usage bucket for one provider/model pair.
     *
     * @param providerName provider name recorded by backend execution
     * @param modelCode model code recorded by backend execution
     * @param runCount number of trace rows for this provider/model
     * @param promptTokens summed prompt tokens
     * @param completionTokens summed completion tokens
     * @param totalTokens summed total tokens
     */
    public record ModelUsage(
            String providerName,
            String modelCode,
            int runCount,
            int promptTokens,
            int completionTokens,
            int totalTokens) {
    }
}
