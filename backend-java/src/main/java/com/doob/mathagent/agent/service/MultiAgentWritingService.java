package com.doob.mathagent.agent.service;

import com.doob.mathagent.agent.dto.AgentRunExecuteRequest;
import com.doob.mathagent.agent.dto.AgentRunPlanRequest;
import com.doob.mathagent.agent.dto.MultiAgentWritingRequest;
import com.doob.mathagent.agent.vo.AgentRunExecuteResponse;
import com.doob.mathagent.agent.vo.AgentRunPlanResponse;
import com.doob.mathagent.agent.vo.MultiAgentWritingResponse;
import com.doob.mathagent.infrastructure.security.RequestSubject;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;

/**
 * Orchestrates a real multi-agent writing workflow through the existing AgentRun plan and execution services.
 */
@Service
public class MultiAgentWritingService {

    private static final ObjectMapper ARTIFACT_OBJECT_MAPPER = new ObjectMapper();

    private static final List<WritingStageSpec> WRITING_STAGES = List.of(
            new WritingStageSpec(
                    "draft",
                    "CoursewareAgent",
                    "courseware_generation",
                    List.of("tool:courseware:generate", "tool:search:textbook", "tool:search:private")),
            new WritingStageSpec(
                    "review",
                    "QualityCheckAgent",
                    "quality_review",
                    List.of("tool:quality:check")),
            new WritingStageSpec(
                    "format",
                    "HandoutFormatterAgent",
                    "handout_formatting",
                    List.of("tool:handout:format")));

    private final AgentRunPlanService planService;
    private final AgentRunExecutionService executionService;
    private final MultiAgentWritingWorkflowStore workflowStore;
    private final TaskExecutor taskExecutor;

    /**
     * Creates the multi-agent writing service.
     *
     * @param planService backend agent planner
     * @param executionService backend agent executor
     * @param workflowStore workflow status store
     */
    @Autowired
    public MultiAgentWritingService(
            AgentRunPlanService planService,
            AgentRunExecutionService executionService,
            MultiAgentWritingWorkflowStore workflowStore,
            @Qualifier("multiAgentWritingTaskExecutor") TaskExecutor taskExecutor) {
        this.planService = planService;
        this.executionService = executionService;
        this.workflowStore = workflowStore;
        this.taskExecutor = taskExecutor;
    }

    /**
     * Returns the one capability action required for protected writing workflows.
     *
     * @return capability action
     */
    public String capabilityAction() {
        return "agent-run:CoursewareAgent";
    }

    /**
     * Runs draft, review, and formatting agents in sequence with one backend-resolved subject.
     *
     * @param request writing request
     * @param subject backend authenticated subject
     * @return safe workflow response
     */
    public MultiAgentWritingResponse run(MultiAgentWritingRequest request, RequestSubject subject) {
        MultiAgentWritingRequest normalized = request.normalize();
        requireLiveModelExecution(normalized);
        RequestSubject normalizedSubject = subject.normalize();
        requireTeacherOrAdmin(normalizedSubject);
        return executeWorkflow(
                UUID.randomUUID().toString(),
                Instant.now(),
                normalized,
                normalizedSubject,
                List.of(),
                "Multi-agent writing workflow started.");
    }

    /**
     * Starts a background writing workflow and immediately returns its recoverable RUNNING status.
     *
     * @param request writing request
     * @param subject backend authenticated subject
     * @return initial workflow status
     */
    public MultiAgentWritingResponse startAsync(MultiAgentWritingRequest request, RequestSubject subject) {
        MultiAgentWritingRequest normalized = request.normalize();
        requireLiveModelExecution(normalized);
        RequestSubject normalizedSubject = subject.normalize();
        requireTeacherOrAdmin(normalizedSubject);
        String workflowId = UUID.randomUUID().toString();
        Instant createdAt = Instant.now();
        MultiAgentWritingWorkflowRecord started = saveWorkflow(
                workflowId,
                normalizedSubject,
                "RUNNING",
                createdAt,
                List.of(),
                "Multi-agent writing workflow queued.");
        taskExecutor.execute(() -> {
            try {
                executeWorkflow(
                        workflowId,
                        createdAt,
                        normalized,
                        normalizedSubject,
                        List.of(),
                        "Multi-agent writing workflow started.");
            } catch (RuntimeException ignored) {
                // executeWorkflow already persisted FAILED with a safe message.
            }
        });
        return toResponse(started);
    }

    /**
     * Resumes a visible failed workflow from the first missing writing stage.
     *
     * @param workflowId workflow id returned by the async or sync writing endpoint
     * @param request latest writing request used for the remaining stages
     * @param subject backend authenticated subject
     * @return resumed workflow response
     */
    public MultiAgentWritingResponse resume(String workflowId, MultiAgentWritingRequest request, RequestSubject subject) {
        MultiAgentWritingRequest normalized = request.normalize();
        requireLiveModelExecution(normalized);
        RequestSubject normalizedSubject = subject.normalize();
        requireTeacherOrAdmin(normalizedSubject);
        MultiAgentWritingWorkflowRecord existing = workflowStore
                .findVisible(normalizedWorkflowId(workflowId), normalizedSubject)
                .orElseThrow(() -> new IllegalArgumentException("Multi-agent writing workflow not found"));
        MultiAgentWritingWorkflowRecord normalizedRecord = existing.normalize();
        if ("COMPLETED".equals(normalizedRecord.status())) {
            return toResponse(normalizedRecord);
        }
        if ("RUNNING".equals(normalizedRecord.status())) {
            throw new IllegalStateException("Multi-agent writing workflow is still running");
        }
        List<MultiAgentWritingResponse.StageResult> completedStages = validCompletedPrefix(normalizedRecord.stages());
        return executeWorkflow(
                normalizedRecord.workflowId(),
                normalizedRecord.createdAt(),
                normalized,
                normalizedSubject,
                completedStages,
                "Multi-agent writing workflow resumed.");
    }

    /**
     * Runs draft, review, and format stages for an existing workflow id, skipping completed prefix stages.
     */
    private MultiAgentWritingResponse executeWorkflow(
            String workflowId,
            Instant createdAt,
            MultiAgentWritingRequest normalized,
            RequestSubject normalizedSubject,
            List<MultiAgentWritingResponse.StageResult> completedStages,
            String startMessage) {
        if (!"teacher".equals(normalizedSubject.subjectType()) && !"admin".equals(normalizedSubject.subjectType())) {
            throw new IllegalArgumentException("Multi-agent writing requires teacher or admin subject");
        }
        List<MultiAgentWritingResponse.StageResult> stages = new ArrayList<>(completedStages);
        saveWorkflow(workflowId, normalizedSubject, "RUNNING", createdAt, stages, startMessage);
        try {
            for (WritingStageSpec stage : WRITING_STAGES.subList(stages.size(), WRITING_STAGES.size())) {
                stages.add(runStage(
                        workflowId,
                        stage.stageCode(),
                        stage.agentCode(),
                        stage.taskType(),
                        normalized,
                        normalizedSubject,
                        stage.requestedTools()));
                saveWorkflow(
                        workflowId,
                        normalizedSubject,
                        "RUNNING",
                        createdAt,
                        stages,
                        stageCompletionMessage(stage.stageCode()));
            }
            MultiAgentWritingWorkflowRecord completed = saveWorkflow(
                    workflowId,
                    normalizedSubject,
                    "COMPLETED",
                    createdAt,
                    stages,
                    "Multi-agent writing workflow completed.");
            return toResponse(completed);
        } catch (RuntimeException exception) {
            saveWorkflow(
                    workflowId,
                    normalizedSubject,
                    "FAILED",
                    createdAt,
                    stages,
                    safeFailureMessage(exception));
            throw exception;
        }
    }

    /**
     * Finds a workflow status snapshot visible to the backend subject.
     *
     * @param workflowId workflow id
     * @param subject backend subject
     * @return visible workflow status
     */
    public Optional<MultiAgentWritingResponse> find(String workflowId, RequestSubject subject) {
        return workflowStore.findVisible(workflowId, subject)
                .map(MultiAgentWritingService::toResponse);
    }

    /**
     * Requires a teacher or admin backend subject before planning expensive writing work.
     */
    private static void requireTeacherOrAdmin(RequestSubject subject) {
        if (!"teacher".equals(subject.subjectType()) && !"admin".equals(subject.subjectType())) {
            throw new IllegalArgumentException("Multi-agent writing requires teacher or admin subject");
        }
    }

    /**
     * Production writing workflows must call real model providers; trace-only dry runs are limited to tests.
     */
    private static void requireLiveModelExecution(MultiAgentWritingRequest request) {
        if (request.dryRun()) {
            throw new IllegalArgumentException("Multi-agent writing dryRun is disabled in production");
        }
    }

    /**
     * Keeps only the leading completed stage sequence that can be trusted for resumable execution.
     */
    private static List<MultiAgentWritingResponse.StageResult> validCompletedPrefix(
            List<MultiAgentWritingResponse.StageResult> stages) {
        List<MultiAgentWritingResponse.StageResult> safeStages = stages == null ? List.of() : stages;
        List<MultiAgentWritingResponse.StageResult> prefix = new ArrayList<>();
        for (int index = 0; index < WRITING_STAGES.size() && index < safeStages.size(); index++) {
            MultiAgentWritingResponse.StageResult stage = safeStages.get(index);
            WritingStageSpec expected = WRITING_STAGES.get(index);
            if (!expected.stageCode().equals(stage.stageCode()) || !"COMPLETED".equals(stage.status())) {
                break;
            }
            prefix.add(stage);
        }
        return List.copyOf(prefix);
    }

    /**
     * Validates workflow id shape before using it to read or resume a workflow.
     */
    private static String normalizedWorkflowId(String workflowId) {
        if (workflowId == null || workflowId.isBlank()) {
            throw new IllegalArgumentException("workflowId is required");
        }
        String normalized = workflowId.strip();
        if (!normalized.matches("[A-Za-z0-9._:-]{8,80}")) {
            throw new IllegalArgumentException("workflowId is invalid");
        }
        return normalized;
    }

    /**
     * Plans and executes one writing stage through shared AgentRun policy.
     */
    private MultiAgentWritingResponse.StageResult runStage(
            String workflowId,
            String stageCode,
            String agentCode,
            String taskType,
            MultiAgentWritingRequest request,
            RequestSubject subject,
            List<String> requestedTools) {
        AgentRunPlanResponse plan = planService.plan(
                new AgentRunPlanRequest(
                        agentCode,
                        taskType,
                        "teacher",
                        3200,
                        1600,
                        false,
                        true,
                        "medium",
                        "normal",
                        3.0,
                        0,
                        stageCode.equals("review"),
                        requestedTools,
                        List.of(),
                        List.of("PUBLIC_TEXTBOOK", "TEACHER_PRIVATE", "CLASS_AUTHORIZED"),
                        agentCode.equals("CoursewareAgent"),
                        request.preferredProviderName(),
                        request.preferredModelCode()),
                subject);
        AgentRunExecuteResponse execution = executionService.execute(
                new AgentRunExecuteRequest(
                        withWorkflowPlanId(plan, workflowId + ":" + stageCode),
                        stagePrompt(stageCode, request),
                        request.evidenceRefs(),
                        request.dryRun()),
                subject);
        return new MultiAgentWritingResponse.StageResult(
                stageCode,
                execution.agentCode(),
                execution.traceId(),
                execution.providerName(),
                execution.modelCode(),
                execution.status(),
                execution.actualUsage(),
                execution.message(),
                execution.generatedContent());
    }

    /**
     * Replaces the generated plan id so all stage traces can be recovered by workflow id.
     */
    private static AgentRunPlanResponse withWorkflowPlanId(AgentRunPlanResponse plan, String planId) {
        return new AgentRunPlanResponse(
                planId,
                plan.tenantId(),
                plan.subjectType(),
                plan.subjectId(),
                plan.agentCode(),
                plan.providerName(),
                plan.modelCode(),
                plan.modelLevel(),
                plan.allowedToolScopes(),
                plan.deniedToolScopes(),
                plan.toolPolicyDecisions(),
                plan.allowedDataScopes(),
                plan.deniedDataScopes(),
                plan.capabilityRequired(),
                plan.capabilityAction(),
                plan.maxInputTokens(),
                plan.maxOutputTokens(),
                plan.estimatedTotalTokens(),
                plan.estimatedCost(),
                plan.withinBudget(),
                plan.routeReason(),
                plan.stageTimings(),
                plan.concurrencyKeys(),
                plan.requiredJsonSchema());
    }

    /**
     * Builds a compact stage prompt safe for trace storage.
     */
    private static String stagePrompt(String stageCode, MultiAgentWritingRequest request) {
        return "stage=" + stageCode
                + "; goal=" + request.writingGoal()
                + "; question=" + request.questionText();
    }

    /**
     * Returns a user-safe progress message for a finished stage.
     */
    private static String stageCompletionMessage(String stageCode) {
        return switch (stageCode) {
            case "draft" -> "Draft stage completed.";
            case "review" -> "Review stage completed.";
            case "format" -> "Format stage completed.";
            default -> "Writing stage completed.";
        };
    }

    /**
     * Sums provider-reported token usage across all stages.
     */
    private static AgentRunExecuteResponse.TokenUsage totalUsage(List<MultiAgentWritingResponse.StageResult> stages) {
        int promptTokens = stages.stream().mapToInt(stage -> stage.actualUsage().promptTokens()).sum();
        int completionTokens = stages.stream().mapToInt(stage -> stage.actualUsage().completionTokens()).sum();
        int totalTokens = stages.stream().mapToInt(stage -> stage.actualUsage().totalTokens()).sum();
        return new AgentRunExecuteResponse.TokenUsage(promptTokens, completionTokens, totalTokens);
    }

    /**
     * Saves one safe workflow status snapshot.
     */
    private MultiAgentWritingWorkflowRecord saveWorkflow(
            String workflowId,
            RequestSubject subject,
            String status,
            Instant createdAt,
            List<MultiAgentWritingResponse.StageResult> stages,
            String message) {
        return workflowStore.save(new MultiAgentWritingWorkflowRecord(
                workflowId,
                subject.tenantId(),
                subject.subjectType(),
                subject.subjectId(),
                status,
                createdAt,
                Instant.now(),
                List.copyOf(stages),
                totalUsage(stages),
                message));
    }

    /**
     * Converts a stored workflow snapshot to the public response shape.
     */
    private static MultiAgentWritingResponse toResponse(MultiAgentWritingWorkflowRecord record) {
        MultiAgentWritingWorkflowRecord normalized = record.normalize();
        return new MultiAgentWritingResponse(
                normalized.workflowId(),
                normalized.tenantId(),
                normalized.subjectType(),
                normalized.subjectId(),
                normalized.status(),
                normalized.createdAt(),
                normalized.updatedAt(),
                normalized.stages(),
                normalized.totalUsage(),
                normalized.message());
    }

    /**
     * Builds an owner-visible writing artifact from completed stage snapshots.
     *
     * @param workflowId workflow id
     * @param subject backend subject
     * @return merged generated content for this workflow
     */
    public MultiAgentWritingArtifact artifact(String workflowId, RequestSubject subject) {
        MultiAgentWritingWorkflowRecord record = workflowStore
                .findVisible(normalizedWorkflowId(workflowId), subject.normalize())
                .orElseThrow(() -> new IllegalArgumentException("Multi-agent writing workflow not found"));
        MultiAgentWritingWorkflowRecord normalized = record.normalize();
        List<MultiAgentWritingArtifact.StageArtifact> stageArtifacts = normalized.stages().stream()
                .map(stage -> new MultiAgentWritingArtifact.StageArtifact(
                        stage.stageCode(),
                        stage.agentCode(),
                        stage.traceId(),
                        stage.providerName(),
                        stage.modelCode(),
                        stage.status(),
                        safeArtifactText(stage.generatedContent())))
                .toList();
        return new MultiAgentWritingArtifact(
                normalized.workflowId(),
                normalized.tenantId(),
                normalized.subjectType(),
                normalized.subjectId(),
                normalized.status(),
                normalized.totalUsage(),
                stageArtifacts,
                mergedArtifactMarkdown(stageArtifacts));
    }

    /**
     * Normalizes one stage artifact body for owner-facing display.
     */
    private static String safeArtifactText(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String normalized = value.strip();
        return structuredArtifactText(normalized).orElse(normalized);
    }

    /**
     * Extracts readable Markdown from structured JSON stage output when the agent used a JSON contract.
     */
    private static Optional<String> structuredArtifactText(String value) {
        if (!value.startsWith("{")) {
            return Optional.empty();
        }
        try {
            JsonNode root = ARTIFACT_OBJECT_MAPPER.readTree(value);
            if (!root.isObject()) {
                return Optional.empty();
            }
            for (String field : List.of("markdown", "content", "review", "body", "result")) {
                JsonNode node = root.get(field);
                if (node != null && node.isTextual() && !node.asText().isBlank()) {
                    return Optional.of(node.asText().strip());
                }
            }
            return Optional.empty();
        } catch (Exception exception) {
            return Optional.empty();
        }
    }

    /**
     * Merges completed stage artifacts into one Markdown document.
     */
    private static String mergedArtifactMarkdown(List<MultiAgentWritingArtifact.StageArtifact> stages) {
        StringBuilder builder = new StringBuilder();
        for (MultiAgentWritingArtifact.StageArtifact stage : stages) {
            if (stage.generatedContent().isBlank()) {
                continue;
            }
            if (!builder.isEmpty()) {
                builder.append("\n\n");
            }
            builder.append("## ").append(stageTitle(stage.stageCode())).append("\n\n")
                    .append(stage.generatedContent());
        }
        return builder.toString();
    }

    /**
     * Returns a readable stage title for merged Markdown artifacts.
     */
    private static String stageTitle(String stageCode) {
        return switch (stageCode) {
            case "draft" -> "讲义初稿";
            case "review" -> "质量检查";
            case "format" -> "格式整理";
            default -> stageCode;
        };
    }

    /**
     * Converts an exception to a short safe workflow status message.
     */
    private static String safeFailureMessage(RuntimeException exception) {
        String message = exception.getMessage();
        if (message == null || message.isBlank()) {
            return "Multi-agent writing workflow failed.";
        }
        return "Multi-agent writing workflow failed: " + message.strip();
    }

    /**
     * Immutable definition for one deterministic writing stage.
     */
    private record WritingStageSpec(
            String stageCode,
            String agentCode,
            String taskType,
            List<String> requestedTools) {
    }
}
