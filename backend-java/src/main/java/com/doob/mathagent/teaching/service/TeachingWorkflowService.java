package com.doob.mathagent.teaching.service;

import com.doob.mathagent.agent.service.AgentTraceRecord;
import com.doob.mathagent.agent.service.AgentTraceStore;
import com.doob.mathagent.agent.vo.AgentRunExecuteResponse;
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
import com.doob.mathagent.teaching.TeachingEvidence;
import com.doob.mathagent.teaching.TeachingReactStep;
import com.doob.mathagent.teaching.TeachingRequestContext;
import com.doob.mathagent.teaching.TeachingTaskStatus;
import com.doob.mathagent.teaching.TeachingWorkflowNode;
import com.doob.mathagent.teaching.dto.TeachingTaskRequest;
import com.doob.mathagent.teaching.vo.TeachingTaskResponse;
import com.doob.mathagent.teacher.service.TeacherResourceBlockSearchService;
import com.doob.mathagent.teacher.vo.TeacherResourceBlockSearchResponse;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;

/**
 * 教学任务 DAG 编排服务：把用户学习目标、资源复用、教材检索、ReAct 解题和讲义生成串成可恢复任务。
 */
@Service
public class TeachingWorkflowService {

    private static final Pattern DRAFT_ORDERED_LINE = Pattern.compile("^\\s*(?:\\d+|[一二三四五六七八九十]+)[.、)]\\s+(.+)$");
    private static final Pattern DRAFT_BULLET_LINE = Pattern.compile("^\\s*[-•·]\\s+(.+)$");
    private static final Pattern BLANK_PLACEHOLDER = Pattern.compile("_{3,}|＿{3,}");
    private static final Pattern LATEX_HEADING_LINE = Pattern.compile("^\\\\(section\\*?|subsection\\*?|subsubsection\\*?|paragraph\\*?)\\{(.+)}\\s*$");
    private static final Pattern INTERNAL_HANDOUT_LINE = Pattern.compile(
            "(?mi)^.*(?:MODEL_CALL|JSON_PARSE|\\btokens?\\b|模型健康|model health|debug|调试|JSON|PDF\\s*(?:规则|排版|版式)|PDF\\s*版式|排版说明|版式要求|页眉|页脚|颜色|讲评色|练习色|渲染引擎|模板规则|页边距|虚线折叠|documentclass|usepackage|fancyhdr|pagestyle|begin\\{document}|end\\{document}|作为\\s*AI|as an AI|本页只保留|课堂任务|本讲任务|讲后自查|教师审校清单|横版讲解提纲|AI 知识定位|模板偏向|本讲更偏向).*$");
    private static final Pattern STUDENT_FORBIDDEN_SECTION = Pattern.compile(
            "【(?:答案与评分点|参考答案|参考解析|评分标准|例题详解|完整解析|教师讲解|讲评主线|教师备注|板书设计)】[\\s\\S]*?(?=【|$)");
    private static final Pattern STUDENT_FORBIDDEN_LINE = Pattern.compile(
            "(?m)^.*(?:答案[：:]|答案为|参考答案|参考解析|评分点|评分标准|完整解析|解答如下|解：|因此答案为|故答案为|教师讲解|讲评主线|板书设计).*$");
    private static final Pattern VISIBLE_WORKSPACE_LABEL = Pattern.compile(
            "(?:课堂作答区|作答区|我的解答|推导区|手写区|教师手写区|留白区|空白区|板书区|教师板书区)\\s*[：:]?");
    private static final Pattern VISIBLE_WORKSPACE_REFERENCE = Pattern.compile(
            "(?:写在|填写在|完成在|放在|留在)(?:课堂作答区|作答区|我的解答|推导区|手写区|教师手写区|留白区|空白区|板书区|教师板书区)");
    private static final Pattern TOPIC_NOISE_WORD = Pattern.compile(
            "(?:请|生成|一份|关于|围绕|针对|包含|以及|并|和|与|及|从|到|开始|讲解|讲义|学习|学会|理解|掌握|做题|大题|小题|题型|例题|易错点|方法|流程|专题|训练|教师版|学生版|教师|学生|课堂|作答|补充要求|要求|目标|主题|知识点|基础|提高|综合|定义|图像|性质|题目|问题|讲清|讲透|入门|复习|巩固|提升|中的|中|的)");
    private static final Set<String> TOPIC_GENERIC_TERMS = Set.of(
            "数学",
            "高中数学",
            "函数",
            "题目",
            "问题",
            "讲解",
            "讲义",
            "知识点");
    private static final Set<String> CORE_TOPIC_PREFERENCES = Set.of(
            "函数", "导数", "双曲线", "椭圆", "抛物线", "圆锥曲线", "数列", "概率", "统计",
            "三角函数", "向量", "空间向量", "立体几何", "直线", "圆", "排列组合", "二项式");

    private final Path processedBooksRoot;
    private final TextbookRetrievalService retrievalService;
    private final TeachingTaskStore taskStore;
    private final StudentMemoryReuseService memoryReuseService;
    private final TeachingAiDraftService aiDraftService;
    private final AgentTraceStore agentTraceStore;
    private final TeachingHandoutTemplateService handoutTemplateService;
    private final KnowledgeQuestionBankService questionBankService;
    private final TeacherResourceBlockSearchService teacherResourceBlockSearchService;
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
            Optional<TeacherResourceBlockSearchService> teacherResourceBlockSearchService,
            @Qualifier("multiAgentWritingTaskExecutor") TaskExecutor taskExecutor) {
        this.processedBooksRoot = processedBooksRoot.toAbsolutePath().normalize();
        this.retrievalService = retrievalService;
        this.taskStore = taskStore;
        this.memoryReuseService = memoryReuseService;
        this.aiDraftService = aiDraftService;
        this.agentTraceStore = agentTraceStore;
        this.handoutTemplateService = handoutTemplateService;
        this.questionBankService = questionBankService.orElse(null);
        this.teacherResourceBlockSearchService = teacherResourceBlockSearchService.orElse(null);
        this.taskExecutor = taskExecutor;
        this.returnCompletedWhenExecutorIsSynchronous = false;
    }

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
        this(
                processedBooksRoot,
                retrievalService,
                taskStore,
                memoryReuseService,
                aiDraftService,
                agentTraceStore,
                handoutTemplateService,
                questionBankService,
                Optional.empty(),
                taskExecutor);
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
        // 历史区只展示可继续审查/预览的任务。旧脏数据会把前端历史和讲义预览直接污染掉。
        return taskStore.listRecentByOwnerKey(context.normalize().ownerKey(), Math.max(limit * 3, limit)).stream()
                .filter(TeachingWorkflowService::isFrontendDisplayableTask)
                .limit(limit)
                .toList();
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
        List<TeachingEvidence> teacherResourceEvidence;
        if (memoryResponse.reused()) {
            textbookEvidence = List.of();
            questionEvidence = List.of();
            teacherResourceEvidence = List.of();
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
            textbookEvidence = alignEvidenceToTopic(request, textbookEvidence);
            timer.mark("textbook_retrieval");
            questionEvidence = retrieveQuestionBankEvidence(request, context);
            questionEvidence = alignEvidenceToTopic(request, questionEvidence);
            timer.mark("question_bank_retrieval");
            teacherResourceEvidence = retrieveTeacherResourceEvidence(request, context);
            teacherResourceEvidence = alignEvidenceToTopic(request, teacherResourceEvidence);
            timer.mark("teacher_resource_retrieval");
            evidence = concatEvidence(textbookEvidence, questionEvidence, teacherResourceEvidence);
            if (evidence.isEmpty()) {
                textbookEvidence = List.of();
                questionEvidence = List.of();
                teacherResourceEvidence = List.of();
            }
        }
        List<TeachingReactStep> reactTrace = List.of();
        timer.mark("react_trace");
        TeachingTaskResponse.AiDraft aiDraft = aiDraftService.draft(request, evidence, memoryResponse, template);
        timer.mark("ai_draft");
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
        String teacherHandoutLatex = buildTeacherHandoutLatex(request, evidence, memoryResponse, template, aiDraft);
        String studentHandoutLatex = buildStudentHandoutLatex(request, evidence, memoryResponse, template, aiDraft);
        teacherHandoutLatex = guardHandoutLatex(teacherHandoutLatex, true);
        studentHandoutLatex = guardHandoutLatex(studentHandoutLatex, false);
        String lectureHandoutLatex = buildLectureHandoutLatex(teacherHandoutLatex, request);
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
                lectureHandoutLatex,
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
     * Final backend guard before storage/export. It keeps printable handout content only.
     */
    private static String guardHandoutLatex(String latex, boolean teacherVersion) {
        if (latex == null || latex.isBlank()) {
            return "";
        }
        String guarded = INTERNAL_HANDOUT_LINE.matcher(latex).replaceAll("");
        if (!teacherVersion) {
            guarded = STUDENT_FORBIDDEN_SECTION.matcher(guarded).replaceAll("");
            guarded = STUDENT_FORBIDDEN_LINE.matcher(guarded).replaceAll("");
            guarded = guarded
                    .replaceAll("(?m)^\\\\(?:section|subsection|subsubsection|paragraph)\\*?\\{(?:答案与评分点|参考答案|参考解析|评分标准|例题详解|完整解析|教师讲解|讲评主线|教师备注|板书设计|课堂作答区|作答区|我的解答|推导区|手写区|教师手写区|留白区|空白区)}\\s*$", "");
            guarded = removeVisibleWorkspaceLabels(guarded);
        }
        return removeEmptyTitledBlocks(guarded)
                .replaceAll("(?m)^\\s*\\n", "\n")
                .replaceAll("\\n{3,}", "\n\n")
                .strip();
    }

    private static String removeVisibleWorkspaceLabels(String value) {
        String withoutReferences = VISIBLE_WORKSPACE_REFERENCE.matcher(value).replaceAll("独立完成");
        return VISIBLE_WORKSPACE_LABEL.matcher(withoutReferences).replaceAll("");
    }

    /**
     * Extracts the teacher-only 16:10 lecture card into an independent exportable handout version.
     * This keeps PPT/lecture review separate from the full teacher solution without requiring a schema migration.
     */
    private static String buildLectureHandoutLatex(String guardedTeacherLatex, TeachingTaskRequest request) {
        String section = extractLatexSection(guardedTeacherLatex, "16:10 横版讲解卡");
        StringBuilder builder = new StringBuilder();
        builder.append("\\section{16:10 横版讲解卡}\n");
        if (section.isBlank()) {
            String topic = request.learningGoal() == null || request.learningGoal().isBlank()
                    ? request.questionText()
                    : request.learningGoal();
            builder.append("\\paragraph{课堂投屏}\n")
                    .append(escapeLatex(topic == null || topic.isBlank() ? "讲义主题未填写" : topic))
                    .append("\n\n")
                    .append("\\vspace{8em}\n");
        } else {
            builder.append(section).append('\n');
        }
        builder.append("\\vspace{10em}\n");
        return guardHandoutLatex(builder.toString(), true);
    }

    private static String extractLatexSection(String latex, String sectionTitle) {
        if (latex == null || latex.isBlank() || sectionTitle == null || sectionTitle.isBlank()) {
            return "";
        }
        String[] lines = latex.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        StringBuilder body = new StringBuilder();
        boolean capturing = false;
        for (String line : lines) {
            String stripped = line.strip();
            Matcher heading = LATEX_HEADING_LINE.matcher(stripped);
            if (heading.matches() && "section".equals(heading.group(1).replace("*", ""))) {
                String title = heading.group(2).strip();
                if (capturing) {
                    break;
                }
                capturing = title.equals(sectionTitle);
                continue;
            }
            if (capturing) {
                body.append(line).append('\n');
            }
        }
        return body.toString().strip();
    }

    private static String removeEmptyTitledBlocks(String latex) {
        if (latex == null || latex.isBlank()) {
            return "";
        }
        String[] lines = latex.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        return renderNonEmptyTitleRange(lines, 0, lines.length).strip();
    }

    private static String renderNonEmptyTitleRange(String[] lines, int start, int end) {
        StringBuilder builder = new StringBuilder();
        int index = start;
        while (index < end) {
            Matcher heading = LATEX_HEADING_LINE.matcher(lines[index].strip());
            if (!heading.matches()) {
                if (!isBlankWorkspaceLabelLine(lines[index])) {
                    builder.append(lines[index]).append('\n');
                }
                index += 1;
                continue;
            }
            int level = latexHeadingLevel(heading.group(1));
            int next = index + 1;
            while (next < end) {
                Matcher nextHeading = LATEX_HEADING_LINE.matcher(lines[next].strip());
                if (nextHeading.matches() && latexHeadingLevel(nextHeading.group(1)) <= level) {
                    break;
                }
                next += 1;
            }
            String body = renderNonEmptyTitleRange(lines, index + 1, next).strip();
            if (hasRealLatexContent(body)) {
                builder.append(lines[index].strip()).append('\n').append(body).append("\n\n");
            }
            index = next;
        }
        return builder.toString();
    }

    private static int latexHeadingLevel(String command) {
        String normalized = command == null ? "" : command.replace("*", "");
        return switch (normalized) {
            case "section" -> 1;
            case "subsection" -> 2;
            case "subsubsection", "paragraph" -> 3;
            default -> 4;
        };
    }

    private static boolean hasRealLatexContent(String body) {
        if (body == null || body.isBlank()) {
            return false;
        }
        for (String rawLine : body.replace("\r\n", "\n").replace('\r', '\n').split("\n")) {
            if (!isBlankOnlyLatexLine(rawLine)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isBlankWorkspaceLabelLine(String line) {
        String text = line == null ? "" : line.strip();
        if (text.isBlank()) {
            return false;
        }
        String compact = text
                .replaceAll("[_＿\\s:：，。,.;；、-]+", "")
                .strip();
        return Set.of("留白区", "留白", "手写区", "教师手写区", "板书留白", "板书区", "教师板书区").contains(compact);
    }

    private static boolean isBlankOnlyLatexLine(String line) {
        String text = line == null ? "" : line.strip();
        if (text.isBlank()) {
            return true;
        }
        if (text.matches("^\\\\vspace\\{[0-9.]+em}\\s*$")
                || text.matches("^\\\\(?:smallskip|medskip|bigskip|par)\\s*$")
                || text.matches("^\\\\underline\\{\\\\hspace\\{[0-9.]+em}}\\s*$")
                || text.matches("^\\\\(?:begin|end)\\{(?:itemize|enumerate|center)}\\s*$")) {
            return true;
        }
        String compact = text
                .replaceAll("\\\\vspace\\{[^}]+}", "")
                .replaceAll("\\\\underline\\{\\\\hspace\\{[^}]+}}", "")
                .replaceAll("\\\\hspace\\{[^}]+}", "")
                .replaceAll("\\\\par", "")
                .replaceAll("[_＿\\s:：，。,.;；、-]+", "")
                .strip();
        return compact.isBlank()
                || isBlankWorkspaceLabelLine(text)
                || Set.of("作答", "作答区", "课堂作答区", "我的解答", "解答", "推导区", "订正", "订正记录",
                        "错因", "错因记录", "订正与错因", "空白区", "留白区", "留白", "手写区",
                        "教师手写区", "板书留白", "板书区", "教师板书区").contains(compact);
    }

    private static String guardDraftText(String value, boolean teacherVersion) {
        String guarded = guardHandoutLatex(value, teacherVersion);
        if (!teacherVersion && guarded.isBlank()) {
            return "";
        }
        return guarded;
    }

    private static List<String> guardDraftItems(List<String> values, boolean teacherVersion) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        List<String> guarded = new ArrayList<>();
        for (String value : values) {
            String item = guardHandoutLatex(value, teacherVersion)
                    .replaceAll("\\s+", " ")
                    .strip();
            if (!item.isBlank()) {
                guarded.add(item);
            }
        }
        return List.copyOf(guarded);
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
        List<String> keywords = topicKeywords(request);
        String coreTopic = primaryTopicKeyword(request);
        StringBuilder builder = new StringBuilder();
        if (!coreTopic.isBlank()) {
            builder.append(coreTopic).append(' ');
        }
        if (request.learningGoal() != null && !request.learningGoal().isBlank()) {
            builder.append(request.learningGoal().strip()).append(' ');
        }
        if (request.questionText() != null && !request.questionText().isBlank()) {
            builder.append(request.questionText().strip()).append(' ');
        }
        if (!keywords.isEmpty()) {
            builder.append(String.join(" ", keywords));
        }
        return builder.toString().replaceAll("\\s+", " ").strip();
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
        Map<String, QuestionBankItemResponse> matchedQuestions = new LinkedHashMap<>();
        List<String> alignedQueries = alignedQueries(request);
        try {
            for (String query : alignedQueries) {
                for (QuestionBankItemResponse question : questionBankService.searchQuestions(
                        context.tenantId(),
                        context.subjectType(),
                        context.subjectId(),
                        query,
                        6)) {
                    matchedQuestions.putIfAbsent(question.questionId(), question);
                    if (matchedQuestions.size() >= 6) {
                        break;
                    }
                }
                if (matchedQuestions.size() >= 6) {
                    break;
                }
            }
            return matchedQuestions.values().stream()
                    .sorted(Comparator.comparingInt(TeachingWorkflowService::questionDifficultyRank))
                    .limit(3)
                    .map(TeachingWorkflowService::toQuestionEvidence)
                    .toList();
        } catch (IllegalArgumentException exception) {
            return List.of();
        }
    }

    private List<TeachingEvidence> retrieveTeacherResourceEvidence(TeachingTaskRequest request, TeachingRequestContext context) {
        if (!canUseTeacherResources(context) || teacherResourceBlockSearchService == null) {
            return List.of();
        }
        Map<String, TeacherResourceBlockSearchResponse.Hit> matchedBlocks = new LinkedHashMap<>();
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
                    if (matchedBlocks.size() >= 6) {
                        break;
                    }
                }
                if (matchedBlocks.size() >= 6) {
                    break;
                }
            }
            return matchedBlocks.values().stream()
                    .sorted(Comparator.comparingDouble(TeacherResourceBlockSearchResponse.Hit::score).reversed()
                            .thenComparing(TeacherResourceBlockSearchResponse.Hit::documentTitle)
                            .thenComparingInt(TeacherResourceBlockSearchResponse.Hit::blockOrder))
                    .limit(3)
                    .map(TeachingWorkflowService::toTeacherResourceEvidence)
                    .toList();
        } catch (IllegalArgumentException exception) {
            return List.of();
        }
    }

    private static int questionDifficultyRank(QuestionBankItemResponse item) {
        String difficulty = item.difficulty() == null ? "" : item.difficulty();
        if (difficulty.contains("基础") || difficulty.equalsIgnoreCase("easy")) {
            return 0;
        }
        if (difficulty.contains("提高") || difficulty.contains("中等") || difficulty.equalsIgnoreCase("medium")) {
            return 1;
        }
        if (difficulty.contains("压轴") || difficulty.contains("困难") || difficulty.equalsIgnoreCase("hard")) {
            return 2;
        }
        return 3;
    }

    private static TeachingEvidence toQuestionEvidence(QuestionBankItemResponse question) {
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
        return new TeachingEvidence(
                "QUESTION_BANK",
                title,
                question.questionId(),
                0,
                snippet);
    }

    private static TeachingEvidence toTeacherResourceEvidence(TeacherResourceBlockSearchResponse.Hit hit) {
        return new TeachingEvidence(
                "TEACHER_RESOURCE",
                teacherResourceSourceTitle(hit),
                hit.blockId(),
                hit.pageNo() == null ? 0 : hit.pageNo(),
                hit.snippet());
    }

    private static String teacherResourceSourceTitle(TeacherResourceBlockSearchResponse.Hit hit) {
        StringBuilder builder = new StringBuilder(hit.documentTitle() == null || hit.documentTitle().isBlank()
                ? "教师资料"
                : hit.documentTitle().strip());
        if (hit.chapter() != null && !hit.chapter().isBlank()) {
            builder.append(" / ").append(hit.chapter().strip());
        }
        if (hit.section() != null && !hit.section().isBlank()
                && !hit.section().strip().equals(hit.chapter() == null ? "" : hit.chapter().strip())) {
            builder.append(" / ").append(hit.section().strip());
        }
        return builder.toString();
    }

    private static List<TeachingEvidence> concatEvidence(List<TeachingEvidence>... groups) {
        List<TeachingEvidence> merged = new ArrayList<>();
        for (List<TeachingEvidence> group : groups) {
            if (group != null) {
                merged.addAll(group);
            }
        }
        return List.copyOf(merged);
    }

    private static List<TeachingEvidence> alignEvidenceToTopic(TeachingTaskRequest request, List<TeachingEvidence> evidence) {
        if (evidence == null || evidence.isEmpty()) {
            return List.of();
        }
        List<String> keywords = topicKeywords(request);
        if (keywords.isEmpty()) {
            return evidence;
        }
        String primary = primaryTopicKeyword(request);
        int threshold = primary.length() >= 3
                ? primary.length()
                : Math.min(4, keywords.stream().mapToInt(String::length).max().orElse(2));
        List<TeachingEvidence> aligned = evidence.stream()
                .filter(item -> topicMatchScore(item, keywords) >= threshold)
                .toList();
        if (!aligned.isEmpty()) {
            return aligned;
        }
        if (hasLocalTeachingResource(evidence)) {
            List<String> expandedKeywords = localResourceTopicKeywords(request);
            if (!expandedKeywords.isEmpty()) {
                int expandedThreshold = Math.min(4,
                        expandedKeywords.stream().mapToInt(String::length).max().orElse(2));
                List<TeachingEvidence> expandedAligned = evidence.stream()
                        .filter(item -> topicMatchScore(item, expandedKeywords) >= expandedThreshold)
                        .toList();
                if (!expandedAligned.isEmpty()) {
                    return expandedAligned;
                }
            }
        }
        if (primary.isBlank()) {
            return List.of();
        }
        return evidence.stream()
                .filter(item -> compactEvidenceText(item).contains(primary.toLowerCase()))
                .toList();
    }

    private static boolean hasLocalTeachingResource(List<TeachingEvidence> evidence) {
        return evidence.stream().anyMatch(item -> !"PUBLIC_TEXTBOOK".equals(item.sourceScope()));
    }

    private static List<String> localResourceTopicKeywords(TeachingTaskRequest request) {
        LinkedHashSet<String> keywords = new LinkedHashSet<>(topicKeywords(request));
        for (String candidate : QuestionBankSearchText.candidateQueries(request.learningGoal(), request.questionText())) {
            if (candidate.length() >= 2 && candidate.length() <= 12 && !TOPIC_GENERIC_TERMS.contains(candidate)) {
                keywords.add(candidate);
            }
        }
        return keywords.stream()
                .sorted(Comparator
                        .comparingInt((String keyword) -> CORE_TOPIC_PREFERENCES.contains(keyword) ? 0 : 1)
                        .thenComparing(Comparator.comparingInt(String::length).reversed()))
                .limit(12)
                .toList();
    }

    static boolean isFrontendDisplayableTask(TeachingTaskResponse task) {
        if (task == null || task.taskId() == null || task.taskId().isBlank()) {
            return false;
        }
        if (task.status() != TeachingTaskStatus.COMPLETED) {
            return false;
        }
        String title = safeFrontendText(task.learningGoal(), task.questionText());
        if (title.isBlank() || looksCorruptedText(title)) {
            return false;
        }
        String combined = safeFrontendText(
                title,
                task.handoutLatex(),
                task.teacherHandoutLatex(),
                task.studentHandoutLatex());
        if (containsProtocolOrDebugLeak(combined)) {
            return false;
        }
        String teacherDraft = task.teacherHandoutLatex();
        String studentDraft = task.studentHandoutLatex();
        return hasReadableHandoutContent(teacherDraft)
                || hasReadableHandoutContent(studentDraft);
    }

    private static boolean hasReadableHandoutContent(String value) {
        String normalized = safeFrontendText(value);
        if (normalized.length() < 18) {
            return false;
        }
        if (looksCorruptedText(normalized)) {
            return false;
        }
        if (containsProtocolOrDebugLeak(normalized)) {
            return false;
        }
        return true;
    }

    private static boolean containsProtocolOrDebugLeak(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        String lower = value.toLowerCase().replaceAll("[\\s_\\-]+", "");
        return lower.contains("capability")
                || lower.contains("requesthash")
                || lower.contains("idempotencykey")
                || lower.contains("modelcall")
                || lower.contains("jsonparse")
                || lower.contains("apiaccess")
                || lower.contains("subjecttype")
                || lower.contains("bearer")
                || lower.contains("mcp")
                || lower.contains("安全探针")
                || lower.contains("不做题目生成")
                || lower.contains("模型健康")
                || lower.contains("调试信息");
    }

    private static boolean looksCorruptedText(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        String normalized = value.replaceAll("\\s+", "");
        if (normalized.contains("???") || normalized.contains("�")) {
            return true;
        }
        long questionMarks = normalized.chars().filter(ch -> ch == '?').count();
        if (questionMarks >= 3 && questionMarks * 2 >= normalized.length()) {
            return true;
        }
        String lower = normalized.toLowerCase();
        return lower.contains("ã")
                || lower.contains("â")
                || lower.contains("ä¸")
                || lower.contains("å")
                || lower.contains("æ")
                || lower.contains("ç");
    }

    private static String safeFrontendText(String... values) {
        StringBuilder builder = new StringBuilder();
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                if (builder.length() > 0) {
                    builder.append(' ');
                }
                builder.append(value.strip());
            }
        }
        return builder.toString().replaceAll("\\s+", " ").strip();
    }

    private static int topicMatchScore(TeachingEvidence evidence, List<String> keywords) {
        String haystack = compactEvidenceText(evidence);
        int score = 0;
        for (String keyword : keywords) {
            if (!keyword.isBlank() && haystack.contains(keyword.toLowerCase())) {
                score += keyword.length();
            }
        }
        return score;
    }

    private static String compactEvidenceText(TeachingEvidence evidence) {
        return ((evidence.sourceTitle() == null ? "" : evidence.sourceTitle()) + " "
                + (evidence.snippet() == null ? "" : evidence.snippet()))
                .replaceAll("\\s+", "")
                .toLowerCase();
    }

    private static List<String> topicKeywords(TeachingTaskRequest request) {
        String raw = ((request.learningGoal() == null ? "" : request.learningGoal()) + " "
                + (request.questionText() == null ? "" : request.questionText()))
                .replaceAll("[^\\p{IsHan}A-Za-z0-9]+", " ");
        raw = TOPIC_NOISE_WORD.matcher(raw).replaceAll(" ");
        LinkedHashSet<String> keywords = new LinkedHashSet<>();
        for (String part : raw.split("\\s+")) {
            String candidate = part.strip();
            if (candidate.length() < 2) {
                continue;
            }
            if (TOPIC_GENERIC_TERMS.contains(candidate)) {
                continue;
            }
            keywords.add(candidate);
        }
        return keywords.stream()
                .sorted(Comparator.comparingInt(String::length).reversed())
                .limit(8)
                .toList();
    }

    private static String primaryTopicKeyword(TeachingTaskRequest request) {
        List<String> keywords = topicKeywords(request);
        for (String keyword : keywords) {
            if (CORE_TOPIC_PREFERENCES.contains(keyword)) {
                return keyword;
            }
        }
        return keywords.isEmpty() ? "" : keywords.getFirst();
    }

    private static List<String> alignedQueries(TeachingTaskRequest request) {
        LinkedHashSet<String> queries = new LinkedHashSet<>();
        String coreTopic = primaryTopicKeyword(request);
        if (!coreTopic.isBlank()) {
            queries.add(coreTopic);
            String combined = (coreTopic + " " + safeQuestionText(request)).trim();
            if (!combined.isBlank()) {
                queries.add(combined);
            }
            String goalCombined = (coreTopic + " " + (request.learningGoal() == null ? "" : request.learningGoal().strip())).trim();
            if (!goalCombined.isBlank()) {
                queries.add(goalCombined);
            }
        }
        queries.addAll(QuestionBankSearchText.candidateQueries(request.learningGoal(), request.questionText()));
        return List.copyOf(queries);
    }

    /**
     * 构造真实执行过的 DAG 节点输出；未执行的扩展能力不得伪装为 completed。
     */
    private static List<TeachingWorkflowNode> buildNodes(
            TeachingTaskRequest request,
            List<TeachingEvidence> evidence,
            List<TeachingEvidence> questionEvidence,
            List<TeachingEvidence> teacherResourceEvidence,
            StudentMemoryResponse memoryResponse,
            TeachingTaskResponse.AiDraft aiDraft,
            TeachingHandoutTemplateProfile template,
            boolean questionBankAllowed,
            boolean teacherResourceAllowed) {
        String reuseSummary = memoryResponse.reused()
                ? "命中学生记忆 %s，作用域 %s，相似度 %.4f，跳过重复教材召回。"
                        .formatted(memoryResponse.memoryId(), memoryResponse.reuseScope(), memoryResponse.similarity())
                : "未命中可复用学生记忆，原因：" + memoryResponse.reason() + "。";
        boolean textbookRetrievalRan = !memoryResponse.reused();
        long publicTextbookCount = evidence.stream()
                .filter(item -> "PUBLIC_TEXTBOOK".equals(item.sourceScope()))
                .count();
        long teacherResourceCount = teacherResourceEvidence.size();
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
                node("TEACHER_RESOURCE_RETRIEVAL", "教师资料检索",
                        textbookRetrievalRan && teacherResourceAllowed ? "completed" : "skipped",
                        textbookRetrievalRan && teacherResourceAllowed
                                ? "命中教师资料证据 " + teacherResourceCount + " 条，已补充题型方法与教师沉淀。"
                                : "当前身份或复用路径未触发教师资料检索。"),
                node("REACT_SOLVE", "解题编排", "基于教材证据、学生记忆和题型方法整理讲解步骤。"),
                node("HANDOUT_TEMPLATE", "讲义模板", "使用模板：" + template.summary().displayName() + "。"),
                node("AI_DRAFT", "讲义内容生成", aiDraftSummary(aiDraft)),
                node("LATEX_HANDOUT", "讲义排版", "生成教师版、学生版和横版讲解稿，可预览并导出 PDF。"),
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
        if (aiDraft.structured()) {
            return "讲义草稿已整理为教师讲解、学生提示、知识点和追问任务，可进入人工审校。";
        }
        return "讲义草稿未能稳定结构化，请先人工复核内容后再导出使用。";
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
            TeachingHandoutTemplateProfile template,
            TeachingTaskResponse.AiDraft aiDraft) {
        String questionSection = safeQuestionText(request).isBlank()
                ? "围绕学习目标设计例题、变式题和课堂追问。"
                : safeQuestionText(request);
        String evidenceSnippet = memoryResponse.reused()
                ? "复用学生记忆：" + escapeLatex(memoryResponse.answer())
                : evidence.isEmpty() ? "暂无教材、题库或教师资料证据，需教师审校后补足题源。" : evidenceSummary(evidence);
        String teacherExplanation = aiDraft == null ? "" : guardDraftText(aiDraft.teacherExplanation(), true);
        String knowledgeLocation = draftBlockContent(teacherExplanation, teacherDraftLabels(), "知识定位");
        String questionType = draftBlockContent(teacherExplanation, teacherDraftLabels(), "题型识别");
        String methodSteps = draftBlockContent(teacherExplanation, teacherDraftLabels(), "方法步骤");
        String workedExample = draftBlockContent(teacherExplanation, teacherDraftLabels(), "例题详解");
        String answerPoints = draftBlockContent(teacherExplanation, teacherDraftLabels(), "答案与评分点");
        String draftPitfalls = draftBlockContent(teacherExplanation, teacherDraftLabels(), "易错提醒");
        String draftFollowUps = draftBlockContent(teacherExplanation, teacherDraftLabels(), "课堂追问");
        List<String> teacherMethodCardItems = mergeDistinctItems(
                8,
                draftBlockLines(knowledgeLocation),
                guardDraftItems(aiDraft == null ? List.of() : aiDraft.knowledgePoints(), true),
                draftBlockLines(questionType),
                teacherMethodCards(request, evidence, template));
        String questionBankTeacherSection = teacherQuestionBankSection(evidence);
        String teacherMethodCards = latexItemize(teacherMethodCardItems);
        String teacherBoardPlan = latexEnumerate(teacherBoardPlan(request, evidence, template));
        String teacherPitfalls = latexItemize(mergeDistinctItems(
                6,
                List.of(
                        "先核对条件是否读全，尤其是定义域、参数范围、符号方向和图形关系。",
                        "不要只记结论不写依据；每一步都要能指出来自哪个定义、公式或条件。",
                        "参数题和分类讨论题要先处理边界，再给最终结论。"),
                draftBlockLines(draftPitfalls)));
        String teacherFollowUps = latexEnumerate(mergeDistinctItems(
                8,
                draftBlockLines(draftFollowUps),
                guardDraftItems(aiDraft == null ? List.of() : aiDraft.followUpQuestions(), true),
                List.of(
                        "这道题的第一步为什么不能直接套结论？",
                        "如果把一个条件改掉，原方法还成立吗？",
                        "学生最容易错在识别题型、边界处理还是计算细节？")));
        String teacherReviewNotes = latexItemize(List.of(
                "先让学生口述定义、公式、图像特征或题型信号，再开始完整板书。",
                "讲到参数、范围、分类讨论时，先处理边界，再推进计算或证明。",
                "答案讲完后补一题同类型变式，检查学生是否真的会迁移。"));
        String wideSlides = latexEnumerate(teacherWideSlides(
                questionSection,
                questionType,
                methodSteps,
                answerPoints,
                draftPitfalls,
                draftFollowUps));
        StringBuilder builder = new StringBuilder();
        builder.append("\\section{课前定位}\n")
                .append(escapeLatex("模板：" + template.summary().displayName()))
                .append("\n\n")
                .append("\\subsection*{学习目标}\n")
                .append(escapeLatex(request.learningGoal()))
                .append("\n\n\\subsection*{题目入口}\n")
                .append(escapeLatex(questionSection))
                .append("\n\n\\subsection*{来源依据}\n")
                .append(evidenceSnippet)
                .append("\n\n");

        builder.append("\\section{核心公式与方法卡}\n")
                .append(teacherMethodCards)
                .append("\n\n\\section{讲评主线}\n")
                .append("\\subsection*{题型识别}\n")
                .append(contentOrFallback(questionType, escapeLatex("先判断题目属于定义识别、性质判断、参数范围还是综合应用。")))
                .append("\n\n\\subsection*{审题提醒}\n")
                .append(escapeLatex("先圈出已知条件中的定义、数量关系、图像关系或参数范围；若题目只给学习主题，则按该主题补一题典型例题再展开讲评。"))
                .append("\n\n\\subsection*{方法步骤}\n")
                .append(contentOrFallback(methodSteps, teacherBoardPlan))
                .append("\n\n\\section{典型例题与讲评入口}\n")
                .append("\\subsection*{题目 / 任务}\n")
                .append(escapeLatex(questionSection))
                .append("\n\n\\subsection*{例题详解}\n")
                .append(contentOrFallback(workedExample, escapeLatex("讲解时必须说明每一步依据来自哪个定义、公式、图形关系或题目条件，不能只给最终结论。")))
                .append("\n\n\\subsection*{答案与评分点}\n")
                .append(contentOrFallback(answerPoints, escapeLatex("若题目来源于题库或教师资料，答案区要保留关键步骤、得分点和边界说明。")))
                .append("\n\n");

        builder.append("\\section{16:10 横版讲解卡}\n")
                .append(wideSlides)
                .append("\n\n");

        builder.append("\\section{易错提醒与课堂追问}\n")
                .append("\\subsection*{易错提醒}\n")
                .append(teacherPitfalls)
                .append("\n\\subsection*{课堂追问}\n")
                .append(teacherFollowUps)
                .append('\n');

        if (!questionBankTeacherSection.isBlank()) {
            builder.append(questionBankTeacherSection).append('\n');
        }

        builder.append("\\section{板书与二次反馈}\n")
                .append(teacherReviewNotes)
                .append("\n\\section{课后订正与追踪记录}\n\\vspace{5em}\n");
        return builder.toString();
    }

    private static boolean canUseQuestionBank(TeachingRequestContext context) {
        String subjectType = context == null ? "" : context.subjectType();
        return "teacher".equalsIgnoreCase(subjectType) || "admin".equalsIgnoreCase(subjectType);
    }

    private static boolean canUseTeacherResources(TeachingRequestContext context) {
        return canUseQuestionBank(context);
    }

    /**
     * Builds a compact evidence summary instead of dumping raw OCR chunks into the handout.
     */
    private static String evidenceSummary(List<TeachingEvidence> evidence) {
        if (evidence.isEmpty()) {
            return "暂无教材证据。";
        }
        StringBuilder builder = new StringBuilder();
        int index = 1;
        for (TeachingEvidence item : evidence.stream().limit(5).toList()) {
            builder.append(escapeLatex(evidenceSourceLine(index, item)))
                    .append('\n');
            index += 1;
        }
        return builder.toString().strip();
    }

    private static String evidenceSourceLine(int index, TeachingEvidence item) {
        if ("QUESTION_BANK".equals(item.sourceScope())) {
            return "来源 " + index
                    + "：题库，" + questionTitleWithoutDifficulty(item)
                    + "，难度 " + questionDifficulty(item)
                    + "；用途：分层练习与教师答案区。";
        }
        if ("TEACHER_RESOURCE".equals(item.sourceScope())) {
            String page = item.pageNo() > 0 ? "第 " + item.pageNo() + " 页" : "页码未记录";
            return "来源 " + index
                    + "：教师资料，" + item.sourceTitle()
                    + "，" + page
                    + "；用途：题型方法、教师沉淀与讲义补充。";
        }
        String page = item.pageNo() > 0 ? "PDF " + item.pageNo() : "页码未记录";
        return "来源 " + index
                + "：公开教材，" + item.sourceTitle()
                + "，" + page
                + "；用途：知识点定位与公式依据。";
    }

    private static String evidenceLabel(TeachingEvidence item) {
        if ("QUESTION_BANK".equals(item.sourceScope())) {
            return "题库：" + questionTitleWithoutDifficulty(item);
        }
        if ("TEACHER_RESOURCE".equals(item.sourceScope())) {
            return item.pageNo() > 0
                    ? item.sourceTitle() + " / 第 " + item.pageNo() + " 页"
                    : item.sourceTitle();
        }
        return item.sourceTitle() + " / PDF " + item.pageNo();
    }

    /**
     * 生成学生版 LaTeX 讲义：保留题目、提示和干净空白，不直接暴露教师解析和知识点归属。
     */
    private static String buildStudentHandoutLatex(
            TeachingTaskRequest request,
            List<TeachingEvidence> evidence,
            StudentMemoryResponse memoryResponse,
            TeachingHandoutTemplateProfile template,
            TeachingTaskResponse.AiDraft aiDraft) {
        String hint = memoryResponse.reused()
                ? "回忆同类问题的方法，先写出已知条件，再判断可用公式。"
                : evidence.isEmpty()
                ? "先圈出题目中的关键词，再尝试写出相关定义。"
                : "先阅读教材证据中的定义或公式，再补全自己的推理。";
        String lectureTitle = studentLectureTitle(request);
        String studentHint = aiDraft == null ? "" : guardDraftText(aiDraft.studentHint(), false);
        String draftKnowledge = draftBlockContent(studentHint, studentDraftLabels(), "知识速记");
        String draftType = draftBlockContent(studentHint, studentDraftLabels(), "题型识别");
        String draftExample = draftBlockContent(studentHint, studentDraftLabels(), "例题任务");
        String draftPractice = draftBlockContent(studentHint, studentDraftLabels(), "练习任务");
        String draftNotice = draftBlockContent(studentHint, studentDraftLabels(), "作答提醒");
        String knowledgeSection = studentKnowledgeCardsLatex(mergeDistinctItems(
                5,
                draftBlockLines(draftKnowledge),
                guardDraftItems(aiDraft == null ? List.of() : aiDraft.knowledgePoints(), false),
                studentKnowledgeCards(request, evidence, hint)));
        String noteSection = latexItemize(mergeDistinctItems(
                5,
                draftBlockLines(draftNotice),
                studentNoticeCards(request, evidence)));
        String methodSection = latexEnumerate(mergeDistinctItems(
                5,
                draftBlockLines(draftType),
                studentMethodCards(request, evidence)));
        String exampleSection = contentOrFallback(draftExample, studentExampleSection(request, evidence, hint));
        List<String> practiceItems = studentPracticeTasks(request, evidence, aiDraft, draftPractice);
        int blankSpaceEm = template.blankSpaceEm();
        if (template.studentLectureStyle()) {
            String questionText = safeQuestionText(request).isBlank()
                    ? "根据本节主题完成下面的知识梳理与分层练习。"
                    : safeQuestionText(request);
            return """
                    \\section{%s}

                    \\section{知识速记}
                    %s

                    \\section{注意}
                    %s

                    \\section{题型识别}
                    %s

                    \\section{例题任务}
                    %s
                    \\paragraph{本讲题干}
                    %s

                    \\section{连续编号练习}
                    %s
                    %s

                    \\section{订正与错因}
                    \\vspace{6em}
                    """.formatted(
                    escapeLatex(lectureTitle),
                    knowledgeSection,
                    noteSection,
                    methodSection,
                    exampleSection,
                    escapeLatex(questionText),
                    latexEnumerateWithWorkspace(practiceItems, blankSpaceEm),
                    studentQuestionBankSection(request, evidence, blankSpaceEm));
        }
        String questionText = safeQuestionText(request).isBlank()
                ? "根据本讲主题完成例题、变式和订正。"
                : safeQuestionText(request);
        return """
                \\section{%s}

                \\section{知识速记}
                %s

                \\section{注意}
                %s

                \\section{题型识别}
                %s

                \\section{例题任务}
                %s
                \\paragraph{本讲题干}
                %s
                
                \\section{连续编号练习}
                %s
                %s

                \\section{错因整理}
                \\vspace{6em}
                """.formatted(
                escapeLatex(lectureTitle),
                knowledgeSection,
                noteSection,
                methodSection,
                exampleSection,
                escapeLatex(questionText),
                latexEnumerateWithWorkspace(practiceItems, blankSpaceEm),
                studentQuestionBankSection(request, evidence, blankSpaceEm));
    }

    /**
     * Keeps the built-in scaffold compact so the later AI draft sections remain the primary readable content.
     */
    private static List<String> teacherMethodCards(
            TeachingTaskRequest request,
            List<TeachingEvidence> evidence,
            TeachingHandoutTemplateProfile template) {
        List<String> cards = new ArrayList<>();
        cards.add("先写出本讲对应的定义、公式、图像特征或空间关系，再进入计算或证明。");
        if (!safeQuestionText(request).isBlank()) {
            cards.add("题目入口：" + safeQuestionText(request));
        }
        if (!evidence.isEmpty()) {
            cards.add("优先依据命中的教材/题库/教师资料组织讲评，不直接搬运 OCR 原文。");
            cards.add("命中主证据：" + evidenceLabel(evidence.getFirst()));
        }
        cards.add("题型推进保持“识别条件 → 选择方法 → 写关键等式 → 回收答案与评分点”。");
        return cards.stream().distinct().limit(5).toList();
    }

    private static List<String> studentMethodCards(TeachingTaskRequest request, List<TeachingEvidence> evidence) {
        List<String> cards = new ArrayList<>();
        cards.add("先圈出关键词，再判断对应的是定义、公式、图像性质还是题型方法。");
        cards.add("遇到参数、范围、符号或图形关系时，先处理边界条件。");
        if (!safeQuestionText(request).isBlank()) {
            cards.add("本讲例题围绕“" + safeQuestionText(request) + "”展开。");
        }
        if (!evidence.isEmpty()) {
            cards.add("先看教材或题源中的核心定义，再自己写第一步。");
        }
        return cards.stream().distinct().limit(4).toList();
    }

    private static List<String> teacherBoardPlan(
            TeachingTaskRequest request,
            List<TeachingEvidence> evidence,
            TeachingHandoutTemplateProfile template) {
        List<String> plan = new ArrayList<>();
        plan.add("先用 1 行话说清本讲主题、题型入口和核心依据，再开始板书。");
        plan.add("板书顺序保持“写定义/公式 → 审条件 → 立关键等式或图形关系 → 回收答案”。");
        if (!safeQuestionText(request).isBlank()) {
            plan.add("把题干中的关键词拆成已知条件、求解目标和第一步落点：" + safeQuestionText(request));
        }
        if (!evidence.isEmpty()) {
            plan.add("引用首条真实来源作为板书依据：" + evidenceLabel(evidence.getFirst()));
        }
        if (template.summary().referenceTitle() != null && !template.summary().referenceTitle().isBlank()) {
            plan.add("保持模板风格与课堂节奏：" + template.summary().referenceTitle());
        }
        return plan.stream().distinct().limit(5).toList();
    }

    private static List<String> teacherChecklist(
            TeachingTaskRequest request,
            List<TeachingEvidence> evidence,
            TeachingHandoutTemplateProfile template) {
        List<String> checklist = new ArrayList<>();
        checklist.add("核对教师版是否包含知识来源、题型识别、完整答案、追问和错因提醒。");
        checklist.add("核对学生版是否只保留知识点、题目、提示和足够作答留白。");
        checklist.add("检查分式、平方、不等号、根式是否按标准 LaTeX 渲染。");
        if (!evidence.isEmpty()) {
            checklist.add("抽查命中来源与讲义内容是否一致，避免把 OCR 碎片直接写进正文。");
        }
        if (!safeQuestionText(request).isBlank()) {
            checklist.add("确认题干与模板主线一致，不要把题型和例题讲偏。");
        }
        if (template.studentLectureStyle()) {
            checklist.add("确认学生版题号连续、留白充足，适合横版讲解和课堂打印。");
        }
        return checklist.stream().distinct().limit(6).toList();
    }

    private static String studentKnowledgeSection(
            TeachingTaskRequest request,
            List<TeachingEvidence> evidence,
            String fallbackHint) {
        List<String> items = new ArrayList<>();
        items.add(fallbackHint);
        if (!safeQuestionText(request).isBlank()) {
            items.add("题目条件先拆成“已知什么、要求什么、先用什么”。");
        }
        if (!evidence.isEmpty()) {
            items.add("优先回忆命中证据里的定义、公式或题型信号，再开始作答。");
        }
        items.add("公式、定义、图像性质写清以后再进入计算，避免直接硬算。");
        return latexItemize(items.stream().distinct().limit(4).toList());
    }

    private static List<String> studentKnowledgeCards(
            TeachingTaskRequest request,
            List<TeachingEvidence> evidence,
            String fallbackHint) {
        List<String> cards = new ArrayList<>();
        cards.add("先写本讲最核心的定义、公式和适用条件，再开始计算或证明。");
        if (!evidence.isEmpty()) {
            cards.add("先回到命中来源里的主定义或主公式，再决定第一步。来源：" + evidenceLabel(evidence.getFirst()));
        }
        if (!safeQuestionText(request).isBlank()) {
            cards.add("把题目拆成“已知条件、求解目标、第一步依据”三件事。");
        }
        cards.add(fallbackHint);
        return cards.stream().distinct().limit(4).toList();
    }

    private static String studentKnowledgeCardsLatex(List<String> cards) {
        return latexItemize(cards);
    }

    private static List<String> studentNoticeCards(TeachingTaskRequest request, List<TeachingEvidence> evidence) {
        List<String> notes = new ArrayList<>();
        notes.add("先核对定义域、参数是否为 0、符号方向和边界条件。");
        notes.add("若题目涉及图像、几何关系或位置关系，先画草图或标关键量。");
        if (!safeQuestionText(request).isBlank()) {
            notes.add("读题时先划出“已知什么、要求什么、第一步写什么”。");
        }
        if (!evidence.isEmpty()) {
            notes.add("教材或题源中的关键词先记下来，再开始计算。");
        }
        return notes.stream().distinct().limit(4).toList();
    }

    private static List<String> studentPracticeTasks(
            TeachingTaskRequest request,
            List<TeachingEvidence> evidence,
            TeachingTaskResponse.AiDraft aiDraft,
            String draftPractice) {
        List<String> tasks = new ArrayList<>();
        tasks.addAll(draftBlockLines(draftPractice));
        tasks.addAll(guardDraftItems(aiDraft == null ? List.of() : aiDraft.followUpQuestions(), false));
        for (TeachingEvidence item : questionBankEvidence(evidence)) {
            tasks.add(questionDifficulty(item) + "：" + questionTextOnly(item.snippet()));
        }
        tasks.addAll(defaultStudentExercises(request));
        return mergeDistinctItems(6, tasks).stream().limit(6).toList();
    }

    private static List<String> defaultStudentExercises(TeachingTaskRequest request) {
        String goal = request.learningGoal() == null || request.learningGoal().isBlank()
                ? "本讲主题"
                : request.learningGoal().strip();
        String prompt = safeQuestionText(request).isBlank()
                ? goal
                : safeQuestionText(request);
        return List.of(
                "基础 1：先写出“" + goal + "”对应的定义、公式或图像特征。",
                "基础 2：围绕“" + prompt + "”写出第一步依据，并说明为什么这样设。",
                "基础 3：补全一组最小条件，判断本题能否直接套用核心公式。",
                "提高 1：把题目中的一个条件改成相近条件，说明解法哪里要调整。",
                "提高 2：保留主方法不变，补一题同类变式并完成关键一步。",
                "综合 1：整理本讲同类题的审题顺序，并写出最容易漏掉的一步。");
    }

    private static List<String> teacherWideSlides(
            String questionSection,
            String questionType,
            String methodSteps,
            String answerPoints,
            String pitfalls,
            String followUps) {
        return List.of(
                "第 1 屏：用一句话交代本讲题目“" + questionSection + "”，并标出知识入口与题型信号。",
                "第 2 屏：突出题型识别与关键方法。"
                        + (questionType.isBlank() ? "先解释为什么选这个方法。" : flattenDraftBlock(questionType)),
                "第 3 屏：逐步板书核心推导与答案回收。"
                        + (methodSteps.isBlank() ? "每一步都写依据。" : flattenDraftBlock(methodSteps))
                        + (answerPoints.isBlank() ? "" : " 结尾强调：" + flattenDraftBlock(answerPoints)),
                "第 4 屏：总结易错点与追问。"
                        + (pitfalls.isBlank() ? "" : " 易错点：" + flattenDraftBlock(pitfalls))
                        + (followUps.isBlank() ? "" : " 追问：" + flattenDraftBlock(followUps)));
    }

    private static String studentExampleSection(
            TeachingTaskRequest request,
            List<TeachingEvidence> evidence,
            String fallbackHint) {
        List<String> items = new ArrayList<>();
        if (!safeQuestionText(request).isBlank()) {
            items.add("先独立拆题：把“" + safeQuestionText(request) + "”分成已知条件、目标和关键方法。");
        } else {
            items.add("先围绕本讲主题补出 1 道典型例题，再写第一步关键依据。");
        }
        items.add("作答时先写定义、公式或图形关系，再推进运算或证明。");
        if (!evidence.isEmpty()) {
            items.add("可参考命中来源中的核心定义或公式，答案由学生独立完成。");
        } else {
            items.add(fallbackHint);
        }
        return latexEnumerate(items.stream().distinct().limit(4).toList());
    }

    private static String studentLectureTitle(TeachingTaskRequest request) {
        String goal = request.learningGoal() == null ? "" : request.learningGoal().strip();
        if (goal.contains("专题")) {
            return "专题  " + goal;
        }
        return "第 1 讲  " + goal;
    }

    /**
     * Builds teacher-only question bank practice with answer snippets preserved.
     */
    private static String teacherQuestionBankSection(List<TeachingEvidence> evidence) {
        List<TeachingEvidence> questions = questionBankEvidence(evidence);
        if (questions.isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder("\\section{题库分层练习与答案}\n");
        int index = 1;
        for (TeachingEvidence item : questions) {
            String difficulty = questionDifficulty(item);
            String question = questionTextOnly(item.snippet());
            String answer = questionAnswerOnly(item.snippet());
            builder.append("\\subsection*{题 ").append(index).append("  ")
                    .append(escapeLatex(difficulty + " · " + questionTitleWithoutDifficulty(item)))
                    .append("}\n")
                    .append(escapeLatex(question))
                    .append("\n\n\\paragraph{答案要点}\n")
                    .append(escapeLatex(answer.isBlank() ? "题库未提供答案，需教师审校后补充。" : answer))
                    .append("\n\n\\paragraph{讲评提醒}\n")
                    .append(escapeLatex("先让学生说出第一步依据，再补完整解题链路。"))
                    .append("\n\n");
            index += 1;
        }
        return builder.toString();
    }

    /**
     * Builds student-safe question bank practice without answer or scoring leakage.
     */
    private static String studentQuestionBankSection(TeachingTaskRequest request, List<TeachingEvidence> evidence, int blankSpaceEm) {
        List<TeachingEvidence> questions = questionBankEvidence(evidence);
        if (questions.isEmpty()) {
            return "";
        }
        int space = boundedEm(blankSpaceEm, 5, 12, 6);
        List<String> tasks = new ArrayList<>();
        for (TeachingEvidence item : questions) {
            tasks.add(questionDifficulty(item) + "：" + questionTextOnly(item.snippet()));
        }
        StringBuilder builder = new StringBuilder("\\section{题库分层练习}\n\\begin{enumerate}\n");
        for (String item : tasks) {
            builder.append("\\item ")
                    .append(escapeLatex(item))
                    .append("\n\\vspace{").append(space).append("em}\n");
        }
        builder.append("\\end{enumerate}\n");
        return builder.toString();
    }

    private static List<TeachingEvidence> questionBankEvidence(List<TeachingEvidence> evidence) {
        return evidence.stream()
                .filter(item -> "QUESTION_BANK".equals(item.sourceScope()))
                .sorted(Comparator.comparingInt(TeachingWorkflowService::questionDifficultyRank))
                .limit(3)
                .toList();
    }

    private static int questionDifficultyRank(TeachingEvidence item) {
        String difficulty = questionDifficulty(item);
        if (difficulty.contains("基础") || difficulty.equalsIgnoreCase("easy")) {
            return 0;
        }
        if (difficulty.contains("提高") || difficulty.contains("中等") || difficulty.equalsIgnoreCase("medium")) {
            return 1;
        }
        if (difficulty.contains("压轴") || difficulty.contains("困难") || difficulty.equalsIgnoreCase("hard")) {
            return 2;
        }
        return 3;
    }

    private static String questionDifficulty(TeachingEvidence item) {
        String title = item.sourceTitle() == null ? "" : item.sourceTitle();
        int index = title.indexOf("难度：");
        if (index >= 0) {
            return title.substring(index + "难度：".length()).strip();
        }
        index = title.indexOf("难度:");
        if (index >= 0) {
            return title.substring(index + "难度:".length()).strip();
        }
        return "未标难度";
    }

    private static String questionTitleWithoutDifficulty(TeachingEvidence item) {
        String title = item.sourceTitle() == null ? "" : item.sourceTitle().strip();
        if (title.isBlank()) {
            return "题库题目";
        }
        return title
                .replaceAll("\\s*/\\s*难度[:：].*$", "")
                .replaceAll("\\s*（?难度[:：].*?）?\\s*$", "")
                .strip();
    }

    private static String questionTextOnly(String snippet) {
        if (snippet == null || snippet.isBlank()) {
            return "题目内容待补充。";
        }
        String[] parts = snippet.split("答案要点：", 2);
        return parts[0].replaceAll("\\s+", " ").strip();
    }

    private static String questionAnswerOnly(String snippet) {
        if (snippet == null || snippet.isBlank()) {
            return "";
        }
        String[] parts = snippet.split("答案要点：", 2);
        if (parts.length < 2) {
            return "";
        }
        String answer = QuestionBankAnswerFormatter.format(parts[1]);
        return "答案要点：" + answer;
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
        String normalized = com.doob.mathagent.infrastructure.text.FormulaMarkupSanitizer.sanitizeFeishuMath(value);
        StringBuilder builder = new StringBuilder();
        StringBuilder segment = new StringBuilder();
        boolean math = false;
        for (int index = 0; index < normalized.length(); index += 1) {
            if (normalized.startsWith("$$", index)) {
                builder.append(math ? sanitizeMathSegment(segment.toString()) : escapeLatexTextWithBlanks(segment.toString()));
                segment.setLength(0);
                builder.append("$$");
                math = !math;
                index += 1;
                continue;
            }
            char character = normalized.charAt(index);
            if (character == '$') {
                builder.append(math ? sanitizeMathSegment(segment.toString()) : escapeLatexTextWithBlanks(segment.toString()));
                segment.setLength(0);
                builder.append('$');
                math = !math;
            } else {
                segment.append(character);
            }
        }
        builder.append(math ? sanitizeMathSegment(segment.toString()) : escapeLatexTextWithBlanks(segment.toString()));
        return builder.toString();
    }

    private static String escapeLatexTextWithBlanks(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        Matcher matcher = BLANK_PLACEHOLDER.matcher(value);
        StringBuilder builder = new StringBuilder();
        int cursor = 0;
        while (matcher.find()) {
            builder.append(escapeLatexText(value.substring(cursor, matcher.start())));
            int width = Math.max(4, Math.min(10, matcher.group().length() + 1));
            builder.append("\\underline{\\hspace{").append(width).append("em}}");
            cursor = matcher.end();
        }
        builder.append(escapeLatexText(value.substring(cursor)));
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

    private static List<String> teacherDraftLabels() {
        return List.of("知识定位", "题型识别", "方法步骤", "例题详解", "答案与评分点", "易错提醒", "课堂追问");
    }

    private static List<String> studentDraftLabels() {
        return List.of("知识速记", "题型识别", "例题任务", "练习任务", "作答提醒");
    }

    private static String labeledDraftSections(String text, List<String> labels, String fallbackTitle) {
        List<LabeledDraftBlock> blocks = parseLabeledDraftBlocks(text, labels, fallbackTitle);
        StringBuilder builder = new StringBuilder();
        for (LabeledDraftBlock block : blocks) {
            builder.append("\\subsection*{")
                    .append(escapeLatex(block.label()))
                    .append("}\n")
                    .append(formatDraftContentAsLatex(block.content()))
                    .append("\n\n");
        }
        return builder.toString();
    }

    private static String draftBlockContent(String text, List<String> labels, String targetLabel) {
        if (text == null || text.isBlank()) {
            return "";
        }
        return parseLabeledDraftBlocks(text, labels, targetLabel).stream()
                .filter(block -> targetLabel.equals(block.label()))
                .map(LabeledDraftBlock::content)
                .findFirst()
                .orElse("");
    }

    private static List<String> draftBlockLines(String content) {
        if (content == null || content.isBlank()) {
            return List.of();
        }
        List<String> items = new ArrayList<>();
        for (String rawLine : content.replace("\r\n", "\n").replace('\r', '\n').split("\n")) {
            String line = rawLine.strip()
                    .replaceFirst("^[0-9]+[.、)]\\s*", "")
                    .replaceFirst("^[-•·]\\s*", "")
                    .strip();
            if (!line.isBlank()) {
                items.add(line);
            }
        }
        return items;
    }

    @SafeVarargs
    private static List<String> mergeDistinctItems(int limit, List<String>... groups) {
        LinkedHashSet<String> merged = new LinkedHashSet<>();
        for (List<String> group : groups) {
            if (group == null) {
                continue;
            }
            for (String item : group) {
                String normalized = guardHandoutLatex(item, true).replaceAll("\\s+", " ").strip();
                if (!normalized.isBlank()) {
                    merged.add(normalized);
                }
                if (merged.size() >= limit) {
                    return List.copyOf(merged);
                }
            }
        }
        return List.copyOf(merged);
    }

    private static String flattenDraftBlock(String content) {
        return content == null ? "" : content.replaceAll("\\s+", " ").strip();
    }

    private static String contentOrFallback(String content, String fallbackLatex) {
        if (content == null || content.isBlank()) {
            return fallbackLatex == null ? "" : fallbackLatex;
        }
        return formatDraftContentAsLatex(content);
    }

    private static String latexEnumerateWithWorkspace(List<String> items, int workspaceEm) {
        if (items == null || items.isEmpty()) {
            return "\n";
        }
        int space = boundedEm(workspaceEm, 5, 12, 6);
        StringBuilder builder = new StringBuilder("\n\\begin{enumerate}\n");
        for (String item : items) {
            builder.append("\\item ").append(escapeLatex(item)).append("\\par\n")
                    .append("\\vspace{").append(space).append("em}\n");
        }
        return builder.append("\\end{enumerate}\n").toString();
    }

    private static int boundedEm(int value, int min, int max, int fallback) {
        if (value <= 0) {
            return fallback;
        }
        return Math.max(min, Math.min(max, value));
    }

    private static String formatDraftContentAsLatex(String content) {
        String source = content == null ? "" : content
                .replace("\r\n", "\n")
                .replace('\r', '\n')
                .strip();
        if (source.isBlank()) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        List<String> ordered = new ArrayList<>();
        List<String> unordered = new ArrayList<>();
        for (String rawLine : source.split("\n")) {
            String line = rawLine.strip();
            if (line.isBlank()) {
                flushDraftList(builder, ordered, true);
                flushDraftList(builder, unordered, false);
                builder.append("\\par\n");
                continue;
            }
            Matcher orderedMatcher = DRAFT_ORDERED_LINE.matcher(line);
            Matcher bulletMatcher = DRAFT_BULLET_LINE.matcher(line);
            if (orderedMatcher.matches()) {
                flushDraftList(builder, unordered, false);
                ordered.add(orderedMatcher.group(1).strip());
                continue;
            }
            if (bulletMatcher.matches()) {
                flushDraftList(builder, ordered, true);
                unordered.add(bulletMatcher.group(1).strip());
                continue;
            }
            flushDraftList(builder, ordered, true);
            flushDraftList(builder, unordered, false);
            builder.append(escapeLatex(line)).append("\\par\n");
        }
        flushDraftList(builder, ordered, true);
        flushDraftList(builder, unordered, false);
        return builder.toString();
    }

    private static void flushDraftList(StringBuilder builder, List<String> items, boolean ordered) {
        if (items.isEmpty()) {
            return;
        }
        builder.append(ordered ? "\\begin{enumerate}\n" : "\\begin{itemize}\n");
        for (String item : items) {
            builder.append("\\item ").append(escapeLatex(item)).append('\n');
        }
        builder.append(ordered ? "\\end{enumerate}\n" : "\\end{itemize}\n");
        items.clear();
    }

    private static List<LabeledDraftBlock> parseLabeledDraftBlocks(String text, List<String> labels, String fallbackTitle) {
        String source = text == null ? "" : text.strip();
        if (source.isBlank()) {
            return List.of();
        }
        List<LabelPosition> positions = new ArrayList<>();
        for (String label : labels) {
            String marker = "【" + label + "】";
            int from = 0;
            while (from < source.length()) {
                int start = source.indexOf(marker, from);
                if (start < 0) {
                    break;
                }
                positions.add(new LabelPosition(label, start, start + marker.length()));
                from = start + marker.length();
            }
        }
        positions.sort(Comparator.comparingInt(LabelPosition::start));
        if (positions.isEmpty()) {
            return List.of(new LabeledDraftBlock(fallbackTitle, source));
        }
        List<LabeledDraftBlock> blocks = new ArrayList<>();
        String prefix = source.substring(0, positions.getFirst().start()).strip();
        if (!prefix.isBlank()) {
            blocks.add(new LabeledDraftBlock(fallbackTitle, prefix));
        }
        for (int index = 0; index < positions.size(); index += 1) {
            LabelPosition current = positions.get(index);
            int nextStart = index + 1 < positions.size() ? positions.get(index + 1).start() : source.length();
            String content = source.substring(current.end(), nextStart).strip();
            if (!content.isBlank()) {
                blocks.add(new LabeledDraftBlock(current.label(), content));
            }
        }
        return List.copyOf(blocks);
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

    private record LabelPosition(String label, int start, int end) {
    }

    private record LabeledDraftBlock(String label, String content) {
    }
}
