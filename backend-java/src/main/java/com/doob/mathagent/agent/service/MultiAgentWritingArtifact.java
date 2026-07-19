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
 * @param sections structured sections used by merge, review, and future parallel writers
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
        List<StructuredSection> sections,
        String mergedMarkdown) {

    public MultiAgentWritingArtifact {
        stages = stages == null ? List.of() : List.copyOf(stages);
        sections = sections == null ? List.of() : List.copyOf(sections);
        mergedMarkdown = mergedMarkdown == null ? "" : mergedMarkdown;
    }

    public MultiAgentWritingArtifact(
            String workflowId,
            String tenantId,
            String subjectType,
            String subjectId,
            String status,
            AgentRunExecuteResponse.TokenUsage totalUsage,
            List<StageArtifact> stages,
            String mergedMarkdown) {
        this(workflowId, tenantId, subjectType, subjectId, status, totalUsage, stages, List.of(), mergedMarkdown);
    }

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

    /**
     * Merge-ready document section with review metadata kept separate from raw stage output.
     *
     * @param sectionCode stable merge section code
     * @param title readable section title
     * @param sourceStageCode stage that produced the section
     * @param content owner-visible Markdown body
     * @param reviewNotes reviewer comments or patch notes for this section
     * @param risks known content, layout, or evidence risks
     * @param artifactRefs evidence or upstream artifact references used by this section
     */
    public record StructuredSection(
            String sectionCode,
            String title,
            String sourceStageCode,
            String content,
            List<String> reviewNotes,
            List<String> risks,
            List<String> artifactRefs) {

        public StructuredSection {
            sectionCode = sectionCode == null ? "" : sectionCode;
            title = title == null ? "" : title;
            sourceStageCode = sourceStageCode == null ? "" : sourceStageCode;
            content = content == null ? "" : content;
            reviewNotes = reviewNotes == null ? List.of() : List.copyOf(reviewNotes);
            risks = risks == null ? List.of() : List.copyOf(risks);
            artifactRefs = artifactRefs == null ? List.of() : List.copyOf(artifactRefs);
        }
    }
}
