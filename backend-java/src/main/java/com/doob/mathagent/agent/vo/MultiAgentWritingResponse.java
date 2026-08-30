package com.doob.mathagent.agent.vo;

import java.time.Instant;
import java.util.List;

/** Safe response for one multi-agent writing workflow. */
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

    /** One writing stage plus bounded writer-owned evidence metadata. */
    public record StageResult(
            String stageCode,
            String agentCode,
            String traceId,
            String providerName,
            String modelCode,
            String status,
            AgentRunExecuteResponse.TokenUsage actualUsage,
            String message,
            String generatedContent,
            long elapsedMs,
            List<String> citations,
            List<AssetPlacement> assetPlacements) {

        public StageResult {
            citations = citations == null ? List.of() : List.copyOf(citations);
            assetPlacements = assetPlacements == null ? List.of() : List.copyOf(assetPlacements);
        }

        public StageResult(String stageCode, String agentCode, String traceId, String providerName, String modelCode,
                           String status, AgentRunExecuteResponse.TokenUsage actualUsage, String message) {
            this(stageCode, agentCode, traceId, providerName, modelCode, status, actualUsage, message, "", 0L,
                    List.of(), List.of());
        }

        public StageResult(String stageCode, String agentCode, String traceId, String providerName, String modelCode,
                           String status, AgentRunExecuteResponse.TokenUsage actualUsage, String message,
                           String generatedContent) {
            this(stageCode, agentCode, traceId, providerName, modelCode, status, actualUsage, message, generatedContent, 0L,
                    List.of(), List.of());
        }

        public StageResult(String stageCode, String agentCode, String traceId, String providerName, String modelCode,
                           String status, AgentRunExecuteResponse.TokenUsage actualUsage, String message,
                           String generatedContent, long elapsedMs) {
            this(stageCode, agentCode, traceId, providerName, modelCode, status, actualUsage, message, generatedContent,
                    elapsedMs, List.of(), List.of());
        }
    }

    /** Writer-selected source image placement; the exporter resolves the original row within its authorized article. */
    public record AssetPlacement(
            String logicalPath,
            String markdownLine,
            String anchorBefore,
            String anchorAfter,
            String layout,
            List<String> variants,
            String caption) {
        public AssetPlacement {
            logicalPath = logicalPath == null ? "" : logicalPath;
            markdownLine = markdownLine == null ? "" : markdownLine;
            anchorBefore = anchorBefore == null ? "" : anchorBefore;
            anchorAfter = anchorAfter == null ? "" : anchorAfter;
            variants = variants == null ? List.of() : List.copyOf(variants);
            caption = caption == null ? "" : caption;
        }

    }
}
