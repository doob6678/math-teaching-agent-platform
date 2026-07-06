package com.doob.mathagent.teaching.service;

import com.doob.mathagent.agent.service.AgentTraceRecord;
import com.doob.mathagent.agent.service.AgentTraceStore;
import com.doob.mathagent.agent.vo.AgentRunExecuteResponse;
import com.doob.mathagent.knowledge.service.KnowledgeQuestionBankService;
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
import com.doob.mathagent.teaching.TeachingEvidence;
import com.doob.mathagent.teaching.TeachingReactStep;
import com.doob.mathagent.teaching.TeachingRequestContext;
import com.doob.mathagent.teaching.TeachingTaskStatus;
import com.doob.mathagent.teaching.TeachingWorkflowNode;
import com.doob.mathagent.teaching.dto.TeachingTaskRequest;
import com.doob.mathagent.teaching.vo.TeachingTaskResponse;
import java.nio.file.Path;
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
 * 教学任务 DAG 编排服务：把用户学习目标、资源复用、教材检索、ReAct 解题和讲义生成串成可恢复任务。
 */
@Service
public class TeachingWorkflowService {

    private final Path processedBooksRoot;
    private final TextbookRetrievalService retrievalService;
    private final TeachingTaskStore taskStore;
    private final StudentMemoryReuseService memoryReuseService;
    private final TeachingAiDraftService aiDraftService;
    private final AgentTraceStore agentTraceStore;
    private final TeachingHandoutTemplateService handoutTemplateService;
    private final KnowledgeQuestionBankService questionBankService;
    private final TaskExecutor taskExecutor;
    private boolean returnCompletedWhenExecutorIsSynchronous;

    /**
     * 创建教学编排服务。
     *
     * @param processedBooksRoot 教材 processed_books 根目录。
     * @param retrievalService 教材 BM25-first 检索服务。
     * @param taskStore 任务存储，用于恢复和隔离。
     * @param memoryReuseService 学生长短期记忆复用服务。
     */
    @Autowired
    public TeachingWorkflowService(
            Path processedBooksRoot,
            TextbookRetrievalService retrievalService,
            TeachingTaskStore taskStore,
            StudentMemoryReuseService memoryReuseService,
            TeachingAiDraftService aiDraftService,
            AgentTraceStore agentTraceStore,
            TeachingHandoutTemplateService handoutTemplateService,
            Optional<KnowledgeQuestionBankService> questionBankService,
            @Qualifier("multiAgentWritingTaskExecutor") TaskExecutor taskExecutor) {
        this.processedBooksRoot = processedBooksRoot.toAbsolutePath().normalize();
        this.retrievalService = retrievalService;
        this.taskStore = taskStore;
        this.memoryReuseService = memoryReuseService;
        this.aiDraftService = aiDraftService;
        this.agentTraceStore = agentTraceStore;
        this.handoutTemplateService = handoutTemplateService;
        this.questionBankService = questionBankService.orElse(null);
        this.taskExecutor = taskExecutor;
        this.returnCompletedWhenExecutorIsSynchronous = false;
    }

    /**
     * Backward-compatible constructor that uses the built-in template registry.
     */
    public TeachingWorkflowService(
            Path processedBooksRoot,
            TextbookRetrievalService retrievalService,
            TeachingTaskStore taskStore,
            StudentMemoryReuseService memoryReuseService,
            TeachingAiDraftService aiDraftService,
            AgentTraceStore agentTraceStore) {
        this(
                processedBooksRoot,
                retrievalService,
                taskStore,
                memoryReuseService,
                aiDraftService,
                agentTraceStore,
                new TeachingHandoutTemplateService(),
                Optional.empty(),
                Runnable::run);
        this.returnCompletedWhenExecutorIsSynchronous = true;
    }

    /**
     * 提交教学任务；异步执行 DAG，立即返回 CREATED 状态。
     * 同一主体同一 clientRequestId 重复提交时直接返回已有任务。
     * 任务通过 multiAgentWritingTaskExecutor 在后台执行，前端轮询 GET /api/teaching/tasks/{taskId} 获取最终结果。
     * 生命周期：CREATED → RUNNING → COMPLETED / FAILED。
     */
    public TeachingTaskResponse submit(TeachingTaskRequest request, TeachingRequestContext context) {
        TeachingRequestContext normalizedContext = context.normalize();
        TeachingTaskRequest normalizedRequest = request.normalize();
        String ownerKey = normalizedContext.ownerKey();
        String idempotencyKey = normalizedContext.idempotencyKey(normalizedRequest.clientRequestId());
        Optional<TeachingTaskResponse> existing = taskStore.findByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) {
            return existing.get();
        }
        String taskId = UUID.randomUUID().toString();
        Instant now = Instant.now();
        TeachingTaskResponse created = new TeachingTaskResponse(
                taskId,
                normalizedRequest.clientRequestId(),
                normalizedContext.tenantId(),
                normalizedContext.subjectType(),
                normalizedContext.subjectId(),
                null,
                TeachingTaskStatus.CREATED,
                normalizedRequest.questionText(),
                normalizedRequest.learningGoal(),
                List.of(), List.of(), List.of(),
                "", "", "", List.of(), null, List.of(), null, null);
        taskStore.save(ownerKey, idempotencyKey, created);
        taskExecutor.execute(() -> {
            try {
                TeachingTaskResponse completed = execute(normalizedRequest, normalizedContext, taskId, ownerKey, idempotencyKey);
                taskStore.save(ownerKey, idempotencyKey, completed);
            } catch (Exception executionException) {
                try {
                    TeachingTaskResponse failed = new TeachingTaskResponse(
                            taskId,
                            normalizedRequest.clientRequestId(),
                            normalizedContext.tenantId(),
                            normalizedContext.subjectType(),
                            normalizedContext.subjectId(),
                            null,
                            TeachingTaskStatus.FAILED,
                            normalizedRequest.questionText(),
                            normalizedRequest.learningGoal(),
                            List.of(), List.of(), List.of(),
                            "", "", "", List.of(), null, List.of(), null,
                            executionException.getMessage());
                    taskStore.save(ownerKey, idempotencyKey, failed);
                } catch (Exception saveException) {
                    throw new RuntimeException("Async teaching task execution AND failure persistence both failed", saveException);
                }
            }
        });
        if (returnCompletedWhenExecutorIsSynchronous) {
            return taskStore.findByIdempotencyKey(idempotencyKey).orElse(created);
        }
        return created;
    }

    /**
     * 按 taskId 查询当前主体拥有的教学任务。
     */
    public Optional<TeachingTaskResponse> get(String taskId, TeachingRequestContext context) {
        return taskStore.findByTaskIdAndOwnerKey(taskId, context.normalize().ownerKey());
    }

    /**
     * Lists recent tasks for the current backend session subject.
     */
    public List<TeachingTaskResponse> listRecent(TeachingRequestContext context, int limit) {
        return taskStore.listRecentByOwnerKey(context.normalize().ownerKey(), limit);
    }

    /**
     * 同步执行 DAG 的兼容入口（无 taskId/owner/idempotencyKey，用于测试或非异步场景）。
     */
    private TeachingTaskResponse execute(TeachingTaskRequest request, TeachingRequestContext context) {
        return execute(request, context, UUID.randomUUID().toString(), null, null);
    }

    /**
     * 执行固定 DAG：学习目标识别、资源复用、公开教材检索、ReAct、AI 草稿、LaTeX 讲义、交互建议。
     * 异步路径下先持久化 RUNNING 状态，完成后更新为 COMPLETED，异常时更新为 FAILED。
     *
     * @param taskId 异步任务的 taskId，来自 submit() 中预生成的 UUID
     * @param ownerKey 用于 RUNNING/COMPLETED 状态的持久化
     * @param idempotencyKey 幂等 key，异步完成后更新已有记录
     */
    private TeachingTaskResponse execute(TeachingTaskRequest request, TeachingRequestContext context, String taskId, String ownerKey, String idempotencyKey) {
        StageTimer timer = new StageTimer();
        /* 异步路径下先将任务状态更新为 RUNNING，前端轮询时可见。 */
        if (taskId != null && ownerKey != null && idempotencyKey != null) {
            try {
                TeachingTaskResponse running = new TeachingTaskResponse(
                        taskId,
                        request.clientRequestId(),
                        context.tenantId(),
                        context.subjectType(),
                        context.subjectId(),
                        null,
                        TeachingTaskStatus.RUNNING,
                        request.questionText(),
                        request.learningGoal(),
                        List.of(), List.of(), List.of(),
                        "", "", "", List.of(), null, List.of(), null, null);
                taskStore.save(ownerKey, idempotencyKey, running);
            } catch (Exception ignored) {
            }
        }
        TeachingHandoutTemplateProfile template = handoutTemplateService.resolve(request.handoutTemplateCode());
        StudentMemoryResponse memoryResponse = memoryReuseService.reuse(memoryRequest(request, context));
        timer.mark("memory_reuse");
        List<TeachingEvidence> evidence;
        List<TeachingEvidence> textbookEvidence;
        List<TeachingEvidence> questionEvidence;
        if (memoryResponse.reused()) {
            textbookEvidence = List.of();
            questionEvidence = List.of();
            evidence = List.of();
            timer.mark("reuse_short_circuit");
        } else {
            TextbookSearchResponse retrieval = retrievalService.search(
                    processedBooksRoot,
                    new TextbookSearchRequest(retrievalQuery(request), request.evidenceLimit()),
                    new RetrievalRequestContext(
                            context.tenantId(),
                            context.subjectType(),
                            context.subjectId(),
                            null,
                            context.deviceId(),
                            "teaching-workflow",
                            "/api/teaching/tasks"));
            textbookEvidence = retrieval.hits().stream()
                    .map(this::toEvidence)
                    .toList();
            timer.mark("textbook_retrieval");
            questionEvidence = retrieveQuestionBankEvidence(request, context);
            timer.mark("question_bank_retrieval");
            evidence = concatEvidence(textbookEvidence, questionEvidence);
        }
        List<TeachingReactStep> reactTrace = List.of();
        timer.mark("react_trace");
        TeachingTaskResponse.AiDraft aiDraft = aiDraftService.draft(request, evidence, memoryResponse, template);
        timer.mark("ai_draft");
        List<TeachingWorkflowNode> nodes = buildNodes(request, evidence, questionEvidence, memoryResponse, aiDraft, template, canUseQuestionBank(context));
        String teacherHandoutLatex = appendAiDraft(
                buildTeacherHandoutLatex(request, evidence, memoryResponse, template),
                aiDraft,
                true);
        String studentHandoutLatex = appendAiDraft(
                buildStudentHandoutLatex(request, evidence, memoryResponse, template),
                aiDraft,
                false);
        timer.mark("handout_generation");
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
                TeachingTaskStatus.COMPLETED,
                request.questionText(),
                request.learningGoal(),
                nodes,
                reactTrace,
                evidence,
                teacherHandoutLatex,
                teacherHandoutLatex,
                studentHandoutLatex,
                List.of("继续追问定义 D(x_0)", "生成同类练习题", "把讲义导出为 PDF"),
                toMemoryReuse(memoryResponse),
                timer.timings(),
                aiDraft,
                null);
        if (ownerKey != null && idempotencyKey != null) {
            taskStore.save(ownerKey, idempotencyKey, response);
        }
        saveAiDraftTrace(response, context);
        return response;
    }

    /**
     * 构造学生记忆查询请求；教学任务阶段先使用学习目标作为知识点粗标签，后续会接入知识点识别器。
     */
    /**
     * Appends model-produced teaching content to printable handouts without exposing backend diagnostics.
     */
    private static String appendAiDraft(
            String latex,
            TeachingTaskResponse.AiDraft aiDraft,
            boolean teacherVersion) {
        if (aiDraft == null || !aiDraft.enabled()) {
            return latex;
        }
        if (aiDraft.content() == null || aiDraft.content().isBlank()) {
            return latex;
        }
        String title = teacherVersion ? "教师讲解稿与练习设计" : "课堂练习与作答区";
        if (!aiDraft.structured()) {
            return latex;
        }
        if (teacherVersion) {
            return latex + "\n\\section{" + title + "}\n"
                    + "\\paragraph{讲评主线}\n"
                    + escapeLatex(aiDraft.teacherExplanation())
                    + "\n\\paragraph{知识点与方法卡}"
                    + latexItemize(aiDraft.knowledgePoints())
                    + "\n\\paragraph{课堂追问与变式训练}"
                    + latexEnumerate(aiDraft.followUpQuestions())
                    + "\n\\paragraph{讲评备注}\n"
                    + "用于记录课堂生成问题、学生典型错误和二次讲评安排。\\vspace{5em}\n";
        }
        return latex + "\n\\section{" + title + "}\n"
                + escapeLatex(aiDraft.studentHint())
                + "\n\\paragraph{练习任务}"
                + latexEnumerate(aiDraft.followUpQuestions())
                + "\n\\paragraph{作答区}\n\\vspace{10em}\n";
    }

    /**
     * Persists the CoursewareAgent trace for real AI draft runs so WorkBuddy/MCP and the frontend can recover it.
     */
    private void saveAiDraftTrace(TeachingTaskResponse response, TeachingRequestContext context) {
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
     * Builds a safe trace message without raw model content or the raw student question.
     */
    private static String aiDraftTraceMessage(TeachingTaskResponse.AiDraft aiDraft) {
        String parseState = aiDraft.structured() ? "structured" : "raw";
        return "Teaching AI draft " + parseState
                + "; retry=" + aiDraft.retryCount() + "/" + aiDraft.maxRetries()
                + "; recovered=" + aiDraft.recoveredAfterRetry()
                + "; events=" + (aiDraft.recoveryEvents() == null ? 0 : aiDraft.recoveryEvents().size());
    }

    /**
     * Converts one evidence row to a trace-safe reference id.
     */
    private static String evidenceRef(TeachingEvidence evidence) {
        return evidence.sourceScope() + ":" + evidence.sourceTitle() + ":" + evidence.chunkId();
    }

    private static StudentMemoryCommand memoryRequest(TeachingTaskRequest request, TeachingRequestContext context) {
        return StudentMemoryCommand.fromRequest(
                context.tenantId(),
                context.subjectType(),
                context.subjectId(),
                new StudentMemoryRequest(
                        request.questionText(),
                        null,
                        request.learningGoal(),
                        "private",
                        false));
    }

    /**
     * 把 memory 模块响应转换为 teaching 模块 VO，避免跨模块 VO 直接暴露给前端契约。
     */
    private static TeachingTaskResponse.MemoryReuse toMemoryReuse(StudentMemoryResponse response) {
        return new TeachingTaskResponse.MemoryReuse(
                response.reused(),
                response.memoryId(),
                response.reuseScope(),
                response.answer(),
                response.similarity(),
                response.reason());
    }

    /**
     * 构造教材检索 query，把用户想学什么和题目文本合并，优先复用公开教材证据。
     */
    private static String retrievalQuery(TeachingTaskRequest request) {
        return request.learningGoal() + " " + request.questionText();
    }

    /**
     * 把教材检索命中转换为教学证据，明确标注 PUBLIC_TEXTBOOK 作用域。
     */
    private TeachingEvidence toEvidence(TextbookSearchHit hit) {
        return new TeachingEvidence(
                "PUBLIC_TEXTBOOK",
                hit.bookName() + " / " + hit.sectionTitle(),
                hit.chunkId(),
                hit.pageNo(),
                hit.textSnippet());
    }

    private List<TeachingEvidence> retrieveQuestionBankEvidence(TeachingTaskRequest request, TeachingRequestContext context) {
        if (!canUseQuestionBank(context) || questionBankService == null) {
            return List.of();
        }
        try {
            return questionBankService.searchQuestions(
                            context.tenantId(),
                            context.subjectType(),
                            context.subjectId(),
                            retrievalQuery(request),
                            3)
                    .stream()
                    .map(TeachingWorkflowService::toQuestionEvidence)
                    .toList();
        } catch (IllegalArgumentException exception) {
            return List.of();
        }
    }

    private static TeachingEvidence toQuestionEvidence(QuestionBankItemResponse question) {
        String difficulty = question.difficulty() == null || question.difficulty().isBlank()
                ? "未标难度"
                : question.difficulty();
        String title = question.questionTitle() + " / 难度：" + difficulty;
        String snippet = question.questionText();
        if (question.answerJson() != null && !question.answerJson().isBlank() && !"{}".equals(question.answerJson().strip())) {
            snippet = snippet + "\n答案要点：" + question.answerJson();
        }
        return new TeachingEvidence(
                "QUESTION_BANK",
                title,
                question.questionId(),
                0,
                snippet);
    }

    private static List<TeachingEvidence> concatEvidence(List<TeachingEvidence> first, List<TeachingEvidence> second) {
        List<TeachingEvidence> merged = new ArrayList<>();
        if (first != null) {
            merged.addAll(first);
        }
        if (second != null) {
            merged.addAll(second);
        }
        return List.copyOf(merged);
    }

    /**
     * 构造真实执行过的 DAG 节点输出；未执行的扩展能力不得伪装为 completed。
     */
    private static List<TeachingWorkflowNode> buildNodes(
            TeachingTaskRequest request,
            List<TeachingEvidence> evidence,
            List<TeachingEvidence> questionEvidence,
            StudentMemoryResponse memoryResponse,
            TeachingTaskResponse.AiDraft aiDraft,
            TeachingHandoutTemplateProfile template,
            boolean questionBankAllowed) {
        String reuseSummary = memoryResponse.reused()
                ? "命中学生记忆 %s，作用域 %s，相似度 %.4f，跳过重复教材召回。"
                        .formatted(memoryResponse.memoryId(), memoryResponse.reuseScope(), memoryResponse.similarity())
                : "未命中可复用学生记忆，原因：" + memoryResponse.reason() + "。";
        boolean textbookRetrievalRan = !memoryResponse.reused();
        long publicTextbookCount = evidence.stream()
                .filter(item -> "PUBLIC_TEXTBOOK".equals(item.sourceScope()))
                .count();
        return List.of(
                node("LEARNING_GOAL", "学习目标识别", "识别用户想学：" + request.learningGoal()),
                node("REUSE_RESOURCE", "历史资源复用", reuseSummary),
                node("PUBLIC_TEXTBOOK_RETRIEVAL", "公开教材检索",
                        textbookRetrievalRan ? "completed" : "skipped",
                        textbookRetrievalRan
                                ? "命中公开教材证据 " + publicTextbookCount + " 条。"
                                : "已复用学生记忆，本次未触发公开教材检索。"),
                node("QUESTION_BANK_RETRIEVAL", "题库检索",
                        textbookRetrievalRan && questionBankAllowed ? "completed" : "skipped",
                        textbookRetrievalRan && questionBankAllowed
                                ? "命中题库题目 " + questionEvidence.size() + " 条，已按难度作为讲义证据。"
                                : "当前身份或复用路径未触发题库检索。"),
                node("REACT_SOLVE", "解题编排", "基于教材证据、学生记忆和题型方法整理讲解步骤。"),
                node("HANDOUT_TEMPLATE", "讲义模板", "使用模板：" + template.summary().displayName() + "。"),
                node("AI_DRAFT", "讲义内容生成", aiDraftSummary(aiDraft)),
                node("LATEX_HANDOUT", "讲义排版", "生成教师版和学生版，可预览并导出 PDF。"),
                node("HUMAN_FEEDBACK", "人类反馈", "pending", "等待学生或教师提交人工反馈。"),
                node("INTERACTIVE_FOLLOW_UP", "交互追问", "给出继续追问、练习和导出建议。"));
    }

    /**
     * Summarizes the real AI draft result for the DAG node without exposing raw model content.
     */
    private static String aiDraftSummary(TeachingTaskResponse.AiDraft aiDraft) {
        if (aiDraft == null || !aiDraft.enabled()) {
            return "未生成模型内容，讲义仅使用教材、题库和模板内容。";
        }
        String parseState = aiDraft.structured() ? "结构化解析成功" : "结构化解析失败";
        return "%s，当前模型 %s/%s，重试 %d/%d，诊断事件 %d 条。".formatted(
                parseState,
                aiDraft.providerName(),
                aiDraft.modelCode(),
                aiDraft.retryCount(),
                aiDraft.maxRetries(),
                aiDraft.recoveryEvents() == null ? 0 : aiDraft.recoveryEvents().size());
    }

    /**
     * 创建已完成 DAG 节点。
     */
    private static TeachingWorkflowNode node(String code, String name, String summary) {
        return new TeachingWorkflowNode(code, name, "completed", summary);
    }

    private static TeachingWorkflowNode node(String code, String name, String status, String summary) {
        return new TeachingWorkflowNode(code, name, status, summary);
    }

    /**
     * Teaching task responses must not fabricate ReAct traces before the backend owns a real tool-execution trace.
     */
    private static List<TeachingReactStep> buildReactTrace(
            TeachingTaskRequest request,
            List<TeachingEvidence> evidence,
            StudentMemoryResponse memoryResponse) {
        return List.of();
    }

    /**
     * 生成 LaTeX 讲义草稿；当前阶段输出结构，后续会接入更强的排版和 PDF 渲染。
     */
    private static String buildTeacherHandoutLatex(
            TeachingTaskRequest request,
            List<TeachingEvidence> evidence,
            StudentMemoryResponse memoryResponse,
            TeachingHandoutTemplateProfile template) {
        String evidenceSnippet = memoryResponse.reused()
                ? "复用学生记忆：" + escapeLatex(memoryResponse.answer())
                : evidence.isEmpty() ? "暂无教材证据。" : evidenceSummary(evidence);
        String templateLine = escapeLatex(template.summary().displayName() + " / " + template.summary().description());
        String difficultyLine = escapeLatex(difficultyBands(template));
        String questionSection = safeQuestionText(request).isBlank()
                ? "围绕学习目标设计例题、变式题和课堂追问。"
                : safeQuestionText(request);
        return """
                \\section{讲义信息}
                \\begin{itemize}
                \\item 模板：%s
                \\item 难度：%s
                \\item 使用场景：教师备课、课堂讲评、课后订正。
                \\end{itemize}

                \\section{学习目标}
                %s

                \\section{本讲任务}
                %s

                \\section{来源索引}
                %s

                \\section{知识点归属}
                %s

                \\section{板书与讲评主线}
                \\begin{enumerate}
                \\item 定位：先写本讲核心定义、公式或图像特征，让学生知道从哪里入手。
                \\item 识别：圈出题目条件中的题型信号，判断使用定义法、代数计算、数形结合还是分类讨论。
                \\item 推进：每一步板书都说明依据，遇到参数、范围或符号先处理边界。
                \\item 收束：给出答案、评分点、易错提醒和可继续追问的变式。
                \\end{enumerate}
                
                \\section{例题讲评}
                \\paragraph{例题}
                %s
                
                \\paragraph{讲解路径}
                先提取条件，再写对应知识点和公式；若有参数或范围条件，单独讨论边界。
                
                \\paragraph{答案与评分点}
                教师版保留完整答案、关键等式、评分点和学生常见失分位置。
                
                \\section{课堂追问预设}
                \\begin{itemize}
                \\item 这道题第一步为什么不能直接套公式？
                \\item 如果条件少一个，应该先补哪个量？
                \\item 学生最容易在定义域、符号、参数范围还是计算细节上出错？
                \\end{itemize}
                
                \\section{课后订正记录}
                \\vspace{5em}
                """.formatted(
                templateLine,
                difficultyLine,
                escapeLatex(request.learningGoal()),
                escapeLatex(questionSection),
                evidenceSnippet,
                teacherKnowledgePoint(request, evidence),
                escapeLatex(questionSection));
    }

    private static boolean canUseQuestionBank(TeachingRequestContext context) {
        String subjectType = context == null ? "" : context.subjectType();
        return "teacher".equalsIgnoreCase(subjectType) || "admin".equalsIgnoreCase(subjectType);
    }

    /**
     * Builds a compact evidence summary instead of dumping raw OCR chunks into the handout.
     */
    private static String evidenceSummary(List<TeachingEvidence> evidence) {
        if (evidence.isEmpty()) {
            return "暂无教材证据。";
        }
        return evidence.stream()
                .limit(3)
                .map(item -> escapeLatex(evidenceLabel(item) + "：" + TeachingEvidenceSnippetSanitizer.sanitizeCompact(item.snippet())))
                .collect(java.util.stream.Collectors.joining("\n\n"));
    }

    private static String evidenceLabel(TeachingEvidence item) {
        if ("QUESTION_BANK".equals(item.sourceScope()) || item.pageNo() <= 0) {
            return "题库：" + item.sourceTitle();
        }
        return item.sourceTitle() + " / PDF " + item.pageNo();
    }

    private static String compactEvidenceSnippet(String snippet) {
        if (snippet == null || snippet.isBlank()) {
            return "已命中资料片段。";
        }
        String cleaned = snippet
                .replace("\r", "\n")
                .replaceAll("!\\[[^\\]]*]\\([^)]*\\)", " ")
                .replaceAll("(?m)^#.*$", " ")
                .replaceAll("(?m)^##\\s*正文.*$", " ")
                .replaceAll("(?m)^-\\s*(书名|章节|PDF页码|印刷页码|页图).*?$", " ")
                .replace("$$", " ")
                .replace("###", " ")
                .replaceAll("\\s+", " ")
                .strip();
        return cleaned.length() <= 120 ? cleaned : cleaned.substring(0, 120) + "...";
    }

    /**
     * 生成学生版 LaTeX 讲义：保留题目、提示和空白作答区，不直接暴露教师解析和知识点归属。
     */
    private static String buildStudentHandoutLatex(
            TeachingTaskRequest request,
            List<TeachingEvidence> evidence,
            StudentMemoryResponse memoryResponse,
            TeachingHandoutTemplateProfile template) {
        String hint = memoryResponse.reused()
                ? "回忆同类问题的方法，先写出已知条件，再判断可用公式。"
                : evidence.isEmpty()
                ? "先圈出题目中的关键词，再尝试写出相关定义。"
                : "先阅读教材证据中的定义或公式，再补全自己的推理。";
        if (template.studentLectureStyle()) {
            String questionText = safeQuestionText(request).isBlank()
                    ? "根据本节主题完成下面的知识梳理与分层练习。"
                    : safeQuestionText(request);
            return """
                    \\section{学习主题}
                    \\begin{itemize}
                    \\item 学习主题：%s
                    \\item 课堂任务：先完成例题任务，再做分层练习，最后记录错因。
                    \\end{itemize}

                    \\subsection*{知识点速记}
                    %s

                    \\subsection*{注意}
                    %s

                    \\subsection*{例题任务}
                    %s

                    \\subsection*{分层练习}
                    \\begin{enumerate}
                    \\item A 基础：写出本讲涉及的定义、公式或图像特征。
                    \\item B 提高：根据题目条件列出关键等式，并说明每一步依据。
                    \\item C 挑战：改变一个条件后，判断方法是否还成立。
                    \\end{enumerate}

                    \\subsection*{课堂作答区}
                    \\vspace{10em}
                    
                    \\subsection*{订正与错因}
                    \\vspace{6em}
                    """.formatted(
                    escapeLatex(request.learningGoal()),
                    escapeLatex(hint),
                    escapeLatex("先核对定义域、符号条件和参数不为 0 等边界。"),
                    escapeLatex(questionText));
        }
        String questionText = safeQuestionText(request).isBlank()
                ? "根据本讲主题完成例题、变式和订正。"
                : safeQuestionText(request);
        return """
                \\section{学习主题}
                \\begin{itemize}
                \\item 主题：%s
                \\item 课堂任务：先独立完成空白区，再订正关键步骤。
                \\end{itemize}

                \\section{例题任务}
                %s

                \\section{思路提示}
                %s
                
                \\section{知识点速记}
                \\begin{itemize}
                \\item 先写定义、公式或图像特征，再代入题目条件。
                \\item 遇到参数、范围、符号时先标记边界，不急着计算。
                \\item 本页不展示答案，完整解析在教师版审查。
                \\end{itemize}
                
                \\section{课堂练习}
                \\begin{enumerate}
                \\item A 基础：复述本题对应的核心知识点。
                \\item B 提高：写出第一步等式或图形关系。
                \\item C 挑战：说明如果条件变化，方法需要怎样调整。
                \\end{enumerate}

                \\section{我的解答}
                \\vspace{10em}

                \\section{订正记录}
                \\vspace{6em}
                """.formatted(
                escapeLatex(request.learningGoal()),
                escapeLatex(questionText),
                escapeLatex(hint));
    }

    /**
     * Builds a compact teacher-facing knowledge point label from the learning goal and top evidence.
     */
    private static String teacherKnowledgePoint(TeachingTaskRequest request, List<TeachingEvidence> evidence) {
        String source = evidence.isEmpty() ? "当前未命中公开教材或教师私有资料" : evidence.getFirst().sourceTitle();
        return escapeLatex(request.learningGoal() + "；来源：" + source);
    }

    /**
     * 最小 LaTeX 转义，避免用户输入中的特殊字符破坏讲义结构。
     */
    private static String escapeLatex(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        StringBuilder segment = new StringBuilder();
        boolean math = false;
        for (int index = 0; index < value.length(); index += 1) {
            char character = value.charAt(index);
            if (character == '$') {
                builder.append(math ? sanitizeMathSegment(segment.toString()) : escapeLatexText(segment.toString()));
                segment.setLength(0);
                builder.append('$');
                math = !math;
            } else {
                segment.append(character);
            }
        }
        builder.append(math ? sanitizeMathSegment(segment.toString()) : escapeLatexText(segment.toString()));
        return builder.toString();
    }

    private static String escapeLatexText(String value) {
        return value
                .replace("\\", "\\textbackslash{}")
                .replace("&", "\\&")
                .replace("%", "\\%")
                .replace("#", "\\#")
                .replace("_", "\\_")
                .replace("{", "\\{")
                .replace("}", "\\}")
                .replace("^", "\\textasciicircum{}")
                .replace("~", "\\textasciitilde{}");
    }

    private static String sanitizeMathSegment(String value) {
        return value
                .replace("\\textbackslash{}frac", "\\frac")
                .replace("\\textbackslash{}sqrt", "\\sqrt")
                .replace("\\textbackslash{}sin", "\\sin")
                .replace("\\textbackslash{}cos", "\\cos")
                .replace("\\textbackslash{}tan", "\\tan")
                .replace("\\textbackslash{}ln", "\\ln")
                .replace("\\textbackslash{}log", "\\log")
                .replace("\\textbackslash{}pi", "\\pi")
                .replace("\\textbackslash{}theta", "\\theta")
                .replace("\\textbackslash{}alpha", "\\alpha")
                .replace("\\textbackslash{}beta", "\\beta")
                .replace("\\textbackslash{}gamma", "\\gamma")
                .replace("\\textbackslash{}Delta", "\\Delta")
                .replace("\\textbackslash{}infty", "\\infty")
                .replace("\\textbackslash{}leq", "\\leq")
                .replace("\\textbackslash{}geq", "\\geq")
                .replace("\\textbackslash{}neq", "\\neq")
                .replace("\\textbackslash{}cdot", "\\cdot")
                .replace("\\textbackslash{}times", "\\times")
                .replace("\\textbackslash{}to", "\\to");
    }

    private static String safeQuestionText(TeachingTaskRequest request) {
        return request.questionText() == null ? "" : request.questionText().strip();
    }

    private static String latexItemize(List<String> items) {
        if (items == null || items.isEmpty()) {
            return "\n";
        }
        StringBuilder builder = new StringBuilder("\n\\begin{itemize}\n");
        for (String item : items) {
            builder.append("\\item ").append(escapeLatex(item)).append('\n');
        }
        return builder.append("\\end{itemize}\n").toString();
    }

    private static String latexEnumerate(List<String> items) {
        if (items == null || items.isEmpty()) {
            return "\n";
        }
        StringBuilder builder = new StringBuilder("\n\\begin{enumerate}\n");
        for (String item : items) {
            builder.append("\\item ").append(escapeLatex(item)).append('\n');
        }
        return builder.append("\\end{enumerate}\n").toString();
    }

    private static String difficultyBands(TeachingHandoutTemplateProfile template) {
        List<String> bands = template.summary().difficultyBands();
        if (bands == null || bands.isEmpty()) {
            return "基础、提高";
        }
        return String.join("、", bands);
    }

    /**
     * 教学任务阶段计时器；只记录阶段耗时，不保存业务内容，避免日志泄露学生题目。
     */
    private static final class StageTimer {

        private final List<TeachingTaskResponse.StageTiming> timings = new ArrayList<>();
        private long lastNanos = System.nanoTime();

        /**
         * 记录一个阶段相对上个检查点的耗时。
         */
        void mark(String stage) {
            long now = System.nanoTime();
            timings.add(new TeachingTaskResponse.StageTiming(stage, Math.max(0L, (now - lastNanos) / 1_000_000L)));
            lastNanos = now;
        }

        /**
         * 返回不可变耗时列表，保证任务保存后不会被后续流程修改。
         */
        List<TeachingTaskResponse.StageTiming> timings() {
            return List.copyOf(timings);
        }
    }
}
