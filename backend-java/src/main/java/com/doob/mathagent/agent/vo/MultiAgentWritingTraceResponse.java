package com.doob.mathagent.agent.vo;

import java.util.List;

/**
 * Safe recovery response for a multi-agent writing workflow.
 *
 * @param workflowId backend workflow id
 * @param tenantId backend tenant id
 * @param subjectType backend subject role
 * @param subjectId backend subject id
 * @param stageCount visible stage trace count
 * @param totalUsage summed provider-reported token usage
 * @param stages ordered safe stage traces
 */
public record MultiAgentWritingTraceResponse(
        String workflowId,
        String tenantId,
        String subjectType,
        String subjectId,
        int stageCount,
        AgentRunExecuteResponse.TokenUsage totalUsage,
        List<AgentTraceResponse> stages) {
}
