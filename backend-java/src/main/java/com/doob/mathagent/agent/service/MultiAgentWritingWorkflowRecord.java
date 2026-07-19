package com.doob.mathagent.agent.service;

import com.doob.mathagent.agent.vo.AgentRunExecuteResponse;
import com.doob.mathagent.agent.vo.MultiAgentWritingResponse;
import java.time.Instant;
import java.util.List;

/**
 * Persistable status snapshot for one multi-agent writing workflow.
 *
 * @param workflowId backend workflow id shared by all writing stage traces
 * @param tenantId backend tenant id
 * @param subjectType backend subject role
 * @param subjectId backend subject id
 * @param status workflow status, such as RUNNING, COMPLETED, or FAILED
 * @param createdAt workflow creation time
 * @param updatedAt latest workflow status update time
 * @param stages stage results completed so far
 * @param totalUsage summed provider-reported token usage for completed stages
 * @param message safe status message without raw prompt or model output
 */
public record MultiAgentWritingWorkflowRecord(
        String workflowId,
        String tenantId,
        String subjectType,
        String subjectId,
        String status,
        Instant createdAt,
        Instant updatedAt,
        List<MultiAgentWritingResponse.StageResult> stages,
        AgentRunExecuteResponse.TokenUsage totalUsage,
        String message) {

    /**
     * Returns a null-safe copy for persistence.
     *
     * @return normalized workflow record
     */
    public MultiAgentWritingWorkflowRecord normalize() {
        AgentRunExecuteResponse.TokenUsage normalizedUsage = totalUsage == null
                ? new AgentRunExecuteResponse.TokenUsage(0, 0, 0)
                : totalUsage;
        return new MultiAgentWritingWorkflowRecord(
                safeText(workflowId),
                textOrDefault(tenantId, "default"),
                safeText(subjectType),
                safeText(subjectId),
                textOrDefault(status, "RUNNING"),
                createdAt == null ? Instant.now() : createdAt,
                updatedAt == null ? Instant.now() : updatedAt,
                stages == null ? List.of() : List.copyOf(stages),
                normalizedUsage,
                safeText(message));
    }

    /**
     * Returns stripped text or fallback.
     */
    private static String textOrDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.strip();
    }

    /**
     * Returns stripped text or an empty string.
     */
    private static String safeText(String value) {
        return value == null || value.isBlank() ? "" : value.strip();
    }
}
