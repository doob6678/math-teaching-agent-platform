package com.doob.mathagent.teaching.service;

import com.doob.mathagent.agent.service.AgentTraceRecord;
import com.doob.mathagent.agent.service.AgentTraceStore;
import com.doob.mathagent.agent.vo.AgentRunExecuteResponse;
import com.doob.mathagent.infrastructure.security.RequestSubject;
import com.doob.mathagent.knowledge.service.KnowledgeQuestionBankService;
import com.doob.mathagent.knowledge.service.QuestionBankSearchText;
import com.doob.mathagent.knowledge.vo.QuestionBankItemResponse;
import com.doob.mathagent.memory.dto.StudentMemoryRequest;
import com.doob.mathagent.memory.service.StudentMemoryCommand;
import com.doob.mathagent.memory.service.StudentMemoryReuseService;
import com.doob.mathagent.memory.vo.StudentMemoryResponse;
import com.doob.mathagent.retrieval.RetrievalRequestContext;
import com.doob.mathagent.retrieval.CanonicalMathPaperRetrievalService;
import com.doob.mathagent.retrieval.TextbookRetrievalService;
import com.doob.mathagent.retrieval.TextbookSearchHit;
import com.doob.mathagent.retrieval.TextbookSearchRequest;
import com.doob.mathagent.retrieval.TextbookSearchResponse;
import com.doob.mathagent.teaching.TeachingDraftSectionCollector;
import com.doob.mathagent.teaching.TeachingDraftMergeResult;
import com.doob.mathagent.teaching.TeachingDraftMerger;
import com.doob.mathagent.teaching.TeachingDraftReview;
import com.doob.mathagent.teaching.TeachingDraftReviewCollector;
import com.doob.mathagent.teaching.TeachingDraftSections;
import com.doob.mathagent.teaching.TeachingEvidence;
import com.doob.mathagent.teaching.TeachingHandoutVersionCollector;
import com.doob.mathagent.teaching.TeachingHandoutVersions;
import com.doob.mathagent.teaching.TeachingKnowledgePointPack;
import com.doob.mathagent.teaching.TeachingReactStep;
import com.doob.mathagent.teaching.TeachingRequestContext;
import com.doob.mathagent.teaching.TeachingReviewPolicy;
import com.doob.mathagent.teaching.TeachingTaskStatus;
import com.doob.mathagent.teaching.TeachingWorkflowEvent;
import com.doob.mathagent.teaching.TeachingWorkflowNode;
import com.doob.mathagent.teaching.dto.TeachingTaskRequest;
import com.doob.mathagent.teaching.vo.TeachingTaskResponse;
import com.doob.mathagent.teacher.service.TeacherResourceBlockSearchService;
import com.doob.mathagent.teacher.search.TeacherResourceBlockSearchResponse;
import com.doob.mathagent.teacher.search.TeacherResourceSearchFilter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Files;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;
import com.doob.mathagent.teaching.service.TeachingWorkflowService.ProgressPhase;
import com.doob.mathagent.teaching.service.TeachingWorkflowService.ModelExplanationUnit;
import com.doob.mathagent.teaching.service.TeachingWorkflowService.ModelExplanationHeader;
import com.doob.mathagent.teaching.service.TeachingWorkflowService.StageTimer;
import com.doob.mathagent.teaching.service.TeachingWorkflowService.LabelPosition;
import com.doob.mathagent.teaching.service.TeachingWorkflowService.LabeledDraftBlock;
import com.doob.mathagent.teaching.service.TeachingWorkflowService.EvidencePack;
import com.doob.mathagent.teaching.service.TeachingWorkflowService.TimedEvidence;
import static com.doob.mathagent.teaching.service.TeachingWorkflowService.*;

/**
 * Stateful execution support for the public teaching workflow facade.
 *
 * <p>The facade owns lifecycle/idempotency endpoints; this class owns dependency-backed execution, retrieval,
 * and progress persistence. Keeping those responsibilities separate prevents another god class.</p>
 */
class TeachingWorkflowExecutionSupport {
    protected Path processedBooksRoot;
    protected TextbookRetrievalService retrievalService;
    protected TeachingTaskStore taskStore;
    protected StudentMemoryReuseService memoryReuseService;
    /** Compatibility placeholder retained only for source-compatible focused constructors; AI execution is Python-owned. */
    protected Object legacyTeachingDraftService;
    /** The handout task path owns exactly one Python LangGraph invocation for all three audience documents. */
    protected TeachingHandoutAiClient pythonTeachingHandoutClient;
    /** The handout graph switch is distinct because teaching-drafts remains available only to non-handout features. */
    @Value("${math-agent.python-handout.enabled:true}")
    protected boolean pythonTeachingHandoutEnabledFlag;
    protected AgentTraceStore agentTraceStore;
    protected TeachingHandoutTemplateService handoutTemplateService;
    protected KnowledgeQuestionBankService questionBankService;
    protected TeacherResourceBlockSearchService teacherResourceBlockSearchService;
    protected TeacherResourceVisualEvidenceService teacherResourceVisualEvidenceService;
    /** 规范真题经统一检索层和清单授权适配器接入，不与教师私有资料索引混用。 */
    protected CanonicalMathPaperRetrievalService canonicalMathPaperRetrievalService;
    protected TaskExecutor taskExecutor;
    /** Separate bounded pool for nested retrieval/agent fan-out so an outer workflow worker cannot self-starve. */
    protected TaskExecutor evidenceTaskExecutor;
    protected boolean returnCompletedWhenExecutorIsSynchronous;
    /** Persists auditable parent/child node records independently from the UI task snapshot. */
    protected TeachingWorkflowTraceRecorder traceRecorder;
    /** A task-level barrier prevents nested retrieval calls from retaining a worker lease indefinitely. */
    @Value("${math-agent.teaching.evidence-timeout-ms:240000}")
    protected long evidenceTimeoutMs = 240000L;
    /** Wires the sole Python graph used by durable teaching-handout tasks. */
    @Autowired
    void configurePythonTeachingHandoutClient(PythonTeachingHandoutClient client) {
        this.pythonTeachingHandoutClient = client;
    }

    /** 仅供组合测试注入 Python 图的确定性替身，不提供 Java 模型降级。 */
    public void setTeachingHandoutAiClientForTesting(TeachingHandoutAiClient client) {
        this.pythonTeachingHandoutClient = client;
        this.pythonTeachingHandoutEnabledFlag = true;
        this.returnCompletedWhenExecutorIsSynchronous = true;
    }


    /** 仅供组合测试注入独立证据执行器，验证嵌套检索不会受同步任务执行器串行化。 */
    public void setEvidenceTaskExecutorForTesting(TaskExecutor executor) {
        this.evidenceTaskExecutor = executor;
    }

    /** Renders three independent publishable versions from an already approved, immutable common draft. */
    protected TeachingTaskResponse renderApprovedHandoutVersions(
            TeachingTaskResponse task, TeachingRequestContext reviewer) {
        TeachingTaskRequest request = new TeachingTaskRequest(
                task.clientRequestId(), task.questionText(), task.learningGoal(), evidenceLimitForResume(task),
                task.selectedTemplate() == null ? null : task.selectedTemplate().templateCode(), task.watermarkText(),
                task.headerLeft(), task.headerRight(), task.footerLeft(), task.footerRight(), null, null, null).normalize();
        TeachingHandoutTemplateProfile template = handoutTemplateService.resolveFor(request);
        StudentMemoryResponse memory = task.memoryReuse() == null
                ? new StudentMemoryResponse(false, null, "private", "", 0D,
                        "review rendering has no memory snapshot", List.of())
                : fromMemoryReuse(task.memoryReuse());
        List<TeachingEvidence> questionEvidence = task.evidence().stream()
                .filter(item -> "QUESTION_BANK".equals(item.sourceScope())).toList();
        List<TeachingEvidence> textbookEvidence = task.evidence().stream()
                .filter(item -> "PUBLIC_TEXTBOOK".equals(item.sourceScope())).toList();
        List<TeachingEvidence> teacherEvidence = task.evidence().stream()
                .filter(item -> "TEACHER_RESOURCE".equals(item.sourceScope())).toList();
        List<TeachingKnowledgePointPack> retrievedPacks = buildKnowledgePointPacks(
                request, textbookEvidence, teacherEvidence, questionEvidence);
        List<TeachingKnowledgePointPack> knowledgePacks = retrievedPacks.isEmpty()
                ? fallbackKnowledgePointPacks(request, task.evidence())
                : retrievedPacks;
        TeachingHandoutVersions versions = renderHandoutVersions(
                request, task.evidence(), knowledgePacks, memory, template, task.aiDraft(), task.mergeResult().mergedSections());
        return task.withHandoutVersion("teacher", versions.teacherHandoutLatex())
                .withHandoutVersion("student", versions.studentHandoutLatex())
                .withHandoutVersion("lecture", versions.lectureHandoutLatex())
                .withReviewStatus(TeachingTaskStatus.COMPLETED, null);
    }


    /**
     * 同步执行 DAG 的兼容入口（无 taskId/owner/idempotencyKey，用于测试或非异步场景）。
     */
    protected TeachingTaskResponse execute(TeachingTaskRequest request, TeachingRequestContext context) {
        return execute(request, context, UUID.randomUUID().toString(), null, null, null);
    }


    /**
     * 执行固定 DAG：学习目标识别、资源复用、公开教材检索、ReAct、AI 草稿、LaTeX 讲义、交互建议。
     * 异步路径下先持久化 RUNNING 状态，完成后更新为 COMPLETED，异常时更新为 FAILED。
     *
     * @param taskId 异步任务的 taskId，来自 submit() 中预生成的 UUID
     * @param ownerKey 用于 RUNNING/COMPLETED 状态的持久化
     * @param idempotencyKey 幂等 key，异步完成后更新已有记录
     */
    protected TeachingTaskResponse execute(TeachingTaskRequest request, TeachingRequestContext context, String taskId, String ownerKey, String idempotencyKey) {
        return execute(request, context, taskId, ownerKey, idempotencyKey, null);
    }


    /** Executes a task while reusing durable evidence and AI draft artifacts already completed before a failure. */
    protected TeachingTaskResponse execute(
            TeachingTaskRequest request,
            TeachingRequestContext context,
            String taskId,
            String ownerKey,
            String idempotencyKey,
            TeachingTaskResponse checkpoint) {
        return execute(request, context, taskId, ownerKey, idempotencyKey, checkpoint, null);
    }

    /**
     * 持有顶层租约的异步执行必须把令牌带入每个可见快照，防止被接管的旧 Worker 覆盖新结果。
     */
    protected TeachingTaskResponse execute(
            TeachingTaskRequest request,
            TeachingRequestContext context,
            String taskId,
            String ownerKey,
            String idempotencyKey,
            TeachingTaskResponse checkpoint,
            com.doob.mathagent.teaching.mq.LectureTaskLease lease) {
        StageTimer timer = new StageTimer(checkpoint == null ? List.of() : checkpoint.stageTimings());
        traceRecorder.running(taskId, context, "WORKFLOW_ORCHESTRATOR", "TeachingOrchestrator", List.of(), 0L,
                "主智能体已接收任务并开始执行固定教学 DAG。");
        TeachingHandoutTemplateProfile template = handoutTemplateService.resolveFor(request);
        StudentMemoryResponse memoryResponse = checkpoint != null && checkpoint.memoryReuse() != null
                ? fromMemoryReuse(checkpoint.memoryReuse())
                : memoryReuseService.reuse(memoryRequest(request, context));
        timer.mark("memory_reuse");
        List<TeachingEvidence> evidence;
        List<TeachingEvidence> textbookEvidence;
        List<TeachingEvidence> questionEvidence;
        List<TeachingEvidence> teacherResourceEvidence;
        EvidencePack evidencePack = null;
        saveRunningProgress(
                request, context, taskId, ownerKey, idempotencyKey, lease, template, memoryResponse,
                List.of(), List.of(), List.of(), List.of(), null, timer,
                ProgressPhase.EVIDENCE_COLLECTING);
        if (checkpoint != null && evidenceCheckpointComplete(checkpoint) && !requiresFreshEvidence(checkpoint)) {
            textbookEvidence = checkpoint.evidence().stream()
                    .filter(item -> "PUBLIC_TEXTBOOK".equals(item.sourceScope()))
                    .filter(item -> !isBenchmarkEvidence(item)).toList();
            questionEvidence = checkpoint.evidence().stream()
                    .filter(item -> "QUESTION_BANK".equals(item.sourceScope()))
                    .filter(item -> !isBenchmarkEvidence(item)).toList();
            teacherResourceEvidence = checkpoint.evidence().stream()
                    .filter(item -> "TEACHER_RESOURCE".equals(item.sourceScope()))
                    .filter(item -> !isBenchmarkEvidence(item)).toList();
            evidence = checkpoint.evidence().stream()
                    .filter(item -> !isBenchmarkEvidence(item))
                    .toList();
            timer.mark("evidence_resume");
        } else {
            final String progressTaskId = taskId;
            evidencePack = checkpoint == null
                    ? retrieveEvidencePack(request, context, progressTaskId, partialPack -> {
                        List<TeachingEvidence> partialTextbook = verifiedEvidence(partialPack.textbookEvidence());
                        List<TeachingEvidence> partialQuestions = verifiedEvidence(partialPack.questionEvidence());
                        List<TeachingEvidence> partialTeacherResources = verifiedEvidence(partialPack.teacherResourceEvidence());
                        saveRunningProgress(
                                request, context, progressTaskId, ownerKey, idempotencyKey, lease, template, memoryResponse,
                                verifiedEvidence(partialPack.mergedEvidence()), partialTextbook, partialQuestions,
                                partialTeacherResources, null, timer, ProgressPhase.EVIDENCE_COLLECTING,
                                partialPack.textbookOutcome(), partialPack.questionOutcome(), partialPack.teacherResourceOutcome());
                    })
                    : retrieveMissingEvidenceBranches(request, context, progressTaskId, checkpoint, partialPack -> {
                        List<TeachingEvidence> partialTextbook = verifiedEvidence(partialPack.textbookEvidence());
                        List<TeachingEvidence> partialQuestions = verifiedEvidence(partialPack.questionEvidence());
                        List<TeachingEvidence> partialTeacherResources = verifiedEvidence(partialPack.teacherResourceEvidence());
                        saveRunningProgress(
                                request, context, progressTaskId, ownerKey, idempotencyKey, lease, template, memoryResponse,
                                verifiedEvidence(partialPack.mergedEvidence()), partialTextbook, partialQuestions,
                                partialTeacherResources, null, timer, ProgressPhase.EVIDENCE_COLLECTING,
                                partialPack.textbookOutcome(), partialPack.questionOutcome(), partialPack.teacherResourceOutcome());
                    });
            timer.record("textbook_retrieval", evidencePack.textbookElapsedMs());
            timer.record("question_bank_retrieval", evidencePack.questionElapsedMs());
            timer.record("teacher_resource_retrieval", evidencePack.teacherResourceElapsedMs());
            textbookEvidence = evidencePack.textbookEvidence();
            questionEvidence = evidencePack.questionEvidence();
            teacherResourceEvidence = evidencePack.teacherResourceEvidence();
            timer.resetCheckpoint();
            evidence = verifiedEvidence(evidencePack.mergedEvidence());
            textbookEvidence = verifiedEvidence(textbookEvidence);
            questionEvidence = verifiedEvidence(questionEvidence);
            teacherResourceEvidence = verifiedEvidence(teacherResourceEvidence);
        }
        evidence = verifiedEvidence(evidence);
        textbookEvidence = verifiedEvidence(textbookEvidence);
        questionEvidence = verifiedEvidence(questionEvidence);
        teacherResourceEvidence = verifiedEvidence(teacherResourceEvidence);
        saveRunningProgress(
                request, context, taskId, ownerKey, idempotencyKey, lease, template, memoryResponse,
                evidence, textbookEvidence, questionEvidence, teacherResourceEvidence, null, timer,
                ProgressPhase.OUTLINE_BUILDING);
        // Python receives the initial authorization snapshot, which may be empty. Its plan writer exclusively owns
        // teacher-resource queries and persists any authorized hits through the broker before returning.
        List<TeachingReactStep> reactTrace = List.of();
        timer.mark("react_trace");
        saveRunningProgress(
                request, context, taskId, ownerKey, idempotencyKey, lease, template, memoryResponse,
                evidence, textbookEvidence, questionEvidence, teacherResourceEvidence, null, timer,
                ProgressPhase.CONTENT_GENERATING);
        // Evidence may have been refreshed on an earlier recovery pass while the structured model draft already
        // names the repaired source number. Reuse that durable draft for a renderer-only repair; otherwise every
        // PDF gate retry would trigger another long real-model call before applying a parser/LaTeX fix.
        // Reuse only a structurally valid draft.  A provider can return a real response that fails the JSON/quality
        // contract; persisting that diagnostic is essential for the recovery UI, but reusing it on resume would turn
        // every retry into the same immediate failure and make a transient relay problem impossible to recover from.
        TeachingTaskResponse.AiDraft aiDraft;
        long draftStarted = System.nanoTime();
        try {
            if (lease != null && !taskStore.ownsLease(lease)) {
                throw new TeachingWorkflowService.LeaseLostException();
            }
            aiDraft = checkpoint != null && checkpoint.aiDraft() != null && checkpoint.aiDraft().structured()
                    ? checkpoint.aiDraft()
                    : draftWithConfiguredAiRuntime(taskId, request, evidence, memoryResponse, template);
            // Python may issue plan-writer-owned teacher searches after this Java retrieval barrier. The broker
            // persists those authorization-checked hits against the same task before returning documentRef; reload
            // them now so the final task snapshot reflects real retrieval rather than the older pre-plan projection.
            teacherResourceEvidence = brokerDiscoveredTeacherEvidence(taskId, teacherResourceEvidence);
            evidence = verifiedEvidence(concatEvidence(textbookEvidence, questionEvidence, teacherResourceEvidence));
            if (evidence.isEmpty()) {
                IllegalStateException failure = new IllegalStateException(
                        "讲义任务缺少可核验来源证据，禁止发布零证据讲义。");
                traceRecorder.failed(taskId, context, "WORKFLOW_ORCHESTRATOR", "TeachingOrchestrator", evidence,
                        timingSum(timer, "textbook_retrieval", "question_bank_retrieval", "teacher_resource_retrieval"),
                        failure);
                throw failure;
            }
            // The high-workload question requirement remains a publication gate, but cannot prevent Python from
            // making its independent teacher-resource retrieval decision first.
            requireQualifiedQuestionEvidence(template, questionEvidence);
            traceRecorder.completed(taskId, context, "EVIDENCE_COLLECTION", "EvidenceCollector", evidence,
                    timingSum(timer, "textbook_retrieval", "question_bank_retrieval", "teacher_resource_retrieval"),
                    "已汇总教材、题库和教师资料的可核验来源。");
            traceRecorder.completed(taskId, context, "AI_DRAFT", "CoursewareAgent", evidence,
                    elapsedMs(draftStarted), "已收到结构化讲义草稿。");
        } catch (Throwable failure) {
            traceRecorder.failed(taskId, context, "AI_DRAFT", "CoursewareAgent", evidence,
                    elapsedMs(draftStarted), failure);
            throw failure;
        }
        // Persist the real provider/result metadata before applying the strict publication gate.  Failed tasks then
        // expose the actual retry/parse state and elapsed work in the workflow record without ever publishing an
        // unstructured response as a handout.
        saveRunningProgress(
                request, context, taskId, ownerKey, idempotencyKey, lease, template, memoryResponse,
                evidence, textbookEvidence, questionEvidence, teacherResourceEvidence, aiDraft, timer,
                ProgressPhase.CONTENT_GENERATING);

        // The continuous real-question master promises per-question reasoning, not a deterministic fallback page.
        // A relay timeout or malformed model response must remain a recoverable FAILED task with its evidence intact;
        // otherwise a generic template can be mistaken for a teacher-reviewed explanation and reach PDF export.
        requireStructuredQuestionReasoning(template, aiDraft);
        timer.mark("ai_draft");
        saveRunningProgress(
                request, context, taskId, ownerKey, idempotencyKey, lease, template, memoryResponse,
                evidence, textbookEvidence, questionEvidence, teacherResourceEvidence, aiDraft, timer,
                ProgressPhase.HANDOUT_RENDERING);
        RetrievalOutcome textbookOutcome = evidencePack == null ? RetrievalOutcome.completed() : evidencePack.textbookOutcome();
        RetrievalOutcome questionOutcome = evidencePack == null ? RetrievalOutcome.completed() : evidencePack.questionOutcome();
        RetrievalOutcome teacherResourceOutcome = evidencePack == null
                ? RetrievalOutcome.completed() : evidencePack.teacherResourceOutcome();
        if (!teacherResourceEvidence.isEmpty()) {
            teacherResourceOutcome = completedOutcome("教师资料", new TimedEvidence(teacherResourceEvidence, 0L));
        }
        List<TeachingWorkflowNode> nodes = buildNodes(
                request,
                evidence,
                questionEvidence,
                teacherResourceEvidence,
                memoryResponse,
                aiDraft,
                template,
                canUseQuestionBank(context),
                canUseTeacherResources(context),
                textbookOutcome, questionOutcome, teacherResourceOutcome);
        TeachingDraftSections draftSections = collectDraftSections(request, evidence, aiDraft);
        TeachingDraftReview draftReview = TeachingDraftReviewCollector.collect(draftSections);
        TeachingDraftMergeResult mergeResult = TeachingDraftMerger.merge(draftSections, draftReview);
        // Publication is a Java-owned state transition. The model may draft/review content, but cannot bypass a
        // tenant's human-review policy or publish an unresolved structural review.
        TeachingReviewPolicy reviewPolicy = TeachingReviewPolicy.fromEnvironment();
        TeachingTaskStatus publicationStatus = reviewPolicy
                .statusAfterQualityGate(passedAutomaticReview(mergeResult));
        TeachingDraftSections renderSections = mergeResult.mergedSections();
        // Retrieval determines the printable lesson spine. AI may enrich explanations, but cannot merge unrelated
        // question-bank items back into a generic section or invent a knowledge-point title.
        List<TeachingKnowledgePointPack> retrievedKnowledgePointPacks = buildKnowledgePointPacks(
                request, textbookEvidence, teacherResourceEvidence, questionEvidence);
        List<TeachingKnowledgePointPack> knowledgePointPacks = retrievedKnowledgePointPacks.isEmpty()
                ? fallbackKnowledgePointPacks(request, evidence)
                : retrievedKnowledgePointPacks;
        traceRecorder.completed(taskId, context, "REACT_SOLVE", "OutlinePlanner", evidence,
                timingFor(timer, "react_trace"), "已按来源证据完成知识点与题目编排。");
        // A pending human review stores only the shared, traceable draft. Rendering exportable variants before a
        // decision would both waste work for rejected drafts and create a path to publish unreviewed material.
        TeachingHandoutVersions handoutVersions;
        long renderStarted = System.nanoTime();
        try {
            handoutVersions = reviewPolicy == TeachingReviewPolicy.AUTO_PUBLISH
                    ? renderHandoutVersions(request, evidence, knowledgePointPacks, memoryResponse, template, aiDraft, renderSections)
                    : new TeachingHandoutVersions("", "", "");
            traceRecorder.completed(taskId, context, "LATEX_HANDOUT", "HandoutRenderer", evidence,
                    elapsedMs(renderStarted), "已完成教师版、学生版和讲解版渲染。");
        } catch (Throwable failure) {
            traceRecorder.failed(taskId, context, "LATEX_HANDOUT", "HandoutRenderer", evidence,
                    elapsedMs(renderStarted), failure);
            throw failure;
        }
        timer.mark("handout_generation");
        List<TeachingWorkflowEvent> workflowEvents = buildWorkflowEvents(
                nodes,
                evidence,
                textbookEvidence,
                questionEvidence,
                teacherResourceEvidence,
                aiDraft,
                template,
                textbookOutcome, questionOutcome, teacherResourceOutcome);
        if (taskId == null) {
            taskId = UUID.randomUUID().toString();
        }
        TeachingTaskResponse response = new TeachingTaskResponse(
                taskId,
                request.clientRequestId(),
                context.tenantId(),
                context.subjectType(),
                context.subjectId(),
                template.summary(),
                publicationStatus,
                request.questionText(),
                request.learningGoal(),
                request.watermarkText(),
                nodes,
                workflowEvents,
                reactTrace,
                evidence,
                handoutVersions.teacherHandoutLatex(),
                handoutVersions.teacherHandoutLatex(),
                handoutVersions.studentHandoutLatex(),
                handoutVersions.lectureHandoutLatex(),
                List.of("继续追问定义 D(x_0)", "生成同类练习题", "把讲义导出为 PDF"),
                toMemoryReuse(memoryResponse),
                timer.timings(),
                aiDraft,
                draftSections,
                draftReview,
                mergeResult,
                null)
                .withPageChrome(request.headerLeft(), request.headerRight(), request.footerLeft(), request.footerRight());
        if (lease == null && ownerKey != null && idempotencyKey != null) {
            taskStore.save(ownerKey, idempotencyKey, response);
        }
        traceRecorder.completed(taskId, context, "WORKFLOW_ORCHESTRATOR", "TeachingOrchestrator", evidence,
                timingSum(timer), "教学 DAG 已完成并生成可审计讲义结果。");
        saveAiDraftTrace(response, context);
        return response;
    }

    /**
     * Runs every new handout through the sole Python graph. Automatic Java fallback is prohibited: switching a
     * failed Python call into a Java provider would create a second billable execution plane and break the run audit.
     */
    protected TeachingTaskResponse.AiDraft draftWithConfiguredAiRuntime(
            String taskId,
            TeachingTaskRequest request,
            List<TeachingEvidence> evidence,
            StudentMemoryResponse memoryResponse,
            TeachingHandoutTemplateProfile template) {
        if (!pythonTeachingHandoutEnabled()) {
            throw new IllegalStateException(
                    "Python handout runtime is unavailable or disabled; Java AI fallback is prohibited for handout tasks");
        }
        return pythonTeachingHandoutClient.execute(taskId, request, evidence);
    }

    /** New handouts fail closed when their sole Python execution plane is unavailable. */
    protected boolean pythonTeachingHandoutEnabled() {
        return pythonTeachingHandoutClient != null && pythonTeachingHandoutEnabledFlag;
    }

    /**
     * Reads only teacher evidence appended by the broker for this task and merges it with the initial branch.
     *
     * <p>The task store remains the authorization ledger; Java never replays the user request as a teacher query.
     * The source/block key avoids double-counting a hit already present in the initial snapshot or returned by two
     * semantically similar Python plan queries.</p>
     */
    private List<TeachingEvidence> brokerDiscoveredTeacherEvidence(
            String taskId, List<TeachingEvidence> initialTeacherEvidence) {
        if (taskId == null || taskId.isBlank()) {
            return initialTeacherEvidence == null ? List.of() : List.copyOf(initialTeacherEvidence);
        }
        List<TeachingEvidence> merged = new ArrayList<>(
                initialTeacherEvidence == null ? List.of() : initialTeacherEvidence);
        Set<String> known = merged.stream()
                .map(item -> item.sourceScope() + "|" + item.sourceDocumentId() + "|" + item.chunkId())
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        taskStore.findByTaskId(taskId).ifPresent(snapshot -> {
            for (TeachingEvidence item : snapshot.evidence() == null ? List.<TeachingEvidence>of() : snapshot.evidence()) {
                if (item == null || !"TEACHER_RESOURCE".equals(item.sourceScope())) {
                    continue;
                }
                String key = item.sourceScope() + "|" + item.sourceDocumentId() + "|" + item.chunkId();
                if (known.add(key)) {
                    merged.add(item);
                }
            }
        });
        return verifiedEvidence(merged);
    }


    /**
     * Persists the CoursewareAgent trace for real AI draft runs so WorkBuddy/MCP and the frontend can recover it.
     */
    protected void saveAiDraftTrace(TeachingTaskResponse response, TeachingRequestContext context) {
        TeachingTaskResponse.AiDraft aiDraft = response.aiDraft();
        if (aiDraft == null || !aiDraft.enabled()
                || aiDraft.providerName() == null || aiDraft.providerName().isBlank()

                || aiDraft.modelCode() == null || aiDraft.modelCode().isBlank()) {
            return;
        }
        agentTraceStore.save(new AgentTraceRecord(
                UUID.randomUUID().toString(),
                response.taskId(),
                Instant.now(),
                context.tenantId(),
                context.subjectType(),
                context.subjectId(),
                "CoursewareAgent",
                aiDraft.providerName(),
                aiDraft.modelCode(),
                "COMPLETED",
                0.0d,
                List.of("tool:courseware:generate", "tool:textbook:search"),
                List.of("data:public_textbook", "data:student_memory"),
                response.evidence().stream().map(TeachingWorkflowService::evidenceRef).toList(),
                response.stageTimings().stream()
                        .map(timing -> new AgentRunExecuteResponse.StageTiming(timing.stage(), timing.elapsedMs()))
                        .toList(),
                new AgentRunExecuteResponse.TokenUsage(
                        aiDraft.promptTokens(),
                        aiDraft.completionTokens(),
                        aiDraft.totalTokens()),
                aiDraftTraceMessage(aiDraft),
                aiDraft.recoveryEvents().stream()
                        .map(event -> new AgentTraceRecord.DiagnosticEvent(
                                event.eventType(),
                                event.providerName(),
                                event.modelCode(),
                                event.attemptNo(),
                                event.retryable(),
                                event.message()))
                        .toList()));
    }


    /**
     * 把教材检索命中转换为教学证据，明确标注 PUBLIC_TEXTBOOK 作用域。
     * sourceDocumentId 必须填充 docId，用于 handout-document-read 工具的授权校验。
     */
    protected TeachingEvidence toEvidence(TextbookSearchHit hit) {
        return new TeachingEvidence(
                "PUBLIC_TEXTBOOK",
                hit.bookName() + " / " + hit.sectionTitle(),
                hit.chunkId(),
                hit.pageNo(),
                hit.textSnippet(),
                resolvedTextbookImagePath(hit),
                "",
                hit.docId() == null ? "" : hit.docId(),  // 修复：必须填充 sourceDocumentId 用于文档读取授权
                "public_textbook",
                "",
                "textbook://" + hit.docId() + "/page/" + hit.pageNo() + "#chunk=" + hit.chunkId(),
                hit.imageRelPaths() == null ? List.of() : hit.imageRelPaths());
    }


    /** Resolves only a page image physically inside the configured textbook corpus; remote document URLs are rejected. */
    protected String resolvedTextbookImagePath(TextbookSearchHit hit) {
        if (hit == null || hit.docId() == null || hit.docId().isBlank()) {
            return "";
        }
        Path corpusRoot = processedBooksRoot.toAbsolutePath().normalize();
        Path bookRoot = corpusRoot.resolve(hit.docId().strip()).normalize();
        if (!bookRoot.startsWith(corpusRoot)) {
            return "";
        }
        List<String> candidates = new ArrayList<>();
        if (hit.imageRelPaths() != null) {
            candidates.addAll(hit.imageRelPaths());
        }
        candidates.add(hit.sourcePageImage());
        for (String relativePath : candidates) {
            String resolved = resolveAuthorizedTextbookImage(corpusRoot, bookRoot, relativePath);
            if (!resolved.isBlank()) {
                return resolved;
            }
        }
        return "";
    }


    /**
     * Recovers only branches that were not settled in the failed snapshot. Completed branches stay frozen, including
     * a legitimate zero-hit textbook result, so a manual resume cannot repeat the entire source fan-out.
     */
    protected EvidencePack retrieveMissingEvidenceBranches(
            TeachingTaskRequest request,
            TeachingRequestContext context,
            String taskId,
            TeachingTaskResponse checkpoint,
            java.util.function.Consumer<EvidencePack> onBranchSettled) {
        TaskExecutor evidenceExecutor = evidenceTaskExecutor != null ? evidenceTaskExecutor : taskExecutor;
        if (evidenceExecutor == null) {
            throw new IllegalStateException("Teaching evidence executor is not configured");
        }
        List<TeachingEvidence> textbook = checkpoint.evidence().stream()
                .filter(item -> "PUBLIC_TEXTBOOK".equals(item.sourceScope())).toList();
        List<TeachingEvidence> questions = checkpoint.evidence().stream()
                .filter(item -> "QUESTION_BANK".equals(item.sourceScope())).toList();
        List<TeachingEvidence> teacher = checkpoint.evidence().stream()
                .filter(item -> "TEACHER_RESOURCE".equals(item.sourceScope())).toList();
        RetrievalOutcome textbookOutcome = checkpointOutcome(checkpoint, "PUBLIC_TEXTBOOK_RETRIEVAL");
        RetrievalOutcome questionOutcome = checkpointOutcome(checkpoint, "QUESTION_BANK_RETRIEVAL");
        RetrievalOutcome teacherOutcome = checkpointOutcome(checkpoint, "TEACHER_RESOURCE_RETRIEVAL");
        long textbookElapsedMs = 0L;
        long questionElapsedMs = 0L;
        long teacherElapsedMs = 0L;

        // The first recovered verified source freezes the evidence barrier. Do not spend another source query after it.
        // Recovery must preserve the same contract as a fresh run: Python decides whether private teacher material
        // is needed. Replaying this branch here would reintroduce the unconditional latency regression on resume.
        teacherOutcome = RetrievalOutcome.skipped("教师资料由 Python Writer 按需决定是否检索。");
        if (!"completed".equals(questionOutcome.status()) && questions.isEmpty()) {
            try {
                List<TeachingEvidence> currentTeacher = teacher;
                TimedEvidence recovered = awaitEvidence("question_bank", submitEvidence(() -> {
                    List<String> pointQueries = currentTeacher.isEmpty()
                            ? curriculumPointQueries(request, textbook)
                            : curriculumPointQueries(request, currentTeacher);
                    List<TeachingEvidence> retrieved = retrieveQuestionBankEvidence(request, context, pointQueries);
                    return requiresQualifiedQuestionCompilation(request) ? retrieved : alignEvidenceToTopic(request, retrieved);
                }, evidenceExecutor), evidenceTimeoutMs);
                questions = verifiedEvidence(recovered.evidence());
                questionElapsedMs = recovered.elapsedMs();
                questionOutcome = completedOutcome("题库", recovered);
            } catch (RuntimeException failure) {
                questionOutcome = retrievalFailureOutcome("题库", failure, questionElapsedMs);
            }
        }
        EvidencePack result = new EvidencePack(textbook, questions, teacher, textbookElapsedMs, questionElapsedMs,
                teacherElapsedMs, textbookOutcome, questionOutcome, teacherOutcome);
        onBranchSettled.accept(result);
        return result;
    }

    /** Restores a branch state from the durable UI snapshot without interpreting an empty completed result as failure. */
    private static RetrievalOutcome checkpointOutcome(TeachingTaskResponse checkpoint, String nodeCode) {
        return checkpoint.nodes().stream()
                .filter(node -> nodeCode.equals(node.code()))
                .findFirst()
                .map(node -> "completed".equalsIgnoreCase(node.status()) || "skipped".equalsIgnoreCase(node.status())
                        ? RetrievalOutcome.completed()
                        : RetrievalOutcome.degraded("上次运行未完成，恢复该分支。"))
                .orElseGet(() -> RetrievalOutcome.degraded("上次运行缺少分支快照，恢复该分支。"));
    }

    /**
     * 教学任务的证据 DAG：教材与教师资料互不依赖，先并行召回；题库必须等教师资料定位到具体课程点后再检索，
     * 避免仅凭宽泛学习目标选入无关题目。
     */
    protected EvidencePack retrieveEvidencePack(TeachingTaskRequest request, TeachingRequestContext context) {
        return retrieveEvidencePack(request, context, null);
    }

    /** Runs the retrieval barrier and records each real source branch under the parent task id. */
    protected EvidencePack retrieveEvidencePack(
            TeachingTaskRequest request, TeachingRequestContext context, String taskId) {
        return retrieveEvidencePack(request, context, taskId, ignored -> { });
    }

    /**
     * Settles sources independently so a slow optional branch cannot erase evidence already visible to the task owner.
     * The callback persists only authorized, verified evidence; it never receives a provider response or raw exception.
     */
    protected EvidencePack retrieveEvidencePack(
            TeachingTaskRequest request,
            TeachingRequestContext context,
            String taskId,
            java.util.function.Consumer<EvidencePack> onBranchSettled) {
        TaskExecutor evidenceExecutor = evidenceTaskExecutor != null ? evidenceTaskExecutor : taskExecutor;
        if (evidenceExecutor == null) {
            throw new IllegalStateException("Teaching evidence executor is not configured");
        }
        long textbookStartedNanos = System.nanoTime();
        CompletableFuture<TimedEvidence> textbookFuture = submitEvidence(
                () -> alignEvidenceToTopic(request, retrieveTextbookEvidence(request, context)), evidenceExecutor);
        // Canonical papers are an independently published public corpus. Its collection can legitimately be absent
        // before the real-paper ingestion owner completes a run, which must never erase already authorized teacher
        // resource evidence from this handout.
        CompletableFuture<TimedEvidence> canonicalPaperFuture = submitEvidence(
                () -> alignEvidenceToTopic(request, retrieveCanonicalMathPaperEvidence(request)), evidenceExecutor);
        TimedEvidence textbook = new TimedEvidence(List.of(), 0L);
        TimedEvidence teacherResource = new TimedEvidence(List.of(), 0L);
        TimedEvidence questionBank = new TimedEvidence(List.of(), 0L);
        RetrievalOutcome textbookOutcome;
        // Teacher resources are optional private context. Python's plan writer decides whether they are needed;
        // submitting this branch here would turn an AI decision into an unconditional, latency-heavy search.
        RetrievalOutcome teacherOutcome = RetrievalOutcome.skipped("教师资料由 Python Writer 按需决定是否检索。");
        RetrievalOutcome questionOutcome = RetrievalOutcome.running();

        try {
            textbook = awaitEvidence("textbook", textbookFuture, evidenceTimeoutMs);
            textbookOutcome = completedOutcome("公开教材", textbook);
            traceRecorder.completed(taskId, context, "PUBLIC_TEXTBOOK_RETRIEVAL", "TextbookRetriever",
                    textbook.evidence(), textbook.elapsedMs(), textbookOutcome.detail());
        } catch (RuntimeException failure) {
            textbookFuture.cancel(true);
            textbook = failedEvidence(textbookStartedNanos);
            textbookOutcome = retrievalFailureOutcome("公开教材", failure, textbook.elapsedMs());
            traceRecorder.failed(taskId, context, "PUBLIC_TEXTBOOK_RETRIEVAL", "TextbookRetriever",
                    List.of(), textbook.elapsedMs(), failure);
        }
        onBranchSettled.accept(new EvidencePack(textbook.evidence(), List.of(), List.of(),
                textbook.elapsedMs(), 0L, 0L, textbookOutcome, questionOutcome, teacherOutcome));

        try {
            TimedEvidence canonical = awaitEvidence("canonical_math_paper", canonicalPaperFuture, evidenceTimeoutMs);
            // Canonical papers remain public evidence; they must not be mislabeled as private teacher material.
            textbook = new TimedEvidence(concatEvidence(textbook.evidence(), canonical.evidence()),
                    textbook.elapsedMs() + canonical.elapsedMs());
        } catch (RuntimeException canonicalFailure) {
            canonicalPaperFuture.cancel(true);
            traceRecorder.failed(taskId, context, "CANONICAL_MATH_PAPER_RETRIEVAL", "CanonicalMathPaperRetriever",
                    List.of(), 0L, canonicalFailure);
        }
        traceRecorder.completed(taskId, context, "TEACHER_RESOURCE_RETRIEVAL", "PythonWriterDecision",
                List.of(), 0L, teacherOutcome.detail());
        onBranchSettled.accept(new EvidencePack(textbook.evidence(), List.of(), teacherResource.evidence(),
                textbook.elapsedMs(), 0L, teacherResource.elapsedMs(), textbookOutcome, questionOutcome, teacherOutcome));

        // A timed-out teacher source still leaves a safe curriculum fallback: normalized request topics plus completed
        // textbook evidence. The request guides lookup but never becomes an evidence row or bypasses question access.
        long questionBankStartedNanos = System.nanoTime();
        try {
            List<TeachingEvidence> completedTeacherEvidence = teacherResource.evidence();
            List<TeachingEvidence> completedTextbookEvidence = textbook.evidence();
            CompletableFuture<TimedEvidence> questionBankFuture = submitEvidence(
                    () -> {
                        // Teacher resources are intentionally absent here. Their use is a Python model decision;
                        // question-bank retrieval stays grounded in the already authorized public textbook branch.
                        List<String> pointQueries = curriculumPointQueries(request, completedTextbookEvidence);
                        List<TeachingEvidence> retrievedQuestions = retrieveQuestionBankEvidence(request, context, pointQueries);
                        return requiresQualifiedQuestionCompilation(request)
                                ? retrievedQuestions
                                : alignEvidenceToTopic(request, retrievedQuestions);
                    }, evidenceExecutor);
            questionBank = awaitEvidence("question_bank", questionBankFuture, evidenceTimeoutMs);
            questionOutcome = completedOutcome("题库", questionBank);
            traceRecorder.completed(taskId, context, "QUESTION_BANK_RETRIEVAL", "QuestionBankRetriever",
                    questionBank.evidence(), questionBank.elapsedMs(), questionOutcome.detail());
        } catch (RuntimeException failure) {
            questionBank = failedEvidence(questionBankStartedNanos);
            questionOutcome = retrievalFailureOutcome("题库", failure, questionBank.elapsedMs());
            traceRecorder.failed(taskId, context, "QUESTION_BANK_RETRIEVAL", "QuestionBankRetriever",
                    List.of(), questionBank.elapsedMs(), failure);
        }
        EvidencePack result = new EvidencePack(textbook.evidence(), questionBank.evidence(), teacherResource.evidence(),
                textbook.elapsedMs(), questionBank.elapsedMs(), teacherResource.elapsedMs(),
                textbookOutcome, questionOutcome, teacherOutcome);
        onBranchSettled.accept(result);
        return result;
    }

    /** 
     * Explains an evidence-only failure with settled source states instead of hiding useful branch diagnostics.
     * 
     * 修改说明（2026-08-18）：
     * - 区分三种状态：failed（检索失败）、degraded（部分失败/超时）、completed（成功但无匹配）
     * - degraded 表示检索过程有异常（超时、依赖不可用等），但不阻止其他来源的证据使用
     * - completed 但无证据表示检索成功执行，但数据库中没有匹配的内容
     * - 提供更清晰的诊断信息，帮助用户理解是数据问题还是检索问题
     */
    private static String evidenceFailureSummary(EvidencePack pack) {
        // 分类统计各来源状态
        List<String> statusDetails = new ArrayList<>();
        
        // 教材状态
        String textbookStatus = pack.textbookOutcome().status();
        if ("failed".equals(textbookStatus)) {
            statusDetails.add("教材：failed");
        } else if ("degraded".equals(textbookStatus)) {
            statusDetails.add("教材：degraded");
        } else if ("completed".equals(textbookStatus)) {
            statusDetails.add("教材：completed");
        }
        
        // 题库状态
        String questionStatus = pack.questionOutcome().status();
        if ("failed".equals(questionStatus)) {
            statusDetails.add("题库：failed");
        } else if ("degraded".equals(questionStatus)) {
            statusDetails.add("题库：degraded");
        } else if ("completed".equals(questionStatus)) {
            statusDetails.add("题库：completed");
        }
        
        // 教师资料状态
        String teacherStatus = pack.teacherResourceOutcome().status();
        if ("failed".equals(teacherStatus)) {
            statusDetails.add("教师资料：failed");
        } else if ("degraded".equals(teacherStatus)) {
            statusDetails.add("教师资料：degraded");
        } else if ("completed".equals(teacherStatus)) {
            statusDetails.add("教师资料：completed");
        } else if ("skipped".equals(teacherStatus)) {
            statusDetails.add("教师资料：skipped");
        }
        
        // 组装最终错误信息
        return "未检索到可核验的教材、题库或教师资料证据；" + String.join("；", statusDetails) 
                + "。用户输入不能作为检索证据，已停止生成讲义。";
    }

    /** Converts operational failures into a compact status without leaking internal connection details. */
    private static RetrievalOutcome retrievalFailureOutcome(String source, RuntimeException failure, long elapsedMs) {
        String category = failure instanceof RejectedExecutionException ? "executor_rejected"
                : failure.getCause() instanceof java.util.concurrent.TimeoutException ? "timeout"
                : "dependency_unavailable";
        return RetrievalOutcome.degraded(source + "检索" + category + "，耗时" + elapsedMs
                + "ms；已保留其他已授权资料，可独立恢复。");
    }

    /** Submits a branch once and turns queue rejection into an independently persisted branch outcome. */
    private static CompletableFuture<TimedEvidence> submitEvidence(
            java.util.function.Supplier<List<TeachingEvidence>> supplier, TaskExecutor executor) {
        try {
            return CompletableFuture.supplyAsync(() -> timeEvidence(supplier), executor);
        } catch (RejectedExecutionException rejection) {
            CompletableFuture<TimedEvidence> failed = new CompletableFuture<>();
            failed.completeExceptionally(rejection);
            return failed;
        }
    }

    /** Uses the branch start time so a timeout remains operationally measurable in a durable snapshot. */
    private static TimedEvidence failedEvidence(long branchStartedNanos) {
        return new TimedEvidence(List.of(), Math.max(0L, (System.nanoTime() - branchStartedNanos) / 1_000_000L));
    }

    /** Safe telemetry is retained in task snapshots through node/event summaries and stage timings. */
    private static RetrievalOutcome completedOutcome(String source, TimedEvidence evidence) {
        return RetrievalOutcome.completed(source + "检索完成：候选/已核验=" + evidence.evidence().size()
                + "/" + evidence.evidence().size() + "，耗时" + evidence.elapsedMs() + "ms。");
    }

    protected List<TeachingEvidence> retrieveTextbookEvidence(TeachingTaskRequest request, TeachingRequestContext context) {
        /*
         * Search each compact, topic-derived branch independently.  The old implementation sent one
         * concatenated query containing the full goal and question; semantic retrieval quite reasonably
         * ranked the long prose, while the later topic aligner rejected those rows.  Branching keeps the
         * retrieval contract small and lets the existing BGE/CLIP/rerank pipeline score the actual topic.
         */
        LinkedHashMap<String, TeachingEvidence> merged = new LinkedHashMap<>();
        List<String> queries = alignedQueries(request);
        String primaryTopic = primaryTopicKeyword(request);
        if (!primaryTopic.isBlank()) {
            LinkedHashSet<String> boundedQueries = new LinkedHashSet<>();
            boundedQueries.add(primaryTopic);
            boundedQueries.addAll(queries);
            // The public endpoint's mature behavior is a compact topic lookup. Limit handout fan-out so a long goal
            // cannot serially repeat an expensive textbook search and starve unrelated evidence branches.
            queries = boundedQueries.stream().limit(2).toList();
        }
        if (queries.isEmpty()) {
            String fallback = retrievalQuery(request);
            if (!fallback.isBlank()) {
                queries = List.of(fallback);
            }
        }
        RetrievalRequestContext retrievalContext = new RetrievalRequestContext(
                context.tenantId(),
                context.subjectType(),
                context.subjectId(),
                null,
                context.deviceId(),
                "teaching-workflow",
                "/api/teaching/tasks");
        for (String query : queries) {
            if (query == null || query.isBlank()) {
                continue;
            }

            TextbookSearchResponse retrieval = retrievalService.search(
                    processedBooksRoot,
                    new TextbookSearchRequest(query, request.evidenceLimit()),
                    retrievalContext);
            for (TextbookSearchHit hit : retrieval.hits()) {
                TeachingEvidence evidence = toEvidence(hit);
                String key = evidence.sourceScope() + ":" + evidence.chunkId();
                TeachingEvidence existing = merged.get(key);
                if (existing == null || shouldPreferEvidence(evidence, existing)) {
                    merged.put(key, evidence);
                }
            }
        }
        return merged.values().stream()
                .limit(request.evidenceLimit())
                .toList();
    }


    protected List<TeachingEvidence> retrieveQuestionBankEvidence(
            TeachingTaskRequest request,
            TeachingRequestContext context,
            List<String> curriculumPointQueries) {
        if (!canUseQuestionBank(context) || questionBankService == null) {
            return List.of();
        }
        Map<String, QuestionBankItemResponse> matchedQuestions = new LinkedHashMap<>();
        LinkedHashSet<String> alignedQueries = new LinkedHashSet<>();
        if (curriculumPointQueries != null) {
            alignedQueries.addAll(curriculumPointQueries);
        }
        // Request-derived queries remain a safe sparse-library fallback, but can no longer be the only route when a
        // teacher document has already disclosed a more concrete directory point.
        alignedQueries.addAll(alignedQueries(request));
        try {

            for (String query : alignedQueries) {
                for (QuestionBankItemResponse question : questionBankService.searchQuestions(
                        context.tenantId(),
                        context.subjectType(),
                        context.subjectId(),
                        query,
                        request.evidenceLimit())) {
                    matchedQuestions.putIfAbsent(question.questionId(), question);
                }
            }
            List<QuestionBankItemResponse> alignedQuestions = matchedQuestions.values().stream()
                    // SQL LIKE is intentionally permissive so teachers can browse a sparse bank.  A handout is
                    // different: an unrelated hit (for example, a geometry maximum-value problem for a quadratic
                    // minimum-value lesson) is worse than no hit because it contaminates all three editions.
                    .filter(question -> hasSpecificQuestionTopicMatch(request, question))
                    .filter(TeachingWorkflowService::isAtomicQuestionBankItem)
                    .sorted(Comparator.comparingInt(TeachingWorkflowService::questionDifficultyRank))
                    .toList();
            /*
             * A user may explicitly ask for a directory-wide real-exam compilation. Imported exam pages can share a
             * single source knowledge-point id even though their atomic prompts cover vectors, conics, geometry and
             * counting; the usual two-per-point lesson cap would then incorrectly leave a qualified eleven-question
             * source with only one printable item.  This narrow branch reads only existing visible bank rows and keeps
             * the same atomic/source checks. It never fabricates a question or activates for an ordinary single-topic
             * lesson.
             */
            if (requiresQualifiedQuestionCompilation(request)
                    && alignedQuestions.size() < MIN_QUALIFIED_HANDOUT_QUESTION_COUNT) {
                List<QuestionBankItemResponse> visibleAtomicQuestions = questionBankService.searchQuestions(
                                context.tenantId(),
                                context.subjectType(),
                                context.subjectId(),
                                "",
                                compilationSearchLimit(request)).stream()
                        .filter(TeachingWorkflowService::isAtomicQuestionBankItem)
                        .toList();
                /*
                 * A qualified continuous-paper template must never mix unrelated legacy banks merely because their
                 * loose search terms ranked first. Prefer one source document that independently contributes the
                 * ten required atomic prompts. This makes the document/page asset lineage deterministic: every
                 * figure gate can resolve the question back to its own synchronized source page.
                 */
                List<QuestionBankItemResponse> sourcePack = qualifiedSingleSourceQuestionPack(visibleAtomicQuestions);
                if (sourcePack.size() >= MIN_QUALIFIED_HANDOUT_QUESTION_COUNT) {
                    return sourcePack.stream()
                            .map(question -> toQuestionEvidence(question, context))
                            .toList();
                }
                LinkedHashMap<String, QuestionBankItemResponse> expanded = new LinkedHashMap<>();
                alignedQuestions.forEach(question -> expanded.put(question.questionId(), question));
                visibleAtomicQuestions.forEach(question -> expanded.putIfAbsent(question.questionId(), question));
                return deduplicateAtomicQuestionRows(expanded.values()).stream()
                        .sorted(Comparator.comparingInt(TeachingWorkflowService::questionDifficultyRank))
                        .map(question -> toQuestionEvidence(question, context))
                        .toList();
            }
            // A directory lesson can contain several concrete knowledge points. Select one example plus one
            // variation per point before rendering, instead of letting the broad first query consume the whole list.
            return selectQuestionsByKnowledgePoint(request, deduplicateAtomicQuestionRows(alignedQuestions)).stream()
                    .map(question -> toQuestionEvidence(question, context))
                    .toList();
        } catch (IllegalArgumentException exception) {
            return List.of();
        }
    }


    /** 检索公共规范试卷；授权由语料清单和来源哈希完成，绝不依赖教师资料可见性。 */
    protected List<TeachingEvidence> retrieveCanonicalMathPaperEvidence(TeachingTaskRequest request) {
        if (canonicalMathPaperRetrievalService == null) {
            return List.of();
        }
        String query = ((request.learningGoal() == null ? "" : request.learningGoal()) + " "
                + (request.questionText() == null ? "" : request.questionText())).strip();
        try {
            return canonicalMathPaperRetrievalService.search(query, request.evidenceLimit());
        } catch (IllegalArgumentException exception) {
            return List.of();
        }
    }

    protected List<TeachingEvidence> retrieveTeacherResourceEvidence(TeachingTaskRequest request, TeachingRequestContext context) {
        if (!canUseTeacherResources(context) || teacherResourceBlockSearchService == null) {
            return List.of();
        }
        Map<String, TeacherResourceBlockSearchResponse.Hit> matchedBlocks = new LinkedHashMap<>();
        Set<String> visualRecoveryDocuments = new LinkedHashSet<>();
        boolean visualRequest = VISUAL_EVIDENCE_REQUEST.matcher(
                (request.questionText() == null ? "" : request.questionText()) + " "
                        + (request.learningGoal() == null ? "" : request.learningGoal())).find();
        List<String> alignedQueries = alignedQueries(request);
        // A visual task already provides an exact source-facing goal. Searching every extracted natural-language
        // token serially repeats the full vector/rerank pipeline and can consume the task-level evidence barrier
        // before the authorized figure is reached. Start with that title, then use one compact curriculum fallback.
        if (visualRequest) {
            LinkedHashSet<String> visualQueries = new LinkedHashSet<>();
            String exactGoal = request.learningGoal() == null ? "" : request.learningGoal().strip();
            if (!exactGoal.isBlank()) {
                visualQueries.add(exactGoal);
            }
            String coreTopic = primaryTopicKeyword(request);
            if (!coreTopic.isBlank()) {
                visualQueries.add(coreTopic);
            }
            alignedQueries.stream()
                    .filter(query -> query != null && !query.isBlank())
                    .forEach(visualQueries::add);
            alignedQueries = visualQueries.stream().limit(TEACHER_RESOURCE_VISUAL_QUERY_LIMIT).toList();
        }
        try {
            for (String query : alignedQueries) {
                TeacherResourceBlockSearchResponse response = teacherResourceBlockSearchService.search(
                        context.tenantId(),
                        context.subjectType(),
                        context.subjectId(),
                        query,
                        6,
                        "/api/teaching/tasks");
                for (TeacherResourceBlockSearchResponse.Hit hit : response.hits()) {
                    matchedBlocks.putIfAbsent(hit.documentId() + ":" + hit.blockId(), hit);
                    if (visualRequest
                            && (hit.assetRefs() == null || hit.assetRefs().isEmpty())
                            && visualRecoveryDocuments.add(hit.documentId())
                            && visualRecoveryDocuments.size() <= TEACHER_RESOURCE_IMAGE_RECOVERY_LIMIT) {
                        // A document may rank its title/intro block first while a sibling block owns the real image
                        // refs. Re-query that same authorized document so visual evidence is not lost to global top-N.
                        TeacherResourceBlockSearchResponse scoped = teacherResourceBlockSearchService.search(
                                context.tenantId(),
                                context.subjectType(),
                                context.subjectId(),
                                query,
                                6,
                                "/api/teaching/tasks",
                                TeacherResourceSearchFilter.of(
                                        List.of(),
                                        List.of(hit.documentId()),
                                        List.of(),
                                        List.of()));
                        for (TeacherResourceBlockSearchResponse.Hit scopedHit : scoped.hits()) {
                            matchedBlocks.putIfAbsent(scopedHit.documentId() + ":" + scopedHit.blockId(), scopedHit);
                        }
                    }
                }
            }
            List<TeachingEvidence> collectedEvidence = matchedBlocks.values().stream()
                    .filter(hit -> teacherHitRespectsColorCountConstraint(request, hit))
                    .sorted((left, right) -> {
                        if (visualRequest) {
                            boolean leftHasImage = left.assetRefs() != null && !left.assetRefs().isEmpty();
                            boolean rightHasImage = right.assetRefs() != null && !right.assetRefs().isEmpty();
                            if (leftHasImage != rightHasImage) {
                                return leftHasImage ? -1 : 1;
                            }
                        }
                        return Comparator.comparingDouble(TeacherResourceBlockSearchResponse.Hit::score)
                                .reversed()
                                .thenComparing(TeacherResourceBlockSearchResponse.Hit::documentTitle)
                                .thenComparingInt(TeacherResourceBlockSearchResponse.Hit::blockOrder)

                                .compare(left, right);
                    })

                    .map(hit -> toTeacherResourceEvidence(hit, context))
                    // A retrieved teacher block can itself be a question stem.  Keep a figure-dependent stem only
                    // when the permission-checked materialization above produced a readable local original; this
                    // prevents an unrelated text-only hit containing “如图” from making a later PDF unsafe.
                    .filter(this::isPublishableTeacherEvidence)
                    .limit(Math.max(1, request.evidenceLimit()))
                    .toList();
            // A source can be synchronized through two paths (for example a Feishu document and an image-recovery
            // import). They are different blocks in storage but one teaching source; retain the image-bearing copy
            // so the model and all three handout versions never repeat the same OCR paragraph.
            return deduplicateSupportingEvidence(collectedEvidence);
        } catch (IllegalArgumentException exception) {
            return List.of();
        }
    }

    /** Applies the same source-image requirement before a retrieved teacher block reaches the printable handout. */
    private boolean isPublishableTeacherEvidence(TeachingEvidence evidence) {
        if (evidence == null || !requiresAuthorizedFigure(evidence.snippet())) {
            return evidence != null;
        }
        String imagePath = evidence.imagePath();
        if (imagePath == null || imagePath.isBlank()) {
            return false;
        }
        try {
            return Files.isRegularFile(Path.of(imagePath));
        } catch (InvalidPathException exception) {
            return false;
        }
    }


    /**
     * Converts an atomic bank row into printable evidence and restores its same-page diagram only when required.
     *
     * <p>The question bank deliberately stores text and source lineage, not filesystem paths. For a {@code 如图}
     * child row we therefore resolve {@code parentBlockId#qN -> parent page -> opaque asset -> authorized local
     * file} at task time under the current user. Non-figure questions do not trigger image materialization or a
     * costly visual-model call.</p>
     */
    protected TeachingEvidence toQuestionEvidence(QuestionBankItemResponse question, TeachingRequestContext context) {
        String difficulty = question.difficulty() == null || question.difficulty().isBlank()
                ? "未标难度"
                : question.difficulty();
        String title = question.questionTitle() + " / 难度：" + difficulty;
        String snippet = question.questionText();
        if (question.answerJson() != null && !question.answerJson().isBlank() && !"{}".equals(question.answerJson().strip())) {
            String formattedAnswer = QuestionBankAnswerFormatter.format(question.answerJson());
            if (!formattedAnswer.isBlank()) {
                snippet = snippet + "\n答案要点：" + formattedAnswer;
            }
        }
        TeacherResourceVisualEvidenceService.MaterializedImageEvidence image = null;
        if (requiresAuthorizedFigure(question.questionText())
                && question.sourceResourceDocumentId() != null
                && !question.sourceResourceDocumentId().isBlank()
                && question.sourceBlockId() != null
                && !question.sourceBlockId().isBlank()
                && teacherResourceBlockSearchService != null) {
            RequestSubject subject = new RequestSubject(
                    context.tenantId(), context.subjectType(), context.subjectId(), context.deviceId()).normalize();
            image = teacherResourceBlockSearchService
                    // DOCX keeps an atomic image block next to its numbered stem, so it is the most precise source.
                    .resolveVisibleInlineFigureForQuestion(
                            question.sourceResourceDocumentId(), question.questionText(), subject)
                    // PDF imports intentionally retain the authorized rendered page on the parent block while their
                    // atomic question rows use "parentBlockId#qN".  Falling back through that documented lineage
                    // restores the original same-source page without inventing a diagram or bypassing visibility.
                    .or(() -> teacherResourceBlockSearchService.resolveVisiblePageImageForQuestion(
                            question.sourceResourceDocumentId(), question.sourceBlockId(), subject))
                    // Both resolvers deliberately return Optional: a missing/unauthorized page asset excludes the
                    // figure-dependent question later in rendering instead of manufacturing a replacement diagram.
                    .flatMap(asset -> materializeTeacherImage(asset, subject, question.questionText()))
                    .orElse(null);
        }
        return new TeachingEvidence(
                "QUESTION_BANK",
                title,
                question.questionId(),
                0,
                snippet,
                image == null ? "" : image.imagePath().toString(),
                image == null ? "" : image.imageDescription(),
                question.sourceResourceDocumentId() == null ? "" : question.sourceResourceDocumentId(),
                "question_bank",
                "",
                question.sourceBlockId() == null ? "" : question.sourceBlockId(),
                List.of());
    }


    protected TeachingEvidence toTeacherResourceEvidence(
            TeacherResourceBlockSearchResponse.Hit hit,
            TeachingRequestContext context) {
        RequestSubject subject = new RequestSubject(
                context.tenantId(),
                context.subjectType(),
                context.subjectId(),
                context.deviceId()).normalize();
        /*
         * A shared legacy mirror can legitimately rank in RAG while its document id is no longer expandable through
         * the current teacher library.  Persist only the resolver's current visible reference.  When no verified
         * same-source block exists, retain the evidence for rendering but intentionally omit sourceDocumentId so the
         * frontend has no broken or permission-bypassing “view original” target.
         */
        Optional<TeacherResourceBlockSearchService.CanonicalReference> inspectionReference =
                teacherResourceBlockSearchService == null
                        ? Optional.empty()
                        : teacherResourceBlockSearchService.resolveVisibleReference(
                                context.tenantId(), context.subjectType(), context.subjectId(), hit);
        TeacherResourceVisualEvidenceService.MaterializedImageEvidence image = hit.assetRefs() == null

                ? null
                : hit.assetRefs().stream()
                        .filter(asset -> asset.assetId() != null && !asset.assetId().isBlank())
                        .map(asset -> materializeTeacherImage(asset, subject, hit.evidenceText()))
                        .flatMap(Optional::stream)
                        .findFirst()
                        .orElse(null);
        return new TeachingEvidence(
                "TEACHER_RESOURCE",
                teacherResourceSourceTitle(hit),
                inspectionReference.map(TeacherResourceBlockSearchService.CanonicalReference::blockId)
                        .orElse(hit.blockId()),
                hit.pageNo() == null ? 0 : hit.pageNo(),
                /*
                 * `evidenceText` is an expanded retrieval window and can contain a previous introduction or the
                 * next 5/6-colour variation.  Printable question evidence must retain the exact matched atomic
                 * block first, otherwise an authorized map loses its own colour condition after compaction.
                 */
                // The expanded permission-filtered window is the primary source.  Passing the search snippet first
                // silently discarded verified answer clauses such as “24+48=72” whenever the snippet was short.
                compactTeachingEvidence(hit.evidenceText(), hit.snippet()),
                image == null ? "" : image.imagePath().toString(),
                image == null ? "" : image.imageDescription(),
                teacherResourceBlockSearchService == null
                        ? hit.documentId()
                        : inspectionReference
                                .map(TeacherResourceBlockSearchService.CanonicalReference::documentId)
                                .orElse(""),
                hit.sourceType(),
                inspectionReference.map(TeacherResourceBlockSearchService.CanonicalReference::originalUrl).orElse(""),
                hit.sourcePath(),
                hit.imageAssetIds() == null ? List.of() : hit.imageAssetIds());
    }


    /** Keeps the task contract strictly retrieval-grounded; request text and memory are never evidence rows. */
    protected List<TeachingEvidence> verifiedEvidence(List<TeachingEvidence> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        return candidates.stream()
                .filter(item -> item != null)
                .filter(item -> Set.of("PUBLIC_TEXTBOOK", "QUESTION_BANK", "TEACHER_RESOURCE", "CANONICAL_MATH_PAPER")
                        .contains(item.sourceScope()))
                .filter(item -> item.snippet() != null && !item.snippet().isBlank())
                // TEACHER_RESOURCE可能没有chunkId（文档级证据），不应被过滤
                .filter(item -> "TEACHER_RESOURCE".equals(item.sourceScope()) 
                        || (item.chunkId() != null && !item.chunkId().isBlank()))
                .toList();
    }


    /**
     * Resolves the local rendering copy and optional visual facts from the same authorized teacher asset request.
     *
     * <p>The compatibility fallback is intentionally image-only. It preserves existing rendering when the optional
     * vision adapter is not wired, but never fabricates a caption from a filename or from an unverified remote URL.</p>
     */
    protected Optional<TeacherResourceVisualEvidenceService.MaterializedImageEvidence> materializeTeacherImage(
            TeacherResourceBlockSearchResponse.AssetRef asset,
            RequestSubject subject) {
        return materializeTeacherImage(asset, subject, "");
    }

    /** Carries source-adjacent text into the visual evidence contract; paths and filenames are not captions. */
    protected Optional<TeacherResourceVisualEvidenceService.MaterializedImageEvidence> materializeTeacherImage(
            TeacherResourceBlockSearchResponse.AssetRef asset,
            RequestSubject subject,
            String verifiedAdjacentText) {
        if (teacherResourceVisualEvidenceService != null) {
            return teacherResourceVisualEvidenceService.materialize(
                    asset.assetId(), asset.mimeType(), subject, verifiedAdjacentText);
        }
        if (teacherResourceBlockSearchService == null) {
            return Optional.empty();
        }
        return teacherResourceBlockSearchService.materializeVisibleAsset(asset.assetId(), subject)
                .map(path -> new TeacherResourceVisualEvidenceService.MaterializedImageEvidence(
                        path, safeEvidenceText(verifiedAdjacentText)));
    }

    /** Converts a monotonic start timestamp into the bounded duration stored in the trace row. */
    private static long elapsedMs(long startedNanos) {
        return Math.max(0L, (System.nanoTime() - startedNanos) / 1_000_000L);
    }

    /** Keeps branch prompts bounded while preserving a source title and the beginning of its verified stem. */
    private static String safeEvidenceText(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String normalized = value.replaceAll("\\s+", " ").strip();
        return normalized.length() > MAX_TEACHING_EVIDENCE_CHARS
                ? normalized.substring(0, MAX_TEACHING_EVIDENCE_CHARS).strip()
                : normalized;
    }

    /** Reads one persisted stage timing without adding another synthetic timer checkpoint. */
    private static long timingFor(StageTimer timer, String stage) {
        if (timer == null || stage == null) return 0L;
        return timer.timings().stream().filter(item -> stage.equals(item.stage()))
                .mapToLong(TeachingTaskResponse.StageTiming::elapsedMs).sum();
    }

    /** Sums all durable spans for the parent orchestrator trace. */
    private static long timingSum(StageTimer timer, String... stages) {
        if (stages == null || stages.length == 0) {
            return timer == null ? 0L : timer.timings().stream()
                    .mapToLong(TeachingTaskResponse.StageTiming::elapsedMs).sum();
        }
        long total = 0L;
        for (String stage : stages) total += timingFor(timer, stage);
        return total;
    }


    protected void saveRunningProgress(
            TeachingTaskRequest request,
            TeachingRequestContext context,
            String taskId,
            String ownerKey,
            String idempotencyKey,
            com.doob.mathagent.teaching.mq.LectureTaskLease lease,
            TeachingHandoutTemplateProfile template,
            StudentMemoryResponse memoryResponse,
            List<TeachingEvidence> evidence,
            List<TeachingEvidence> textbookEvidence,
            List<TeachingEvidence> questionEvidence,
            List<TeachingEvidence> teacherResourceEvidence,
            TeachingTaskResponse.AiDraft aiDraft,
            StageTimer timer,
            ProgressPhase phase) {
        RetrievalOutcome settled = RetrievalOutcome.completed();
        saveRunningProgress(request, context, taskId, ownerKey, idempotencyKey, lease, template, memoryResponse,
                evidence, textbookEvidence, questionEvidence, teacherResourceEvidence, aiDraft, timer, phase,
                phase == ProgressPhase.EVIDENCE_COLLECTING ? RetrievalOutcome.running() : settled,
                phase == ProgressPhase.EVIDENCE_COLLECTING ? RetrievalOutcome.running() : settled,
                phase == ProgressPhase.EVIDENCE_COLLECTING ? RetrievalOutcome.running() : settled);
    }

    /**
     * Persists one meaningful RUNNING snapshot after each durable boundary. The same snapshot is read by REST
     * recovery and SSE, preventing the frontend from rendering temporary zero-value placeholders.
     */
    protected void saveRunningProgress(
            TeachingTaskRequest request,
            TeachingRequestContext context,
            String taskId,
            String ownerKey,
            String idempotencyKey,
            com.doob.mathagent.teaching.mq.LectureTaskLease lease,
            TeachingHandoutTemplateProfile template,
            StudentMemoryResponse memoryResponse,
            List<TeachingEvidence> evidence,
            List<TeachingEvidence> textbookEvidence,
            List<TeachingEvidence> questionEvidence,
            List<TeachingEvidence> teacherResourceEvidence,
            TeachingTaskResponse.AiDraft aiDraft,
            StageTimer timer,
            ProgressPhase phase,
            RetrievalOutcome textbookOutcome,
            RetrievalOutcome questionOutcome,
            RetrievalOutcome teacherResourceOutcome) {
        if (taskId == null || ownerKey == null || idempotencyKey == null) {
            return;
        }
        List<TeachingWorkflowNode> nodes = progressWorkflowNodes(
                request,
                memoryResponse,
                evidence,
                textbookEvidence,
                questionEvidence,
                teacherResourceEvidence,
                aiDraft,
                template,
                canUseQuestionBank(context),
                canUseTeacherResources(context),
                phase, textbookOutcome, questionOutcome, teacherResourceOutcome);
        TeachingTaskResponse snapshot = new TeachingTaskResponse(
                taskId,
                request.clientRequestId(),
                context.tenantId(),
                context.subjectType(),
                context.subjectId(),
                template.summary(),
                TeachingTaskStatus.RUNNING,
                request.questionText(),
                request.learningGoal(),
                request.watermarkText(),
                nodes,
                progressWorkflowEvents(template, textbookEvidence, questionEvidence, teacherResourceEvidence, aiDraft, phase,
                        textbookOutcome, questionOutcome, teacherResourceOutcome),
                List.of(),
                evidence,
                "", "", "", "",
                List.of(),
                toMemoryReuse(memoryResponse),
                timer.timings(),
                aiDraft,
                null,
                null,
                null,
                null)
                .withPageChrome(request.headerLeft(), request.headerRight(), request.footerLeft(), request.footerRight());
        if (lease != null) {
            if (!taskStore.saveOwnedRunning(lease, snapshot)) {
                throw new TeachingWorkflowService.LeaseLostException();
            }
            return;
        }
        taskStore.save(ownerKey, idempotencyKey, snapshot);
    }
}
