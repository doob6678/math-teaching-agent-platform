package com.doob.mathagent.agent.vo;

import java.time.Instant;
import java.util.List;

/**
 * Safe response for one multi-agent writing workflow.
 *
 * @param workflowId backend workflow id shared by all stage plans and traces
 * @param tenantId backend tenant id
 * @param subjectType backend subject role
 * @param subjectId backend subject id
 * @param status workflow status
 * @param createdAt workflow creation time
 * @param updatedAt latest workflow status update time
 * @param stages ordered writing stages and their trace ids
 * @param totalUsage summed provider-reported token usage
 * @param message safe workflow status message without raw prompt or full model output
 */
public record MultiAgentWritingResponse(
        String workflowId,
        String tenantId,
        String subjectType,
        String subjectId,
        String status,
        Instant createdAt,
        Instant updatedAt,
        List<StageResult> stages,
        AgentRunExecuteResponse.TokenUsage totalUsage,
        String message) {

    /**
     * One writing agent stage result.
     *
     * @param stageCode stable stage code
     * @param agentCode agent executed by the backend
     * @param traceId trace id for recovery and diagnostics
     * @param providerName provider used by the stage
     * @param modelCode model used by the stage
     * @param status execution status
     * @param actualUsage provider-reported token usage
     * @param message safe status message without raw model output
     */
    public record StageResult(
            String stageCode,
            String agentCode,
            String traceId,
            String providerName,
            String modelCode,
            String status,
            AgentRunExecuteResponse.TokenUsage actualUsage,
            String message) {
    }
}
