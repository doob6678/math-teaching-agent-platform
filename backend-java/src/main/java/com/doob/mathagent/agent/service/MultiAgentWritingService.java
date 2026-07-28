package com.doob.mathagent.agent.service;

import com.doob.mathagent.agent.dto.AgentRunExecuteRequest;
import com.doob.mathagent.agent.dto.AgentRunPlanRequest;
import com.doob.mathagent.agent.dto.MultiAgentWritingRequest;
import com.doob.mathagent.agent.vo.AgentRunExecuteResponse;
import com.doob.mathagent.agent.vo.AgentRunPlanResponse;
import com.doob.mathagent.agent.vo.MultiAgentWritingResponse;
import com.doob.mathagent.agent.worker.AgentWorkerTask;
import com.doob.mathagent.agent.worker.AgentWorkerTaskPublisher;
import com.doob.mathagent.agent.worker.AgentWorkerTaskStore;
import com.doob.mathagent.infrastructure.security.RequestSubject;
import com.doob.mathagent.teacher.search.TeacherResourceBlockSearchResponse;
import com.doob.mathagent.teacher.service.TeacherResourceBlockSearchService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

/**
 * Orchestrates a real multi-agent writing workflow through the existing AgentRun plan and execution services.
 */
@Service
public class MultiAgentWritingService {

    private static final ObjectMapper ARTIFACT_OBJECT_MAPPER = new ObjectMapper();
    private static final int MAX_PARALLEL_STAGE_WORKERS = 3;
    private static final int MAX_PROMPT_GOAL_CHARS = 600;
    private static final int MAX_PROMPT_QUESTION_CHARS = 1800;
    private static final int MAX_ARTIFACT_CHARS_PER_STAGE = 2400;
    private static final int MAX_ARTIFACT_CONTEXT_CHARS = 12000;
    /**
     * Evidence references are identifiers, not a transport for source documents. Keeping the list and each item
     * bounded prevents a large retrieval response from consuming the next agent's input budget or leaking raw files.
     */
    private static final int MAX_EVIDENCE_REFS = 24;
    private static final int MAX_EVIDENCE_REF_CHARS = 240;
    /** Writing needs source text, not opaque ids; keep this small enough to preserve room for the handout itself. */
    private static final int WRITING_EVIDENCE_HIT_LIMIT = 4;
    private static final int MAX_WRITING_EVIDENCE_CHARS_PER_HIT = 1_200;

    /*
     * Every group is a persisted barrier: later agents can consume only completed group artifacts. The middle writer
     * and reviewer groups intentionally use three distinct policy-approved agent identities, allowing bounded
     * parallelism while the existing user/tenant concurrency guard still prevents duplicate role execution.
     */
    private static final List<WritingStageGroup> WRITING_STAGE_GROUPS = List.of(
            new WritingStageGroup("evidence", List.of(
                    stage("resource_curation", "TeacherAssistantAgent", "resource_curation",
                            List.of("tool:search:textbook", "tool:search:private"), List.of(), false,
                            "Collect only owner-authorized evidence anchors and compact source summaries."))),
            new WritingStageGroup("template", List.of(
                    stage("template_selection", "HandoutFormatterAgent", "template_selection",
                            List.of("tool:handout:format"), List.of("resource_curation"), true,
                            "Select a classroom handout structure from compact evidence and teaching goal."))),
            new WritingStageGroup("outline", List.of(
                    stage("outline_planning", "CoursewareAgent", "outline_planning",
                            List.of("tool:courseware:generate"), List.of("resource_curation", "template_selection"), true,
                            "Produce a shared outline with evidence anchors, no full final handout."))),
            new WritingStageGroup("versions", List.of(
                    stage("teacher_writer", "CoursewareAgent", "teacher_handout_writing",
                            List.of("tool:courseware:generate"), List.of("resource_curation", "template_selection", "outline_planning"), true,
                            "Write the teacher version with cited reasoning, answers, and review notes."),
                    stage("student_writer", "TeacherAssistantAgent", "student_handout_writing",
                            List.of(), List.of("resource_curation", "template_selection", "outline_planning"), true,
                            "Write a student worksheet from the outline without answers, teacher notes, or hidden reasoning."),
                    stage("lecture_writer", "HandoutFormatterAgent", "lecture_handout_writing",
                            List.of("tool:handout:format"), List.of("resource_curation", "template_selection", "outline_planning"), true,
                            "Write an independent 16:10 lecture card sequence from the shared outline."))),
            new WritingStageGroup("reviews", List.of(
                    stage("source_review", "QualityCheckAgent", "source_review",
                            List.of("tool:quality:check"), List.of("resource_curation", "outline_planning", "teacher_writer", "student_writer", "lecture_writer"), true,
                            "Check every source assertion against supplied evidence anchors."),
                    stage("student_safety_review", "TeacherAssistantAgent", "student_safety_review",
                            List.of(), List.of("outline_planning", "student_writer"), true,
                            "Check the student version for answer, teacher-note, prompt, and internal-reasoning leakage."),
                    stage("layout_review", "HandoutFormatterAgent", "layout_review",
                            List.of("tool:handout:format"), List.of("outline_planning", "teacher_writer", "student_writer", "lecture_writer"), true,
                            "Check continuous question flow, 16:10 layout constraints, placeholders, and unsafe visible labels."))),
            new WritingStageGroup("merge", List.of(
                    stage("merge_coordinator", "HandoutFormatterAgent", "handout_merge",
                            List.of("tool:handout:format"), List.of(
                                    "resource_curation", "template_selection", "outline_planning",
                                    "teacher_writer", "student_writer", "lecture_writer",
                                    "source_review", "student_safety_review", "layout_review"), true,
                            "Merge only approved patches into owner-visible final artifacts; never expose prompts or diagnostics."))));

    private static final List<WritingStageSpec> WRITING_STAGES = WRITING_STAGE_GROUPS.stream()
            .flatMap(group -> group.stages().stream())
            .toList();

    private final AgentRunPlanService planService;
    private final AgentRunExecutionService executionService;
    private final MultiAgentWritingWorkflowStore workflowStore;
    private final TaskExecutor taskExecutor;
    private final AgentWorkerTaskStore workerTaskStore;
    private final AgentWorkerTaskPublisher workerTaskPublisher;
    private final Environment environment;
    private final TeacherResourceBlockSearchService teacherResourceBlockSearchService;

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
            @Qualifier("multiAgentWritingTaskExecutor") TaskExecutor taskExecutor,
            AgentWorkerTaskStore workerTaskStore,
            AgentWorkerTaskPublisher workerTaskPublisher,
            Environment environment,
            TeacherResourceBlockSearchService teacherResourceBlockSearchService) {
        this.planService = planService;
        this.executionService = executionService;
        this.workflowStore = workflowStore;
        this.taskExecutor = taskExecutor;
        this.workerTaskStore = workerTaskStore;
        this.workerTaskPublisher = workerTaskPublisher;
        this.environment = environment;
        this.teacherResourceBlockSearchService = teacherResourceBlockSearchService;
    }

    /** Compatibility constructor preserves focused tests that intentionally exercise the in-process fallback. */
    public MultiAgentWritingService(AgentRunPlanService planService, AgentRunExecutionService executionService,
            MultiAgentWritingWorkflowStore workflowStore, TaskExecutor taskExecutor) {
        this(planService, executionService, workflowStore, taskExecutor, null, null, null, null);
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
        if (distributedWorkerDispatchEnabled()) {
            if (workerTaskStore == null || workerTaskPublisher == null) {
                throw new IllegalStateException("Distributed Agent Worker dispatch is not configured");
            }
            dispatchReadyStageTasks(workflowId, normalizedSubject, normalized, List.of());
            return toResponse(started);
        }
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

    /** Executes one durable worker-owned workflow using the original workflow id, preserving status and trace APIs. */
    public MultiAgentWritingResponse executeDispatched(String workflowId, MultiAgentWritingRequest request, RequestSubject subject) {
        MultiAgentWritingWorkflowRecord existing = workflowStore.findVisible(workflowId, subject.normalize())
                .orElseThrow(() -> new IllegalArgumentException("Multi-agent writing workflow not found"));
        return executeWorkflow(workflowId, existing.createdAt(), request.normalize(), subject.normalize(),
                validCompletedStages(existing.stages()), "Multi-agent writing workflow started by Agent Worker.");
    }

    /** Executes exactly one leased stage and only unlocks the next barrier after every sibling has completed. */
    public MultiAgentWritingResponse executeDispatchedStage(
            String workflowId, String stageCode, MultiAgentWritingRequest request, RequestSubject subject) {
        RequestSubject normalizedSubject = subject.normalize();
        MultiAgentWritingWorkflowRecord existing = workflowStore.findVisible(workflowId, normalizedSubject)
                .orElseThrow(() -> new IllegalArgumentException("Multi-agent writing workflow not found"));
        List<MultiAgentWritingResponse.StageResult> stages = new ArrayList<>(validCompletedStages(existing.stages()));
        WritingStageSpec spec = WRITING_STAGES.stream().filter(stage -> stage.stageCode().equals(stageCode))
                .findFirst().orElseThrow(() -> new IllegalArgumentException("Unknown writing stage: " + stageCode));
        WritingStageGroup group = WRITING_STAGE_GROUPS.stream().filter(candidate -> candidate.stages().contains(spec))
                .findFirst().orElseThrow(() -> new IllegalStateException("Writing stage group is missing"));
        if (!allEarlierGroupsCompleted(group, stages)) {
            throw new IllegalStateException("Writing stage prerequisites are incomplete");
        }
        if (stageCodes(stages).contains(stageCode)) {
            return toResponse(existing);
        }
        MultiAgentWritingResponse.StageResult result = runStage(workflowId, spec, request.normalize(), normalizedSubject, List.copyOf(stages));
        mergeStageResults(stages, List.of(result));
        boolean groupComplete = group.stages().stream().allMatch(stage -> stageCodes(stages).contains(stage.stageCode()));
        boolean workflowComplete = WRITING_STAGES.stream().allMatch(stage -> stageCodes(stages).contains(stage.stageCode()));
        String message = workflowComplete ? "Multi-agent writing workflow completed." : stageCompletionMessage(group.groupCode());
        MultiAgentWritingWorkflowRecord saved = saveWorkflow(workflowId, normalizedSubject,
                workflowComplete ? "COMPLETED" : "RUNNING", existing.createdAt(), stages, message);
        if (groupComplete && !workflowComplete) {
            dispatchReadyStageTasks(workflowId, normalizedSubject, request.normalize(), stages);
        }
        return toResponse(saved);
    }

    /**
     * Marks a distributed workflow recoverable after its stage task exhausts durable retries.
     * Completed sibling stages remain persisted so a later resume does not repeat successful provider calls.
     */
    public MultiAgentWritingResponse failDispatchedStage(
            String workflowId, RequestSubject subject, String errorSummary) {
        RequestSubject normalizedSubject = subject.normalize();
        MultiAgentWritingWorkflowRecord existing = workflowStore.findVisible(workflowId, normalizedSubject)
                .orElseThrow(() -> new IllegalArgumentException("Multi-agent writing workflow not found"));
        String safeMessage = errorSummary == null || errorSummary.isBlank()
                ? "Distributed writing stage failed after retries."
                : "Distributed writing stage failed after retries: "
                        + errorSummary.substring(0, Math.min(300, errorSummary.length()));
        return toResponse(saveWorkflow(
                workflowId,
                normalizedSubject,
                "FAILED",
                existing.createdAt(),
                validCompletedStages(existing.stages()),
                safeMessage));
    }

    /** Creates opaque tasks only for the first dependency-ready barrier; later barriers are released by completion. */
    private void dispatchReadyStageTasks(String workflowId, RequestSubject subject, MultiAgentWritingRequest request,
            List<MultiAgentWritingResponse.StageResult> completedStages) {
        for (WritingStageGroup group : WRITING_STAGE_GROUPS) {
            if (group.stages().stream().allMatch(stage -> stageCodes(completedStages).contains(stage.stageCode()))) continue;
            if (!allEarlierGroupsCompleted(group, completedStages)) return;
            for (WritingStageSpec stage : group.stages()) {
                if (!stageCodes(completedStages).contains(stage.stageCode())) {
                    AgentWorkerTask task = workerTaskStore.create(workflowId, subject.tenantId(), stage.agentCode(),
                            stage.stageCode(), workerTaskPayload(request, subject));
                    workerTaskPublisher.publish(task);
                }
            }
            return;
        }
    }

    private boolean distributedWorkerDispatchEnabled() {
        return environment != null && Boolean.parseBoolean(environment.getProperty("math-agent.agent-worker.dispatch.enabled", "true"));
    }

    private static String workerTaskPayload(MultiAgentWritingRequest request, RequestSubject subject) {
        try {
            return ARTIFACT_OBJECT_MAPPER.writeValueAsString(java.util.Map.of("request", request, "subject", subject));
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to persist distributed Agent Worker task payload", exception);
        }
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
        List<MultiAgentWritingResponse.StageResult> completedStages = validCompletedStages(normalizedRecord.stages());
        /*
         * A browser recovery must be quick and must use the same durable Worker path as a
         * newly-created workflow.  Calling executeWorkflow here used to hold the HTTP request
         * open for every remaining model call, so a teacher could not tell whether recovery had
         * started and a proxy timeout could create a second recovery attempt.  Persist RUNNING
         * before publishing: Worker redelivery is safe because completed stage codes are retained.
         */
        if (distributedWorkerDispatchEnabled()) {
            if (workerTaskStore == null || workerTaskPublisher == null) {
                throw new IllegalStateException("Distributed Agent Worker dispatch is not configured");
            }
            MultiAgentWritingWorkflowRecord resumed = saveWorkflow(
                    normalizedRecord.workflowId(),
                    normalizedSubject,
                    "RUNNING",
                    normalizedRecord.createdAt(),
                    completedStages,
                    "Multi-agent writing workflow resumed; queued only unfinished stages.");
            dispatchReadyStageTasks(resumed.workflowId(), normalizedSubject, normalized, completedStages);
            return toResponse(resumed);
        }
        // The local development fallback still runs asynchronously so clicking recovery never blocks the UI.
        MultiAgentWritingWorkflowRecord resumed = saveWorkflow(
                normalizedRecord.workflowId(),
                normalizedSubject,
                "RUNNING",
                normalizedRecord.createdAt(),
                completedStages,
                "Multi-agent writing workflow resumed; running unfinished stages.");
        taskExecutor.execute(() -> {
            try {
                executeWorkflow(
                        normalizedRecord.workflowId(),
                        normalizedRecord.createdAt(),
                        normalized,
                        normalizedSubject,
                        completedStages,
                        "Multi-agent writing workflow resumed.");
            } catch (RuntimeException ignored) {
                // executeWorkflow persists a safe FAILED snapshot that can be retried from the UI.
            }
        });
        // A direct executor is used by focused tests; reading back preserves its completed snapshot.
        // A real async executor returns the just-persisted RUNNING status immediately.
        return toResponse(workflowStore.findVisible(normalizedRecord.workflowId(), normalizedSubject).orElse(resumed));
    }

    /**
     * Runs deterministic stage barriers for an existing workflow id, skipping a durable completed prefix on resume.
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
            /*
             * A failed fan-out may have durable results for only some branches. Resume works from stage codes rather
             * than a numeric prefix so a successful teacher branch is retained while only the failed student branch
             * is executed again. The group order remains the durable barrier between collection, drafting and review.
             */
            for (WritingStageGroup group : WRITING_STAGE_GROUPS) {
                List<String> completedStageCodes = stageCodes(stages);
                if (group.stages().stream().allMatch(stage -> completedStageCodes.contains(stage.stageCode()))) {
                    continue;
                }
                if (!allEarlierGroupsCompleted(group, stages)) {
                    throw new IllegalStateException("Workflow resume state has an incomplete prerequisite stage group");
                }
                List<MultiAgentWritingResponse.StageResult> completedGroup = runStageGroup(
                        workflowId,
                        group,
                        normalized,
                        normalizedSubject,
                        List.copyOf(stages));
                mergeStageResults(stages, completedGroup);
                saveWorkflow(
                        workflowId,
                        normalizedSubject,
                        "RUNNING",
                        createdAt,
                        stages,
                        stageCompletionMessage(group.groupCode()));
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
            if (exception instanceof ParallelStageFailure failure) {
                // Preserve every branch that completed before the sibling failed so the next resume can skip it.
                mergeStageResults(stages, failure.completedStages());
            }
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
     * Executes one sequential stage or a bounded independent fan-out, returning results in declared rather than race
     * completion order. That stable order is the durable resume and artifact contract.
     */
    private List<MultiAgentWritingResponse.StageResult> runStageGroup(
            String workflowId,
            WritingStageGroup group,
            MultiAgentWritingRequest request,
            RequestSubject subject,
            List<MultiAgentWritingResponse.StageResult> completedStages) {
        List<String> completedStageCodes = stageCodes(completedStages);
        List<WritingStageSpec> pendingStages = group.stages().stream()
                .filter(stage -> !completedStageCodes.contains(stage.stageCode()))
                .toList();
        if (pendingStages.isEmpty()) {
            return List.of();
        }
        if (pendingStages.size() == 1) {
            return List.of(runStage(workflowId, pendingStages.getFirst(), request, subject, completedStages));
        }
        int workerCount = Math.min(MAX_PARALLEL_STAGE_WORKERS, pendingStages.size());
        ExecutorService groupExecutor = Executors.newFixedThreadPool(workerCount);
        try {
            List<CompletableFuture<MultiAgentWritingResponse.StageResult>> futures = pendingStages.stream()
                    .map(stage -> CompletableFuture.supplyAsync(
                            () -> runStage(workflowId, stage, request, subject, completedStages),
                            groupExecutor))
                    .toList();
            List<MultiAgentWritingResponse.StageResult> results = new ArrayList<>(futures.size());
            RuntimeException firstFailure = null;
            for (CompletableFuture<MultiAgentWritingResponse.StageResult> future : futures) {
                try {
                    results.add(future.join());
                } catch (CompletionException exception) {
                    Throwable cause = exception.getCause();
                    if (firstFailure == null) {
                        firstFailure = cause instanceof RuntimeException runtimeException
                                ? runtimeException
                                : new IllegalStateException("Parallel writing stage failed", cause);
                    }
                }
            }
            if (firstFailure != null) {
                throw new ParallelStageFailure(firstFailure, results);
            }
            return List.copyOf(results);
        } finally {
            groupExecutor.shutdownNow();
        }
    }

    /** Returns completed stage codes in a stable insertion order for resume and dependency checks. */
    private static List<String> stageCodes(List<MultiAgentWritingResponse.StageResult> stages) {
        if (stages == null || stages.isEmpty()) {
            return List.of();
        }
        return stages.stream()
                .filter(stage -> stage != null && "COMPLETED".equals(stage.status()))
                .map(MultiAgentWritingResponse.StageResult::stageCode)
                .filter(code -> code != null && !code.isBlank())
                .distinct()
                .toList();
    }

    /**
     * Ensures every group before the requested one has completed. This guards against manually corrupted snapshots
     * while still allowing a partial result inside the current parallel group.
     */
    private static boolean allEarlierGroupsCompleted(
            WritingStageGroup requestedGroup,
            List<MultiAgentWritingResponse.StageResult> completedStages) {
        List<String> completedCodes = stageCodes(completedStages);
        for (WritingStageGroup group : WRITING_STAGE_GROUPS) {
            if (group == requestedGroup) {
                return true;
            }
            if (!group.stages().stream().allMatch(stage -> completedCodes.contains(stage.stageCode()))) {
                return false;
            }
        }
        return false;
    }

    /**
     * Merges branch results without changing the declared topology. A retry can return the same stage code, so the
     * new result replaces the previous snapshot instead of creating a second visible teacher/student version.
     */
    private static void mergeStageResults(
            List<MultiAgentWritingResponse.StageResult> target,
            List<MultiAgentWritingResponse.StageResult> additions) {
        if (additions == null || additions.isEmpty()) {
            return;
        }
        for (MultiAgentWritingResponse.StageResult addition : additions) {
            target.removeIf(existing -> existing.stageCode().equals(addition.stageCode()));
            int insertionIndex = 0;
            while (insertionIndex < target.size()
                    && stageOrder(target.get(insertionIndex).stageCode()) < stageOrder(addition.stageCode())) {
                insertionIndex += 1;
            }
            target.add(insertionIndex, addition);
        }
    }

    /** Returns the declared stage order used when replacing a partial branch snapshot. */
    private static int stageOrder(String stageCode) {
        for (int index = 0; index < WRITING_STAGES.size(); index += 1) {
            if (WRITING_STAGES.get(index).stageCode().equals(stageCode)) {
                return index;
            }
        }
        return WRITING_STAGES.size();
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
    private static List<MultiAgentWritingResponse.StageResult> validCompletedStages(
            List<MultiAgentWritingResponse.StageResult> stages) {
        List<MultiAgentWritingResponse.StageResult> safeStages = stages == null ? List.of() : stages;
        List<MultiAgentWritingResponse.StageResult> valid = new ArrayList<>();
        for (WritingStageGroup group : WRITING_STAGE_GROUPS) {
            List<MultiAgentWritingResponse.StageResult> groupResults = group.stages().stream()
                    .map(expected -> safeStages.stream()
                            .filter(stage -> stage != null
                                    && expected.stageCode().equals(stage.stageCode())
                                    && "COMPLETED".equals(stage.status()))
                            .findFirst()
                            .orElse(null))
                    .filter(java.util.Objects::nonNull)
                    .toList();
            if (groupResults.isEmpty()) {
                break;
            }
            valid.addAll(groupResults);
            if (groupResults.size() < group.stages().size()) {
                // A partial fan-out is resumable, but no later barrier may be trusted yet.
                break;
            }
        }
        return List.copyOf(valid);
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
            WritingStageSpec stage,
            MultiAgentWritingRequest request,
            RequestSubject subject,
            List<MultiAgentWritingResponse.StageResult> completedStages) {
        long startedNanos = System.nanoTime();
        AgentRunPlanResponse plan = planService.plan(
                new AgentRunPlanRequest(
                        stage.agentCode(),
                        stage.taskType(),
                        "teacher",
                        3200,
                        1600,
                        false,
                        true,
                        "medium",
                        "normal",
                        3.0,
                        0,
                        stage.requiresStructuredOutput(),
                        stage.requestedTools(),
                        List.of(),
                        List.of("PUBLIC_TEXTBOOK", "TEACHER_PRIVATE", "CLASS_AUTHORIZED"),
                        stage.agentCode().equals("CoursewareAgent"),
                        request.preferredProviderName(),
                        request.preferredModelCode()),
                subject);
        AgentRunExecuteResponse execution = executionService.execute(
                new AgentRunExecuteRequest(
                        withWorkflowPlanId(plan, workflowId + ":" + stage.stageCode()),
                        stagePrompt(stage, request, completedStages, writingEvidenceContext(stage, request, subject)),
                        compactEvidenceRefs(request.evidenceRefs()),
                        request.dryRun()),
                subject);
        return new MultiAgentWritingResponse.StageResult(
                stage.stageCode(),
                execution.agentCode(),
                execution.traceId(),
                execution.providerName(),
                execution.modelCode(),
                execution.status(),
                execution.actualUsage(),
                execution.message(),
                execution.generatedContent(),
                Math.max(0L, (System.nanoTime() - startedNanos) / 1_000_000L));
    }

    /**
     * Replaces the generated plan id so all stage traces can be recovered by workflow id.
     */
    private static AgentRunPlanResponse withWorkflowPlanId(AgentRunPlanResponse plan, String planId) {
        /*
         * The ordinary model key is an exclusive lock. Parallel branches in this workflow already have distinct
         * user/tenant/agent keys and a fixed three-worker bound, so retaining that key would turn the declared fan-out
         * into a serial queue. Provider-wide throttling remains the responsibility of the configured gateway.
         */
        List<String> workflowConcurrencyKeys = plan.concurrencyKeys().stream()
                .filter(key -> !key.startsWith("concurrent:model:"))
                .toList();
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
                workflowConcurrencyKeys,
                plan.requiredJsonSchema());
    }

    /**
     * Builds a compact stage prompt safe for trace storage.
     */
    private static String stagePrompt(
            WritingStageSpec stage,
            MultiAgentWritingRequest request,
            List<MultiAgentWritingResponse.StageResult> completedStages,
            String writingEvidenceContext) {
        String basePrompt = "stage=" + stage.stageCode()
                + "; role=" + stage.roleBrief()
                + "; goal=" + compactPromptText(request.writingGoal(), MAX_PROMPT_GOAL_CHARS)
                + "; question=" + compactPromptText(request.questionText(), MAX_PROMPT_QUESTION_CHARS)
                + "\n\n" + MultiAgentHandoutPromptProfiles.instructionsFor(stage.stageCode());
        String previousArtifacts = previousStageArtifacts(completedStages, stage.allowedArtifactStages());
        String promptWithEvidence = writingEvidenceContext == null || writingEvidenceContext.isBlank()
                ? basePrompt
                : basePrompt + "\n\nAuthorized teacher-source evidence (cite these facts; do not claim an evidence gap for text below):\n"
                        + writingEvidenceContext;
        if (previousArtifacts.isBlank()) {
            return promptWithEvidence;
        }
        return promptWithEvidence + "\n\nPrevious completed stages:\n" + previousArtifacts;
    }

    /**
     * Resolves real, permission-filtered teacher-source blocks before the resource curator calls the model.
     *
     * <p>Earlier code passed only opaque document ids.  That made the model correctly refuse to attribute methods
     * to a source it could not read.  This adapter deliberately uses the existing audited retrieval service rather
     * than reading files or bypassing tenant/subject visibility, and it exposes only compact evidence snippets plus
     * backend-controlled image URIs.</p>
     */
    private String writingEvidenceContext(
            WritingStageSpec stage, MultiAgentWritingRequest request, RequestSubject subject) {
        if (!"resource_curation".equals(stage.stageCode()) || teacherResourceBlockSearchService == null) {
            return "";
        }
        String query = compactPromptText(request.questionText() + " " + request.writingGoal(), MAX_PROMPT_GOAL_CHARS);
        try {
            TeacherResourceBlockSearchResponse response = teacherResourceBlockSearchService.search(
                    subject.tenantId(),
                    subject.subjectType(),
                    subject.subjectId(),
                    query,
                    WRITING_EVIDENCE_HIT_LIMIT,
                    "/internal/agents/writing/evidence");
            return response.hits().stream()
                    .map(hit -> writingEvidenceSnippet(hit))
                    .filter(snippet -> !snippet.isBlank())
                    .collect(java.util.stream.Collectors.joining("\n\n"));
        } catch (RuntimeException exception) {
            // Retrieval availability must not discard a teacher's workflow; the trace still records the model result.
            return "";
        }
    }

    /** Formats a bounded, cited evidence block that later writer stages inherit through resource_curation. */
    private static String writingEvidenceSnippet(TeacherResourceBlockSearchResponse.Hit hit) {
        String text = compactPromptText(hit.evidenceText(), MAX_WRITING_EVIDENCE_CHARS_PER_HIT);
        if (text.isBlank()) {
            return "";
        }
        String assets = hit.assetRefs().stream()
                .map(TeacherResourceBlockSearchResponse.AssetRef::assetUri)
                .filter(uri -> uri != null && !uri.isBlank())
                .limit(2)
                .collect(java.util.stream.Collectors.joining(", "));
        return "[source document=" + hit.documentId() + "; block=" + hit.blockId() + "; title="
                + compactPromptText(hit.documentTitle(), 120) + "]\n" + text
                + (assets.isBlank() ? "" : "\nTEACHER_IMAGE: " + assets);
    }

    /**
     * Carries owned workflow artifacts forward so later agents review and format the actual prior draft.
     */
    private static String previousStageArtifacts(
            List<MultiAgentWritingResponse.StageResult> completedStages,
            List<String> allowedStageCodes) {
        if (completedStages == null || completedStages.isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        for (MultiAgentWritingResponse.StageResult stage : completedStages) {
            if (allowedStageCodes == null || !allowedStageCodes.contains(stage.stageCode())) {
                continue;
            }
            String content = artifactPromptExcerpt(stage.generatedContent());
            if (content.isBlank()) {
                continue;
            }
            if (builder.length() + content.length() > MAX_ARTIFACT_CONTEXT_CHARS) {
                break;
            }
            if (!builder.isEmpty()) {
                builder.append("\n\n");
            }
            builder.append("- ")
                    .append(stage.stageCode())
                    .append(" (")
                    .append(stage.agentCode())
                    .append("):\n")
                    .append(content);
        }
        return builder.toString();
    }

    /**
     * Keeps prior-stage context useful without letting one verbose agent consume the whole next prompt.
     */
    private static String artifactPromptExcerpt(String value) {
        String normalized = safeArtifactText(value).replaceAll("\\s+", " ").strip();
        if (normalized.length() <= MAX_ARTIFACT_CHARS_PER_STAGE) {
            return normalized;
        }
        return normalized.substring(0, MAX_ARTIFACT_CHARS_PER_STAGE).strip() + "...";
    }

    /** Keeps request text bounded before it crosses an agent boundary or becomes part of a traceable prompt summary. */
    private static String compactPromptText(String value, int maxChars) {
        String normalized = value == null ? "" : value.replaceAll("\\s+", " ").strip();
        if (normalized.length() <= maxChars) {
            return normalized;
        }
        int contentLimit = Math.max(0, maxChars - 3);
        return normalized.substring(0, contentLimit).strip() + "...";
    }

    /**
     * Carries only bounded, de-duplicated evidence identifiers across agent boundaries. Source bodies are fetched by
     * the retrieval tools under backend authorization; they must never be smuggled through this request field.
     */
    private static List<String> compactEvidenceRefs(List<String> evidenceRefs) {
        if (evidenceRefs == null || evidenceRefs.isEmpty()) {
            return List.of();
        }
        return evidenceRefs.stream()
                .map(value -> compactPromptText(value, MAX_EVIDENCE_REF_CHARS))
                .filter(value -> !value.isBlank())
                .distinct()
                .limit(MAX_EVIDENCE_REFS)
                .toList();
    }

    /**
     * Returns a user-safe progress message for a finished stage.
     */
    private static String stageCompletionMessage(String groupCode) {
        return switch (groupCode) {
            case "evidence" -> "Evidence curation completed.";
            case "template" -> "Template selection completed.";
            case "outline" -> "Shared outline completed.";
            case "versions" -> "Teacher, student, and 16:10 versions completed.";
            case "reviews" -> "Source, student-safety, and layout reviews completed.";
            case "merge" -> "Final merge completed.";
            default -> "Writing stage group completed.";
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
        List<MultiAgentWritingArtifact.StructuredSection> sections =
                structuredSections(normalized.stages(), stageArtifacts);
        return new MultiAgentWritingArtifact(
                normalized.workflowId(),
                normalized.tenantId(),
                normalized.subjectType(),
                normalized.subjectId(),
                normalized.status(),
                normalized.totalUsage(),
                stageArtifacts,
                sections,
                mergedArtifactMarkdown(sections));
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
     * Converts raw stage output into merge-ready sections while preserving a fallback for free-form model text.
     */
    private static List<MultiAgentWritingArtifact.StructuredSection> structuredSections(
            List<MultiAgentWritingResponse.StageResult> rawStages,
            List<MultiAgentWritingArtifact.StageArtifact> safeStages) {
        List<MultiAgentWritingArtifact.StructuredSection> sections = new ArrayList<>();
        for (int index = 0; index < safeStages.size(); index++) {
            MultiAgentWritingArtifact.StageArtifact safeStage = safeStages.get(index);
            String rawContent = index < rawStages.size() ? rawStages.get(index).generatedContent() : safeStage.generatedContent();
            Optional<JsonNode> root = jsonObject(rawContent);
            switch (safeStage.stageCode()) {
                case "teacher_writer", "student_writer", "lecture_writer" -> appendDraftSections(sections, safeStage, root);
                case "source_review", "student_safety_review", "layout_review" -> appendReviewSection(sections, safeStage, root);
                case "merge_coordinator" -> appendFormatSection(sections, safeStage, root);
                default -> appendFallbackSection(sections, safeStage, safeStage.stageCode(), stageTitle(safeStage.stageCode()));
            }
        }
        return List.copyOf(sections);
    }

    /**
     * Draft agents may produce multiple independently reviewable document sections.
     */
    private static void appendDraftSections(
            List<MultiAgentWritingArtifact.StructuredSection> sections,
            MultiAgentWritingArtifact.StageArtifact stage,
            Optional<JsonNode> root) {
        int before = sections.size();
        List<String> risks = root.map(MultiAgentWritingService::risks).orElse(List.of());
        List<String> refs = root.map(MultiAgentWritingService::artifactRefs).orElse(List.of());
        root.ifPresent(json -> {
            appendTextFieldSection(
                    sections,
                    json,
                    "teacherExplanation",
                    "teacher-explanation",
                    "Teacher Explanation",
                    stage.stageCode(),
                    risks,
                    refs);
            appendTextFieldSection(
                    sections,
                    json,
                    "studentWorksheet",
                    "student-worksheet",
                    "Student Worksheet",
                    stage.stageCode(),
                    risks,
                    refs);
            appendTextFieldSection(
                    sections,
                    json,
                    "lectureCards",
                    "lecture-cards",
                    "Lecture Cards",
                    stage.stageCode(),
                    risks,
                    refs);
            appendTextFieldSection(
                    sections,
                    json,
                    "exercises",
                    "exercises",
                    "Exercises",
                    stage.stageCode(),
                    risks,
                    refs);
            appendTextFieldSection(
                    sections,
                    json,
                    "markdown",
                    "draft",
                    "Draft",
                    stage.stageCode(),
                    risks,
                    refs);
            appendTextFieldSection(
                    sections,
                    json,
                    "content",
                    "draft",
                    "Draft",
                    stage.stageCode(),
                    risks,
                    refs);
            appendTextFieldSection(
                    sections,
                    json,
                    "body",
                    "draft",
                    "Draft",
                    stage.stageCode(),
                    risks,
                    refs);
            appendTextFieldSection(
                    sections,
                    json,
                    "result",
                    "draft",
                    "Draft",
                    stage.stageCode(),
                    risks,
                    refs);
        });
        if (sections.size() == before) {
            // Luna returns rich JSON objects with domain-specific field names (for example worked_examples), while
            // older providers use the smaller teacherExplanation/studentWorksheet contract. Preserve the real
            // classroom content under the audience-specific section code instead of exporting raw Agent JSON.
            String structuredMarkdown = root.map(MultiAgentWritingService::structuredJsonMarkdown).orElse("");
            if (!structuredMarkdown.isBlank()) {
                String sectionCode = switch (stage.stageCode()) {
                    case "teacher_writer" -> "teacher-explanation";
                    case "student_writer" -> "student-worksheet";
                    case "lecture_writer" -> "lecture-cards";
                    default -> "draft";
                };
                String title = switch (stage.stageCode()) {
                    case "teacher_writer" -> "Teacher Explanation";
                    case "student_writer" -> "Student Worksheet";
                    case "lecture_writer" -> "Lecture Cards";
                    default -> "Draft";
                };
                sections.add(new MultiAgentWritingArtifact.StructuredSection(
                        sectionCode, title, stage.stageCode(), structuredMarkdown, List.of(), risks, refs));
            } else {
                appendFallbackSection(sections, stage, "draft", "Draft");
            }
        }
    }

    /**
     * Converts a provider-specific JSON answer to printable Markdown without exposing retrieval identifiers or
     * operational fields. This is a compatibility bridge, not a second prompt: future providers can add teaching
     * fields without forcing a teacher to download an unreadable JSON blob.
     */
    private static String structuredJsonMarkdown(JsonNode root) {
        StringBuilder markdown = new StringBuilder();
        appendJsonMarkdown(markdown, root, 1, "");
        return markdown.toString().strip();
    }

    private static void appendJsonMarkdown(StringBuilder markdown, JsonNode value, int depth, String label) {
        if (value == null || value.isNull() || depth > 5) {
            return;
        }
        if (value.isTextual() || value.isNumber() || value.isBoolean()) {
            String text = value.asText().strip();
            if (text.isBlank()) return;
            if (!label.isBlank()) {
                markdown.append("#".repeat(Math.min(4, Math.max(2, depth)))).append(' ')
                        .append(readableJsonLabel(label)).append("\n\n");
            }
            markdown.append(text).append("\n\n");
            return;
        }
        if (value.isArray()) {
            for (JsonNode item : value) {
                if (item.isValueNode()) {
                    String text = item.asText().strip();
                    if (!text.isBlank()) markdown.append("- ").append(text).append('\n');
                } else {
                    appendJsonMarkdown(markdown, item, depth + 1, label);
                }
            }
            markdown.append('\n');
            return;
        }
        if (value.isObject()) {
            value.fields().forEachRemaining(entry -> {
                if (!isPrintableJsonField(entry.getKey())) return;
                JsonNode child = entry.getValue();
                if (child.isValueNode()) {
                    String text = child.asText().strip();
                    if (!text.isBlank()) {
                        if ("title".equalsIgnoreCase(entry.getKey())) {
                            // Provider JSON often carries the actual AI title as a field value. Promote it directly
                            // to a Markdown heading so neither "title" nor a duplicate title reaches the PDF.
                            markdown.append("#".repeat(Math.min(4, Math.max(1, depth)))).append(' ')
                                    .append(text).append("\n\n");
                        } else {
                            markdown.append("#".repeat(Math.min(4, Math.max(1, depth)))).append(' ')
                                    .append(readableJsonLabel(entry.getKey())).append("\n\n")
                                    .append(text).append("\n\n");
                        }
                    }
                } else {
                    markdown.append("#".repeat(Math.min(4, Math.max(1, depth)))).append(' ')
                            .append(readableJsonLabel(entry.getKey())).append("\n\n");
                    appendJsonMarkdown(markdown, child, depth + 1, "");
                }
            });
        }
    }

    /** Removes operational/source fields; citations stay in the protected trace and evidence panel. */
    private static boolean isPrintableJsonField(String field) {
        String normalized = field == null ? "" : field.toLowerCase(java.util.Locale.ROOT);
        return !normalized.contains("evidence") && !normalized.contains("citation") && !normalized.contains("source")
                && !normalized.contains("trace") && !normalized.contains("token") && !normalized.contains("model");
    }

    private static String readableJsonLabel(String field) {
        if (field == null) return "";
        return switch (field) {
            case "name" -> "名称";
            case "table" -> "方法选择表";
            case "coordinate_form", "coordinate form" -> "坐标形式";
            case "audience" -> "适用对象";
            case "type" -> "讲义类型";
            case "fields" -> "填写信息";
            case "sections" -> "学习内容";
            case "teaching_objectives" -> "教学目标";
            case "knowledge_framework" -> "知识框架";
            case "notation" -> "符号约定";
            case "formulas" -> "核心公式";
            case "reasoning" -> "推导理由";
            case "application" -> "适用条件";
            case "method_decision_table" -> "方法选择";
            case "known_conditions" -> "已知条件";
            case "preferred_method" -> "优先方法";
            case "worked_examples" -> "例题讲解";
            case "problem" -> "题目";
            case "solution" -> "解题过程";
            case "answer" -> "答案";
            case "review_notes" -> "检验与提醒";
            case "practice" -> "课堂练习";
            case "common_errors" -> "易错提醒";
            case "class_summary" -> "课堂小结";
            case "content" -> "内容";
            case "items" -> "要点";
            case "prompts" -> "思考问题";
            case "problems" -> "练习题";
            case "checklist" -> "自查";
            case "cards" -> "课堂卡片";
            case "card" -> "卡片";
            default -> field.replace('_', ' ').strip();
        };
    }

    /**
     * Review output is kept as its own section so a future merge agent can apply it deterministically.
     */
    private static void appendReviewSection(
            List<MultiAgentWritingArtifact.StructuredSection> sections,
            MultiAgentWritingArtifact.StageArtifact stage,
            Optional<JsonNode> root) {
        List<String> notes = root
                .map(json -> textList(json, "review", "reviewNotes", "patches", "content", "body", "result"))
                .filter(list -> !list.isEmpty())
                .orElseGet(() -> stage.generatedContent().isBlank() ? List.of() : List.of(stage.generatedContent()));
        if (notes.isEmpty()) {
            return;
        }
        sections.add(new MultiAgentWritingArtifact.StructuredSection(
                "quality-review",
                "Quality Review",
                stage.stageCode(),
                String.join("\n\n", notes),
                notes,
                root.map(MultiAgentWritingService::risks).orElse(List.of()),
                root.map(MultiAgentWritingService::artifactRefs).orElse(List.of())));
    }

    /**
     * Format output is treated as the final classroom-facing handout section.
     */
    private static void appendFormatSection(
            List<MultiAgentWritingArtifact.StructuredSection> sections,
            MultiAgentWritingArtifact.StageArtifact stage,
            Optional<JsonNode> root) {
        String content = root
                .flatMap(json -> firstText(json, "markdown", "content", "body", "result"))
                .orElse(stage.generatedContent());
        if (content.isBlank()) {
            return;
        }
        sections.add(new MultiAgentWritingArtifact.StructuredSection(
                "final-handout",
                "Final Handout",
                stage.stageCode(),
                content,
                List.of(),
                root.map(MultiAgentWritingService::risks).orElse(List.of()),
                root.map(MultiAgentWritingService::artifactRefs).orElse(List.of())));
    }

    /**
     * Adds a structured section from one JSON text field.
     */
    private static void appendTextFieldSection(
            List<MultiAgentWritingArtifact.StructuredSection> sections,
            JsonNode root,
            String fieldName,
            String sectionCode,
            String title,
            String sourceStageCode,
            List<String> risks,
            List<String> artifactRefs) {
        firstText(root, fieldName).ifPresent(content -> sections.add(new MultiAgentWritingArtifact.StructuredSection(
                sectionCode,
                title,
                sourceStageCode,
                content,
                List.of(),
                risks,
                artifactRefs)));
    }

    /**
     * Keeps non-JSON or partial stage output visible instead of dropping content.
     */
    private static void appendFallbackSection(
            List<MultiAgentWritingArtifact.StructuredSection> sections,
            MultiAgentWritingArtifact.StageArtifact stage,
            String sectionCode,
            String title) {
        if (stage.generatedContent().isBlank()) {
            return;
        }
        sections.add(new MultiAgentWritingArtifact.StructuredSection(
                sectionCode,
                title,
                stage.stageCode(),
                stage.generatedContent(),
                List.of(),
                List.of(),
                List.of()));
    }

    /**
     * Parses a JSON object without surfacing raw model parsing errors to users.
     */
    private static Optional<JsonNode> jsonObject(String value) {
        if (value == null || value.isBlank() || !value.stripLeading().startsWith("{")) {
            return Optional.empty();
        }
        try {
            JsonNode root = ARTIFACT_OBJECT_MAPPER.readTree(value);
            return root.isObject() ? Optional.of(root) : Optional.empty();
        } catch (Exception exception) {
            return Optional.empty();
        }
    }

    /**
     * Returns the first non-blank string field from a JSON object.
     */
    private static Optional<String> firstText(JsonNode root, String... fieldNames) {
        for (String fieldName : fieldNames) {
            JsonNode node = root.get(fieldName);
            if (node != null && node.isTextual() && !node.asText().isBlank()) {
                return Optional.of(node.asText().strip());
            }
        }
        return Optional.empty();
    }

    /**
     * Reads text or text-array fields from a JSON object.
     */
    private static List<String> textList(JsonNode root, String... fieldNames) {
        List<String> values = new ArrayList<>();
        for (String fieldName : fieldNames) {
            JsonNode node = root.get(fieldName);
            if (node == null) {
                continue;
            }
            if (node.isTextual() && !node.asText().isBlank()) {
                values.add(node.asText().strip());
            } else if (node.isArray()) {
                node.forEach(item -> {
                    if (item.isTextual() && !item.asText().isBlank()) {
                        values.add(item.asText().strip());
                    }
                });
            }
        }
        return List.copyOf(values);
    }

    /**
     * Reads evidence or artifact references from common agent JSON fields.
     */
    private static List<String> artifactRefs(JsonNode root) {
        return textList(root, "artifactRefs", "evidenceRefs", "sourceRefs");
    }

    /**
     * Reads known risks from structured agent JSON fields.
     */
    private static List<String> risks(JsonNode root) {
        return textList(root, "risks", "riskNotes");
    }

    /**
     * Merges structured sections into one Markdown document.
     */
    private static String mergedArtifactMarkdown(List<MultiAgentWritingArtifact.StructuredSection> sections) {
        StringBuilder builder = new StringBuilder();
        for (MultiAgentWritingArtifact.StructuredSection section : sections) {
            if (section.content().isBlank()) {
                continue;
            }
            if (!builder.isEmpty()) {
                builder.append("\n\n");
            }
            if (!section.content().stripLeading().startsWith("#")) {
                builder.append("## ").append(section.title()).append("\n\n");
            }
            builder.append(section.content());
        }
        return builder.toString();
    }

    /**
     * Returns a readable stage title for merged Markdown artifacts.
     */
    private static String stageTitle(String stageCode) {
        return switch (stageCode) {
            case "resource_curation" -> "资料汇总";
            case "template_selection" -> "模板选择";
            case "outline_planning" -> "共享大纲";
            case "teacher_writer" -> "教师版";
            case "student_writer" -> "学生版";
            case "lecture_writer" -> "16:10 讲解版";
            case "source_review" -> "来源审查";
            case "student_safety_review" -> "学生版安全审查";
            case "layout_review" -> "版式审查";
            case "merge_coordinator" -> "合并结果";
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
            List<String> requestedTools,
            List<String> allowedArtifactStages,
            boolean requiresStructuredOutput,
            String roleBrief) {
    }

    /** Defines a durable barrier whose stages may run concurrently only when they have identical dependencies. */
    private record WritingStageGroup(String groupCode, List<WritingStageSpec> stages) {
    }

    /**
     * Signals a fan-out failure while carrying the branches that already produced a valid result. The outer workflow
     * persists these branches in the FAILED snapshot, allowing a later resume to execute only missing roles.
     */
    private static final class ParallelStageFailure extends IllegalStateException {

        private final List<MultiAgentWritingResponse.StageResult> completedStages;

        private ParallelStageFailure(
                RuntimeException cause,
                List<MultiAgentWritingResponse.StageResult> completedStages) {
            super("Parallel writing stage failed: "
                    + (cause.getMessage() == null || cause.getMessage().isBlank()
                            ? cause.getClass().getSimpleName()
                            : cause.getMessage()),
                    cause);
            this.completedStages = completedStages == null ? List.of() : List.copyOf(completedStages);
        }

        private List<MultiAgentWritingResponse.StageResult> completedStages() {
            return completedStages;
        }
    }

    /** Builds one immutable role definition while retaining a short declaration for the static topology above. */
    private static WritingStageSpec stage(
            String stageCode,
            String agentCode,
            String taskType,
            List<String> requestedTools,
            List<String> allowedArtifactStages,
            boolean requiresStructuredOutput,
            String roleBrief) {
        return new WritingStageSpec(
                stageCode,
                agentCode,
                taskType,
                List.copyOf(requestedTools),
                List.copyOf(allowedArtifactStages),
                requiresStructuredOutput,
                roleBrief);
    }
}
