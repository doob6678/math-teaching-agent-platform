package com.doob.mathagent.agent.service;

import com.doob.mathagent.agent.vo.AgentRunExecuteResponse;
import java.util.List;

/**
 * Owner-visible generated content snapshot for one multi-agent writing workflow.
 *
 * @param workflowId backend workflow id
 * @param tenantId backend tenant id
 * @param subjectType backend subject role
 * @param subjectId backend subject id
 * @param status workflow status
 * @param totalUsage summed provider-reported token usage
 * @param stages generated content per writing stage
 * @param mergedMarkdown merged Markdown draft suitable for frontend preview or export preparation
 */
public record MultiAgentWritingArtifact(
        String workflowId,
        String tenantId,
        String subjectType,
        String subjectId,
        String status,
        AgentRunExecuteResponse.TokenUsage totalUsage,
        List<StageArtifact> stages,
        String mergedMarkdown) {

    /**
     * Generated content for one writing stage.
     *
     * @param stageCode stable stage code
     * @param agentCode backend agent code
     * @param traceId safe trace id for diagnostics
     * @param providerName provider used by the stage
     * @param modelCode model used by the stage
     * @param status stage status
     * @param generatedContent owner-visible generated content
     */
    public record StageArtifact(
            String stageCode,
            String agentCode,
            String traceId,
            String providerName,
            String modelCode,
            String status,
            String generatedContent) {
    }
}
