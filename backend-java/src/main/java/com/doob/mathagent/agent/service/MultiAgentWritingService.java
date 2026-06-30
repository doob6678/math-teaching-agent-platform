package com.doob.mathagent.agent.service;

import com.doob.mathagent.agent.dto.AgentRunExecuteRequest;
import com.doob.mathagent.agent.dto.AgentRunPlanRequest;
import com.doob.mathagent.agent.dto.MultiAgentWritingRequest;
import com.doob.mathagent.agent.vo.AgentRunExecuteResponse;
import com.doob.mathagent.agent.vo.AgentRunPlanResponse;
import com.doob.mathagent.agent.vo.MultiAgentWritingResponse;
import com.doob.mathagent.infrastructure.security.RequestSubject;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;

/**
 * Orchestrates a real multi-agent writing workflow through the existing AgentRun plan and execution services.
 */
@Service
public class MultiAgentWritingService {

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
     * Creates a service with an explicit workflow store and synchronous executor for focused tests.
     *
     * @param planService backend agent planner
     * @param executionService backend agent executor
     * @param workflowStore workflow status store
     */
    public MultiAgentWritingService(
            AgentRunPlanService planService,
            AgentRunExecutionService executionService,
            MultiAgentWritingWorkflowStore workflowStore) {
        this(planService, executionService, workflowStore, new SyncTaskExecutor());
    }

    /**
     * Creates a service with in-memory workflow status storage for focused tests.
     *
     * @param planService backend agent planner
     * @param executionService backend agent executor
     */
    public MultiAgentWritingService(
            AgentRunPlanService planService,
            AgentRunExecutionService executionService) {
        this(planService, executionService, new InMemoryMultiAgentWritingWorkflowStore(), new SyncTaskExecutor());
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
        RequestSubject normalizedSubject = subject.normalize();
        requireTeacherOrAdmin(normalizedSubject);
        return executeWorkflow(UUID.randomUUID().toString(), Instant.now(), normalized, normalizedSubject);
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
                executeWorkflow(workflowId, createdAt, normalized, normalizedSubject);
            } catch (RuntimeException ignored) {
                // executeWorkflow already persisted FAILED with a safe message.
            }
        });
        return toResponse(started);
    }

    /**
     * Runs draft, review, and format stages for an existing workflow id.
     */
    private MultiAgentWritingResponse executeWorkflow(
            String workflowId,
            Instant createdAt,
            MultiAgentWritingRequest normalized,
            RequestSubject normalizedSubject) {
        if (!"teacher".equals(normalizedSubject.subjectType()) && !"admin".equals(normalizedSubject.subjectType())) {
            throw new IllegalArgumentException("Multi-agent writing requires teacher or admin subject");
        }
        List<MultiAgentWritingResponse.StageResult> stages = new ArrayList<>();
        saveWorkflow(workflowId, normalizedSubject, "RUNNING", createdAt, stages, "Multi-agent writing workflow started.");
        try {
            stages.add(runStage(
                    workflowId,
                    "draft",
                    "CoursewareAgent",
                    "courseware_generation",
                    normalized,
                    normalizedSubject,
                    List.of("tool:courseware:generate", "tool:search:textbook", "tool:search:private")));
            saveWorkflow(workflowId, normalizedSubject, "RUNNING", createdAt, stages, "Draft stage completed.");
            stages.add(runStage(
                    workflowId,
                    "review",
                    "QualityCheckAgent",
                    "quality_review",
                    normalized,
                    normalizedSubject,
                    List.of("tool:quality:check")));
            saveWorkflow(workflowId, normalizedSubject, "RUNNING", createdAt, stages, "Review stage completed.");
            stages.add(runStage(
                    workflowId,
                    "format",
                    "HandoutFormatterAgent",
                    "handout_formatting",
                    normalized,
                    normalizedSubject,
                    List.of("tool:handout:format")));
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
                execution.message());
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
                plan.concurrencyKeys());
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
     * Converts an exception to a short safe workflow status message.
     */
    private static String safeFailureMessage(RuntimeException exception) {
        String message = exception.getMessage();
        if (message == null || message.isBlank()) {
            return "Multi-agent writing workflow failed.";
        }
        return "Multi-agent writing workflow failed: " + message.strip();
    }
}
