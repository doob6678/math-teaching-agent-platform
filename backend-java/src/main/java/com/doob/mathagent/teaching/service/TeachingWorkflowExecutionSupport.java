package com.doob.mathagent.teaching.service;

import com.doob.mathagent.agent.service.AgentTraceRecord;
import com.doob.mathagent.agent.service.AgentTraceStore;
import com.doob.mathagent.agent.dto.AgentRunExecuteRequest;
import com.doob.mathagent.agent.dto.AgentRunPlanRequest;
import com.doob.mathagent.agent.service.AgentRunExecutionService;
import com.doob.mathagent.agent.service.AgentRunPlanService;
import com.doob.mathagent.agent.vo.AgentRunExecuteResponse;
import com.doob.mathagent.agent.vo.AgentRunPlanResponse;
import com.doob.mathagent.infrastructure.security.RequestSubject;
import com.doob.mathagent.knowledge.service.KnowledgeQuestionBankService;
import com.doob.mathagent.knowledge.service.QuestionBankSearchText;
import com.doob.mathagent.knowledge.vo.QuestionBankItemResponse;
import com.doob.mathagent.memory.dto.StudentMemoryRequest;
import com.doob.mathagent.memory.service.StudentMemoryCommand;
import com.doob.mathagent.memory.service.StudentMemoryReuseService;
import com.doob.mathagent.memory.vo.StudentMemoryResponse;
import com.doob.mathagent.retrieval.RetrievalRequestContext;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
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
import com.doob.mathagent.teaching.service.TeachingWorkflowService.QuestionAgentContext;
import com.doob.mathagent.teaching.service.TeachingWorkflowService.QuestionAgentBranch;
import com.doob.mathagent.teaching.service.TeachingWorkflowService.QuestionAgentTiming;
import com.doob.mathagent.teaching.service.TeachingWorkflowService.QuestionAgentBatch;
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
    protected TeachingAiDraftService aiDraftService;
    protected AgentTraceStore agentTraceStore;
    protected TeachingHandoutTemplateService handoutTemplateService;
    protected KnowledgeQuestionBankService questionBankService;
    protected TeacherResourceBlockSearchService teacherResourceBlockSearchService;
    protected TeacherResourceVisualEvidenceService teacherResourceVisualEvidenceService;
    protected TaskExecutor taskExecutor;
    protected boolean returnCompletedWhenExecutorIsSynchronous;
    /** Persists auditable parent/child node records independently from the UI task snapshot. */
    protected TeachingWorkflowTraceRecorder traceRecorder;
    /** Shared planner/executor used by every question branch; null only in focused unit-test constructors. */
    protected AgentRunPlanService agentRunPlanService;
    protected AgentRunExecutionService agentRunExecutionService;


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
        requireQualifiedRenderedQuestionCount(template, versions.teacherHandoutLatex());
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
        saveRunningProgress(
                request, context, taskId, ownerKey, idempotencyKey, template, memoryResponse,
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
            EvidencePack evidencePack = retrieveEvidencePack(request, context, taskId);
            textbookEvidence = evidencePack.textbookEvidence();
            questionEvidence = evidencePack.questionEvidence();
            teacherResourceEvidence = evidencePack.teacherResourceEvidence();
            timer.record("textbook_retrieval", evidencePack.textbookElapsedMs());
            timer.record("question_bank_retrieval", evidencePack.questionElapsedMs());
            timer.record("teacher_resource_retrieval", evidencePack.teacherResourceElapsedMs());
            timer.resetCheckpoint();
            evidence = verifiedEvidence(evidencePack.mergedEvidence());
            textbookEvidence = verifiedEvidence(textbookEvidence);
            questionEvidence = verifiedEvidence(questionEvidence);
            teacherResourceEvidence = verifiedEvidence(teacherResourceEvidence);
            if (evidence.isEmpty()) {
                traceRecorder.failed(taskId, context, "WORKFLOW_ORCHESTRATOR", "TeachingOrchestrator", evidence,
                        timingSum(timer, "textbook_retrieval", "question_bank_retrieval", "teacher_resource_retrieval"),
                        new IllegalStateException("未检索到可核验证据"));
                throw new IllegalStateException("未检索到可核验的教材、题库或教师资料证据；用户输入不能作为检索证据，已停止生成讲义。");
            }
        }
        evidence = verifiedEvidence(evidence);
        textbookEvidence = verifiedEvidence(textbookEvidence);
        questionEvidence = verifiedEvidence(questionEvidence);
        teacherResourceEvidence = verifiedEvidence(teacherResourceEvidence);
        if (evidence.isEmpty()) {
            traceRecorder.failed(taskId, context, "WORKFLOW_ORCHESTRATOR", "TeachingOrchestrator", evidence,
                    timingSum(timer, "textbook_retrieval", "question_bank_retrieval", "teacher_resource_retrieval"),
                    new IllegalStateException("讲义任务缺少可核验来源证据"));
            throw new IllegalStateException("讲义任务缺少可核验来源证据，禁止发布零证据讲义。");
        }
        traceRecorder.completed(taskId, context, "EVIDENCE_COLLECTION", "EvidenceCollector", evidence,
                timingSum(timer, "textbook_retrieval", "question_bank_retrieval", "teacher_resource_retrieval"),
                "已汇总教材、题库和教师资料的可核验来源。");
        saveRunningProgress(
                request, context, taskId, ownerKey, idempotencyKey, template, memoryResponse,
                evidence, textbookEvidence, questionEvidence, teacherResourceEvidence, null, timer,
                ProgressPhase.OUTLINE_BUILDING);
        requireQualifiedQuestionEvidence(template, questionEvidence);
        // Fan out an immutable context per verified question before the shared draft is built. This is the
        // orchestration boundary for question agents and keeps cross-question state out of each worker.
        QuestionAgentBatch questionAgentBatch = prepareQuestionAgentContexts(questionEvidence);


        timer.record("question_agents_parallel", questionAgentBatch.elapsedMs());
        // Keep one durable timing row per isolated question branch.  The aggregate barrier alone cannot tell the
        // progress UI which question was slow, and would make a failed branch indistinguishable from a healthy one.
        questionAgentBatch.branchTimings().forEach(branch ->
                timer.record("question_agent_" + branch.agentId(), branch.elapsedMs()));
        final String traceTaskId = taskId;
        final TeachingRequestContext traceContext = context;
        final List<TeachingEvidence> traceQuestionEvidence = List.copyOf(questionEvidence);
        runQuestionAgents(
                request,
                context,
                traceTaskId,
                traceQuestionEvidence,
                questionAgentBatch);
        List<TeachingReactStep> reactTrace = List.of();
        timer.mark("react_trace");
        saveRunningProgress(
                request, context, taskId, ownerKey, idempotencyKey, template, memoryResponse,
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
            aiDraft = checkpoint != null && checkpoint.aiDraft() != null && checkpoint.aiDraft().structured()
                    ? checkpoint.aiDraft()
                    : aiDraftService.draft(request, evidence, memoryResponse, template);
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
                request, context, taskId, ownerKey, idempotencyKey, template, memoryResponse,
                evidence, textbookEvidence, questionEvidence, teacherResourceEvidence, aiDraft, timer,
                ProgressPhase.CONTENT_GENERATING);

        // The continuous real-question master promises per-question reasoning, not a deterministic fallback page.
        // A relay timeout or malformed model response must remain a recoverable FAILED task with its evidence intact;
        // otherwise a generic template can be mistaken for a teacher-reviewed explanation and reach PDF export.
        requireStructuredQuestionReasoning(template, aiDraft);
        timer.mark("ai_draft");
        saveRunningProgress(
                request, context, taskId, ownerKey, idempotencyKey, template, memoryResponse,
                evidence, textbookEvidence, questionEvidence, teacherResourceEvidence, aiDraft, timer,
                ProgressPhase.HANDOUT_RENDERING);
        List<TeachingWorkflowNode> nodes = buildNodes(
                request,
                evidence,
                questionEvidence,
                teacherResourceEvidence,
                memoryResponse,
                aiDraft,
                template,
                canUseQuestionBank(context),
                canUseTeacherResources(context));
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
                template);
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
        if (ownerKey != null && idempotencyKey != null) {
            taskStore.save(ownerKey, idempotencyKey, response);
        }
        traceRecorder.completed(taskId, context, "WORKFLOW_ORCHESTRATOR", "TeachingOrchestrator", evidence,
                timingSum(timer), "教学 DAG 已完成并生成可审计讲义结果。");
        saveAiDraftTrace(response, context);
        return response;
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
                "",
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
     * 教学任务的证据 DAG：教材与教师资料互不依赖，先并行召回；题库必须等教师资料定位到具体课程点后再检索，
     * 避免仅凭宽泛学习目标选入无关题目。
     */
    protected EvidencePack retrieveEvidencePack(TeachingTaskRequest request, TeachingRequestContext context) {
        return retrieveEvidencePack(request, context, null);
    }

    /** Runs the retrieval barrier and records each real source branch under the parent task id. */
    protected EvidencePack retrieveEvidencePack(
            TeachingTaskRequest request, TeachingRequestContext context, String taskId) {
        /*
         * Do not reuse the outer teaching task executor here: the outer worker may already be occupied by this task.
         * A tiny per-task pool keeps textbook/question-bank/teacher-resource retrieval bounded and avoids starvation.
         */
        ExecutorService evidenceExecutor = Executors.newFixedThreadPool(2);
        // 两条独立证据源并行执行，缩短讲义任务的关键路径；后续题库检索保持依赖关系，不在此并发。
        CompletableFuture<TimedEvidence> textbookFuture = CompletableFuture.supplyAsync(
                () -> timeEvidence(() -> alignEvidenceToTopic(request, retrieveTextbookEvidence(request, context))),
                evidenceExecutor);
        CompletableFuture<TimedEvidence> teacherResourceFuture = CompletableFuture.supplyAsync(
                () -> timeEvidence(() -> alignEvidenceToTopic(request, retrieveTeacherResourceEvidence(request, context))),
                evidenceExecutor);
        try {
            TimedEvidence textbook;
            try {
                textbook = awaitEvidence("textbook", textbookFuture);
            } catch (RuntimeException failure) {
                traceRecorder.failed(taskId, context, "PUBLIC_TEXTBOOK_RETRIEVAL", "TextbookRetriever", List.of(), 0L, failure);
                traceRecorder.failed(taskId, context, "WORKFLOW_ORCHESTRATOR", "TeachingOrchestrator", List.of(), 0L, failure);
                throw failure;
            }
            traceRecorder.completed(taskId, context, "PUBLIC_TEXTBOOK_RETRIEVAL", "TextbookRetriever",
                    textbook.evidence(), textbook.elapsedMs(), "教材检索完成。");

            TimedEvidence teacherResource;
            try {
                teacherResource = awaitEvidence("teacher_resource", teacherResourceFuture);
            } catch (RuntimeException failure) {
                traceRecorder.failed(taskId, context, "TEACHER_RESOURCE_RETRIEVAL", "TeacherResourceRetriever", List.of(), 0L, failure);
                traceRecorder.failed(taskId, context, "WORKFLOW_ORCHESTRATOR", "TeachingOrchestrator", List.of(), 0L, failure);
                throw failure;
            }
            traceRecorder.completed(taskId, context, "TEACHER_RESOURCE_RETRIEVAL", "TeacherResourceRetriever",
                    teacherResource.evidence(), teacherResource.elapsedMs(), "教师资料检索完成。");

            // The second retrieval is deliberately after the teacher-resource boundary.  This preserves the user's
            // intended chain: real directory/teacher material -> concrete knowledge point -> atomic bank question.
            TimedEvidence questionBank;
            try {
                questionBank = timeEvidence(() -> {
                    List<TeachingEvidence> retrievedQuestions = retrieveQuestionBankEvidence(
                            request, context, curriculumPointQueries(request, teacherResource.evidence()));
                    // retrieveQuestionBankEvidence has already selected permission-checked atomic rows for a qualified
                    // multi-topic compilation. Applying the single-topic aligner a second time collapses that pack back
                    // to the first matching subject and recreates the one-question failure that this branch prevents.
                    return requiresQualifiedQuestionCompilation(request)
                            ? retrievedQuestions
                            : alignEvidenceToTopic(request, retrievedQuestions);
                });
            } catch (RuntimeException failure) {
                traceRecorder.failed(taskId, context, "QUESTION_BANK_RETRIEVAL", "QuestionBankRetriever", List.of(), 0L, failure);
                traceRecorder.failed(taskId, context, "WORKFLOW_ORCHESTRATOR", "TeachingOrchestrator", List.of(), 0L, failure);
                throw failure;
            }
            traceRecorder.completed(taskId, context, "QUESTION_BANK_RETRIEVAL", "QuestionBankRetriever",
                    questionBank.evidence(), questionBank.elapsedMs(), "题库检索完成。");
            return new EvidencePack(
                    textbook.evidence(),
                    questionBank.evidence(),
                    teacherResource.evidence(),
                    textbook.elapsedMs(),
                    questionBank.elapsedMs(),
                    teacherResource.elapsedMs());
        } catch (RuntimeException exception) {
            textbookFuture.cancel(true);
            teacherResourceFuture.cancel(true);
            throw exception;
        } finally {
            evidenceExecutor.shutdownNow();
        }
    }

    /**
     * Executes one real agent run for every retrieved atomic question.
     *
     * <p>The retrieval result is immutable input to each branch. The branch receives only a short task summary and
     * stable evidence references; the user's text is never used as a search query or persisted as an evidence row.
     * A missing planner/executor is allowed only for legacy unit-test constructors, while the Spring production
     * constructor always wires both services. Any production branch failure propagates to the parent DAG.</p>
     */
    protected void runQuestionAgents(
            TeachingTaskRequest request,
            TeachingRequestContext context,
            String taskId,
            List<TeachingEvidence> questionEvidence,
            QuestionAgentBatch batch) {
        if (questionEvidence == null || questionEvidence.isEmpty()) {
            return;
        }
        // Focused compatibility constructors intentionally do not wire the production agent catalog. They exercise
        // retrieval/rendering in isolation; the Spring production constructor always supplies both services and
        // therefore cannot silently skip the real child-agent barrier.
        if (agentRunPlanService == null || agentRunExecutionService == null) {
            return;
        }
        ExecutorService executor = Executors.newFixedThreadPool(
                Math.max(1, Math.min(questionEvidence.size(), QUESTION_AGENT_MAX_PARALLELISM)));
        try {
            List<CompletableFuture<Void>> runs = questionEvidence.stream()
                    .map(evidence -> CompletableFuture.runAsync(
                            () -> executeQuestionAgent(request, context, taskId, evidence), executor))
                    .toList();
            runs.forEach(CompletableFuture::join);
        } finally {
            executor.shutdownNow();
        }
    }

    /** Runs and records one isolated question-agent branch. */
    private void executeQuestionAgent(
            TeachingTaskRequest request,
            TeachingRequestContext context,
            String taskId,
            TeachingEvidence evidence) {
        String agentId = questionAgentId(evidence);
        String nodeCode = "QUESTION_AGENT_" + agentId;
        List<TeachingEvidence> branchEvidence = List.of(evidence);
        long started = System.nanoTime();
        traceRecorder.running(taskId, context, nodeCode, "QuestionSolvingAgent", branchEvidence, 0L,
                "题目子智能体已分配，仅使用当前题目的检索证据。");
        try {
            RequestSubject subject = new RequestSubject(
                    context.tenantId(), context.subjectType(), context.subjectId(), context.deviceId()).normalize();
            AgentRunPlanResponse plan = agentRunPlanService.plan(
                    new AgentRunPlanRequest(
                            "TeacherAssistantAgent",
                            "question_solving",
                            "teacher",
                            Math.max(256, safeEvidenceText(evidence.snippet()).length()),
                            1200,
                            evidence.imagePath() != null && !evidence.imagePath().isBlank(),
                            true,
                            "medium",
                            "normal",
                            3.0d,
                            0,
                            false,
                            List.of("tool:search:textbook", "tool:search:private"),
                            List.of(),
                            List.of("PUBLIC_TEXTBOOK", "TEACHER_PRIVATE", "CLASS_AUTHORIZED"),
                            false,
                            "",
                            ""),
                    subject);
            String summary = "独立解答题目：" + safeEvidenceText(evidence.sourceTitle()) + "。题目内容："
                    + safeEvidenceText(evidence.snippet());
            AgentRunExecuteResponse execution = agentRunExecutionService.execute(
                    new AgentRunExecuteRequest(
                            plan,
                            summary,
                            branchEvidence.stream().map(TeachingWorkflowService::evidenceRef).toList(),
                            false),
                    subject);
            if (!"COMPLETED".equalsIgnoreCase(execution.status())) {
                throw new IllegalStateException("题目子智能体返回状态：" + execution.status());
            }
            traceRecorder.completed(taskId, context, nodeCode, execution.agentCode(), branchEvidence,
                    elapsedMs(started), "题目子智能体已完成真实模型调用并返回结果。");
        } catch (Throwable failure) {
            traceRecorder.failed(taskId, context, nodeCode, "QuestionSolvingAgent", branchEvidence,
                    elapsedMs(started), failure);
            throw failure instanceof RuntimeException runtime ? runtime : new IllegalStateException(failure);
        }
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

                    .limit(Math.max(1, request.evidenceLimit()))
                    .map(hit -> toTeacherResourceEvidence(hit, context))
                    .toList();
            // A source can be synchronized through two paths (for example a Feishu document and an image-recovery
            // import). They are different blocks in storage but one teaching source; retain the image-bearing copy
            // so the model and all three handout versions never repeat the same OCR paragraph.
            return deduplicateSupportingEvidence(collectedEvidence);
        } catch (IllegalArgumentException exception) {
            return List.of();
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
                    // A rendered source page is not an atomic diagram. Resolve only the original DOCX image that is
                    // structurally adjacent to this exact numbered stem; missing proof means no figure is printed.
                    .resolveVisibleInlineFigureForQuestion(
                            question.sourceResourceDocumentId(), question.questionText(), subject)
                    // Both resolvers deliberately return Optional: a missing/unauthorized page asset excludes the
                    // figure-dependent question later in rendering instead of manufacturing a replacement diagram.
                    .flatMap(asset -> materializeTeacherImage(asset, subject))
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
                        .map(asset -> materializeTeacherImage(asset, subject))
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
                .filter(item -> Set.of("PUBLIC_TEXTBOOK", "QUESTION_BANK", "TEACHER_RESOURCE")
                        .contains(item.sourceScope()))
                .filter(item -> item.snippet() != null && !item.snippet().isBlank())
                .filter(item -> item.chunkId() != null && !item.chunkId().isBlank())
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
        if (teacherResourceVisualEvidenceService != null) {

            return teacherResourceVisualEvidenceService.materialize(asset.assetId(), asset.mimeType(), subject);
        }
        if (teacherResourceBlockSearchService == null) {
            return Optional.empty();
        }
        return teacherResourceBlockSearchService.materializeVisibleAsset(asset.assetId(), subject)
                .map(path -> new TeacherResourceVisualEvidenceService.MaterializedImageEvidence(path, ""));
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
            TeachingHandoutTemplateProfile template,
            StudentMemoryResponse memoryResponse,
            List<TeachingEvidence> evidence,
            List<TeachingEvidence> textbookEvidence,
            List<TeachingEvidence> questionEvidence,
            List<TeachingEvidence> teacherResourceEvidence,
            TeachingTaskResponse.AiDraft aiDraft,
            StageTimer timer,

            ProgressPhase phase) {
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
                phase);
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
                progressWorkflowEvents(template, textbookEvidence, questionEvidence, teacherResourceEvidence, aiDraft, phase),
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
        taskStore.save(ownerKey, idempotencyKey, snapshot);
    }
}
