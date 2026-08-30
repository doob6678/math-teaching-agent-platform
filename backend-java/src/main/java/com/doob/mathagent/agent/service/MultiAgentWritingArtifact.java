package com.doob.mathagent.agent.service;

import com.doob.mathagent.agent.vo.AgentRunExecuteResponse;
import com.doob.mathagent.agent.vo.MultiAgentWritingResponse;
import java.util.List;

/** Owner-visible generated content snapshot for one multi-agent writing workflow. */
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

    public MultiAgentWritingArtifact(String workflowId, String tenantId, String subjectType, String subjectId,
                                     String status, AgentRunExecuteResponse.TokenUsage totalUsage,
                                     List<StageArtifact> stages, String mergedMarkdown) {
        this(workflowId, tenantId, subjectType, subjectId, status, totalUsage, stages, List.of(), mergedMarkdown);
    }

    public record StageArtifact(String stageCode, String agentCode, String traceId, String providerName,
                                String modelCode, String status, String generatedContent,
                                List<String> citations,
                                List<MultiAgentWritingResponse.AssetPlacement> assetPlacements) {
        public StageArtifact {
            citations = citations == null ? List.of() : List.copyOf(citations);
            assetPlacements = assetPlacements == null ? List.of() : List.copyOf(assetPlacements);
        }

        public StageArtifact(String stageCode, String agentCode, String traceId, String providerName,
                             String modelCode, String status, String generatedContent) {
            this(stageCode, agentCode, traceId, providerName, modelCode, status, generatedContent, List.of(), List.of());
        }
    }

    /** Merge-ready section with writer-owned citations and opaque asset placements. */
    public record StructuredSection(
            String sectionCode,
            String title,
            String sourceStageCode,
            String content,
            List<String> reviewNotes,
            List<String> risks,
            List<String> artifactRefs,
            List<String> citations,
            List<MultiAgentWritingResponse.AssetPlacement> assetPlacements) {

        public StructuredSection {
            sectionCode = sectionCode == null ? "" : sectionCode;
            title = title == null ? "" : title;
            sourceStageCode = sourceStageCode == null ? "" : sourceStageCode;
            content = content == null ? "" : content;
            reviewNotes = reviewNotes == null ? List.of() : List.copyOf(reviewNotes);
            risks = risks == null ? List.of() : List.copyOf(risks);
            artifactRefs = artifactRefs == null ? List.of() : List.copyOf(artifactRefs);
            citations = citations == null ? List.of() : List.copyOf(citations);
            assetPlacements = assetPlacements == null ? List.of() : List.copyOf(assetPlacements);
        }

        public StructuredSection(String sectionCode, String title, String sourceStageCode, String content,
                                List<String> reviewNotes, List<String> risks, List<String> artifactRefs) {
            this(sectionCode, title, sourceStageCode, content, reviewNotes, risks, artifactRefs, List.of(), List.of());
        }
    }
}
