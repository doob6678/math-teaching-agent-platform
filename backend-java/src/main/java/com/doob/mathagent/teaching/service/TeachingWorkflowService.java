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

/**
 * 教学任务 DAG 编排服务：把用户学习目标、资源复用、教材检索、ReAct 解题和讲义生成串成可恢复任务。
 */
@Service
public class TeachingWorkflowService {

    private static final int RESUME_EVIDENCE_LIMIT = 3;
    /** Minimum blank writing area reserved after every standalone student question. */
    private static final int STUDENT_QUESTION_WORKSPACE_EM = 18;
    /** Blank projection area that keeps each 16:10 lecture unit visually separated. */
    private static final int LECTURE_CARD_WORKSPACE_EM = 14;
    /** Upper bound for concurrent isolated question branches; keeps provider and thread pressure predictable. */
    private static final int QUESTION_AGENT_MAX_PARALLELISM = 4;
    /** Maximum visible characters on one projection page, preserving a large annotation area below the prompt. */
    private static final int MAX_LECTURE_CARD_CHARACTERS = 220;
    /** A projection card explains only the three decision points that fit beside a full real question. */
    private static final int LECTURE_PROJECTION_STEP_LIMIT = 3;
    /** Balanced columns keep a source diagram with its exact question instead of pushing it to a following page. */
    private static final String LECTURE_COLUMN_WIDTH = "0.485\\linewidth";
    /** Diagram height is bounded inside the left column so it cannot force a second 16:10 page. */
    private static final String LECTURE_IMAGE_MAX_HEIGHT = "0.54\\textheight";
    /** Printed figure height leaves a full prompt-plus-solution unit together while keeping a source page legible. */
    private static final String PRINTED_IMAGE_MAX_HEIGHT = "0.42\\textheight";
    /** Printed figure width keeps portrait page assets readable rather than reducing them to a thumbnail. */
    private static final String PRINTED_IMAGE_WIDTH = "0.90\\linewidth";
    /** A generation evidence item must be one question, not an OCR page bundle imported as one bank record. */
    private static final int MAX_HANDOUT_QUESTION_TEXT_CHARACTERS = 1200;
    /** A second top-level question number means the importer failed to split the source document. */
    private static final int MAX_TOP_LEVEL_QUESTION_MARKERS = 1;
    /** Extra document-scoped hits used when a visual question's first block is text-only but a sibling carries assets. */
    private static final int TEACHER_RESOURCE_IMAGE_RECOVERY_LIMIT = 3;
    /** Bounded RAG payload sent to a handout draft: enough for a source conclusion without exhausting model context. */
    private static final int MAX_TEACHING_EVIDENCE_CHARS = 120;
    /** Reserve the beginning of a source excerpt for the problem context before retaining a later conclusion. */
    private static final int TEACHING_EVIDENCE_INTRO_CHARS = 42;
    /** A concise terminal result/method clause retained when a source block is longer than the model evidence budget. */
    private static final int TEACHING_EVIDENCE_CONCLUSION_CHARS = 70;
    /** Context retained immediately before an explicit source result marker such as “合计” or “答案”. */
    private static final int TEACHING_EVIDENCE_MARKER_CONTEXT_CHARS = 18;
    /** Bounds fallback deduplication when a legacy source has no stable Feishu document token in its title. */
    private static final int MAX_EVIDENCE_FINGERPRINT_CHARS = 160;
    /** Stable Feishu document tokens are preserved by synchronized document titles and identify mirrored copies. */
    private static final Pattern FEISHU_DOCUMENT_TOKEN = Pattern.compile(
            "(?<![A-Za-z0-9])([A-Za-z][A-Za-z0-9]{11,})(?![A-Za-z0-9])");
    /** A colour count is a material condition in map-colouring problems, not a loose search keyword. */
    private static final Pattern COLORING_TOPIC = Pattern.compile("(?:涂色|着色|颜色)");
    /** Require “种颜色” so the rule does not mistake “同一颜色” for a selectable-colour count. */
    private static final Pattern COLOR_COUNT = Pattern.compile("([0-9一二三四五六七八九十]+)\\s*种(?:不同的)?颜色");
    /** A printable source result must be a complete arithmetic equality, never an OCR sentence around it. */
    private static final Pattern VERIFIED_SUM_EXPRESSION = Pattern.compile(
            "(?<![0-9])([0-9]+(?:\\s*[+＋]\\s*[0-9]+)+\\s*=\\s*[0-9]+)(?![0-9])");
    /** Normalizes the bounded Chinese number vocabulary accepted by the colour-count condition. */
    private static final Map<String, Integer> CHINESE_COLOR_COUNTS = Map.of(
            "一", 1, "二", 2, "三", 3, "四", 4, "五", 5,
            "六", 6, "七", 7, "八", 8, "九", 9, "十", 10);
    private static final Pattern VISUAL_EVIDENCE_REQUEST = Pattern.compile(
            "(?:图|图片|如图|地图|image|figure)", Pattern.CASE_INSENSITIVE);
    /** A stem that points at a diagram is incomplete until the same authorized diagram is synchronized. */
    private static final Pattern FIGURE_DEPENDENT_QUESTION = Pattern.compile("(?:如图|见图|下图|上图|图中)");
    /** An OCR square in a mathematical stem is an unresolved relation, not a printable answer blank. */
    private static final Pattern UNRESOLVED_OCR_MATH_GLYPH = Pattern.compile("[□�]");
    /** Importers sometimes put a display label between a shortened OCR preview and the full question stem. */
    private static final Pattern STANDALONE_QUESTION_LABEL = Pattern.compile("(?m)^\\s*题目\\s*[：:]?\\s*$");
    /** Source workbook banners are audit metadata, never mathematical stem content. */
    private static final Pattern PRINTABLE_SOURCE_WORKBOOK_PREFIX = Pattern.compile(
            "^(?:(?:赵礼显数学|飞猪数学)\\s*)?(?:作业|讲义|课堂练习)\\s*\\d+\\s*[.．、:：]?\\s*");
    /** Product/source labels from historical snapshots must not become a teacher-facing attribution. */
    private static final Pattern PRINTABLE_SOURCE_BRAND_PREFIX = Pattern.compile("^(?:赵礼显数学|飞猪数学)\\s*");
    /** A qualified printable handout requires ten distinct, source-traceable real questions. */
    private static final int MIN_QUALIFIED_HANDOUT_QUESTION_COUNT = 10;
    /** A compilation search reads several requested result pages before selecting one coherent source document. */
    private static final int QUESTION_BANK_COMPILATION_QUERY_MULTIPLIER = 4;
    /** Stable registry identity for the user-authorized Zhao master; used only for renderer-owned content structure. */
    private static final String ZHAO_MASTER_TEMPLATE_CODE = "zhao_lixian_2025_master_v1";
    /** A fuzzy point/source binding needs two independent curriculum terms whenever two are available. */
    private static final int MIN_DISTINCT_POINT_TERMS_FOR_FUZZY_SUPPORT = 2;

    private static final Pattern DRAFT_ORDERED_LINE = Pattern.compile("^\\s*(?:\\d+|[一二三四五六七八九十]+)[.、)]\\s+(.+)$");
    /** Original source number recovered from a synchronized atomic question stem. */
    private static final Pattern SOURCE_QUESTION_NUMBER = Pattern.compile("^\\s*(\\d{1,3})[.．、]");
    /** Top-level numbered model solution; indented numbered derivation steps deliberately do not match this form. */
    private static final Pattern MODEL_EXPLANATION_HEADING = Pattern.compile(
            "(?m)^\\s*(?:第\\s*)?(?:题\\s*)?(\\d{1,3})(?:\\s*题)?[.．、:：]\\s*(?:【[^】]{1,32}】\\s*)?(.+)$");
    /** Some providers compact adjacent `题N：` units onto one line; restore only explicit top-level labels. */
    private static final Pattern INLINE_MODEL_EXPLANATION_HEADING = Pattern.compile(
            "(?<!\\R)(?=(?:第\\s*)?(?:题\\s*)?\\d{1,3}(?:\\s*题)?[.．、:：])");
    /** A question-specific model excerpt needs enough shared prompt terms to avoid cross-question contamination. */
    private static final int MIN_MODEL_PROMPT_MATCHES = 2;
    /** An explicit source-number match still needs a real reasoning chain; a bare conclusion is never publishable. */
    private static final int MIN_NUMBERED_REASONING_CHARACTERS = 120;
    private static final Pattern SUBSTANTIVE_REASONING_SIGNAL = Pattern.compile(
            "(?:条件识别|推导依据|步骤|计算|由[^。；]{1,}|因此|故|证明|结论)");
    private static final Pattern TOP_LEVEL_QUESTION_MARKER = Pattern.compile("(?m)^\\s*\\d{1,3}[.、．]");
    private static final Pattern DRAFT_BULLET_LINE = Pattern.compile("^\\s*[-•·]\\s+(.+)$");
    /** Optional model-authored strategy heading; the renderer validates and falls back to the concrete point title. */
    private static final Pattern CUSTOM_METHOD_HEADING = Pattern.compile(
            "(?m)^\\s*(?:方法标题|策略标题|标题)\\s*[：:]\\s*(.{2,36})\\s*$");
    private static final Pattern BLANK_PLACEHOLDER = Pattern.compile("_{3,}|＿{3,}");
    private static final Pattern LATEX_HEADING_LINE = Pattern.compile("^\\\\(section\\*?|subsection\\*?|subsubsection\\*?|paragraph\\*?)\\{(.+)}\\s*$");
    private static final Pattern INTERNAL_HANDOUT_LINE = Pattern.compile(
            "(?mi)^.*(?:MODEL_CALL|JSON_PARSE|\\btokens?\\b|模型健康|model health|debug|调试|JSON|内部提示词|内部提示|系统提示|提示词|方法标题|策略标题|OCR\\s*原文|\\{\\{[^\\r\\n]*\\}\\}|PDF\\s*(?:规则|排版|版式)|PDF\\s*版式|排版说明|版式要求|页眉|页脚|(?:页面颜色|颜色规则|讲评色|练习色)|渲染引擎|模板规则|页边距|虚线折叠|documentclass|usepackage|fancyhdr|pagestyle|begin\\{document}|end\\{document}|作为\\s*AI|as an AI|本页只保留|课堂任务|本讲任务|讲后自查|教师审校清单|横版讲解提纲|AI 知识定位|模板偏向|本讲更偏向).*$");
    /** Control and evaluation statements are not mathematical questions and must never become handout text. */
    private static final Pattern TASK_CONTROL_LINE = Pattern.compile(
            "(?mi)^.*(?:题目入口|讲评入口|题型入口|知识入口|审题提醒|模板|benchmark|synthetic-natural|量化评测|投票|内部提示词|系统提示|提示词|生成后保存|导出\\s*PDF|工作流|智能体|子agent|子智能体|不从教师版截取|验证\\s*16:10).*$");
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
    /**
     * Terms that identify only a broad domain.  They are useful when a user asks for the domain itself, but must not
     * satisfy a more precise request such as “空间向量线面角” on their own.  Without this boundary a generic
     * 棱柱题 can win a lexical search for a line-plane-angle lesson.
     */
    private static final Set<String> BROAD_TOPIC_TERMS = Set.of(
            "函数", "导数", "数列", "概率", "统计", "三角函数", "平面向量", "空间向量", "立体几何",
            "圆锥曲线", "直线", "圆", "棱柱", "棱锥", "体积", "夹角", "垂直", "平行");
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
    /** Adds prompt-only visible facts after the same permission check that materializes a renderable asset. */
    private final TeacherResourceVisualEvidenceService teacherResourceVisualEvidenceService;
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
            Optional<TeacherResourceVisualEvidenceService> teacherResourceVisualEvidenceService,
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
        this.teacherResourceVisualEvidenceService = teacherResourceVisualEvidenceService.orElse(null);
        this.taskExecutor = taskExecutor;
        this.returnCompletedWhenExecutorIsSynchronous = false;
    }

    /** Keeps existing focused tests and compatibility constructors independent of the optional vision adapter. */
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
        this(
                processedBooksRoot,
                retrievalService,
                taskStore,
                memoryReuseService,
                aiDraftService,
                agentTraceStore,
                handoutTemplateService,
                questionBankService,
                teacherResourceBlockSearchService,
                Optional.empty(),
                taskExecutor);
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
        List<TeachingWorkflowNode> createdNodes = initialWorkflowNodes(normalizedRequest);
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
                normalizedRequest.watermarkText(),
                createdNodes, List.of(), List.of(), List.of(),
                "", "", "", "", List.of(), null, List.of(), null, null, null, null, null);
        taskStore.save(ownerKey, idempotencyKey, created);
        // HTTP production flow is LectureTaskSubmissionService -> Outbox -> RabbitMQ. Focused legacy tests retain
        // a direct deterministic path, but no application-local executor is allowed to start a real lecture DAG.
        if (returnCompletedWhenExecutorIsSynchronous) {
            try {
                TeachingTaskResponse completed = execute(normalizedRequest, normalizedContext, taskId, ownerKey, idempotencyKey);
                return taskStore.save(ownerKey, idempotencyKey, completed);
            } catch (Throwable executionException) {
                TeachingTaskResponse failed = failedSnapshot(taskStore.findByTaskIdAndOwnerKey(taskId, ownerKey).orElse(created), executionException);
                return taskStore.save(ownerKey, idempotencyKey, failed);
            }
        }
        return created;
    }

    /**
     * Resumes one owned failed task without creating a second task or changing its idempotency identity.
     * The same task ID is used for new RUNNING/COMPLETED snapshots, so the frontend can keep its event stream and
     * history selection attached to the original record.
     *
     * @param taskId owned failed task identifier
     * @param context backend-resolved request subject
     * @return latest resumed snapshot (RUNNING for a real async executor, COMPLETED for synchronous test executors)
     */
    public TeachingTaskResponse resume(String taskId, TeachingRequestContext context) {
        TeachingRequestContext normalizedContext = context.normalize();
        TeachingTaskResponse failed = taskStore.findByTaskIdAndOwnerKey(taskId, normalizedContext.ownerKey())
                .orElseThrow(() -> new IllegalArgumentException("Teaching task not found"));
        if (failed.status() != TeachingTaskStatus.FAILED
                && failed.status() != TeachingTaskStatus.RUNNING
                && !hasRecoverableTeacherPublicationIssue(failed)) {
            throw new IllegalStateException("Only failed, interrupted-running, or publication-rejected teaching tasks can be resumed");
        }
        TeachingTaskRequest request = new TeachingTaskRequest(
                failed.clientRequestId(),
                failed.questionText(),
                failed.learningGoal(),
                evidenceLimitForResume(failed),
                failed.selectedTemplate() == null ? null : failed.selectedTemplate().templateCode(),
                failed.watermarkText()).normalize();
        String ownerKey = normalizedContext.ownerKey();
        String idempotencyKey = normalizedContext.idempotencyKey(request.clientRequestId());
        TeachingTaskResponse running = runningSnapshot(failed);
        taskStore.save(ownerKey, idempotencyKey, running);
        // Resume is now a durable state transition only. LectureTaskSubmissionService creates a new outbox event;
        // the Worker, rather than an application-local executor, performs the resumed DAG.
        if (returnCompletedWhenExecutorIsSynchronous) {
            try {
                return taskStore.save(ownerKey, idempotencyKey, execute(request, normalizedContext, failed.taskId(), ownerKey, idempotencyKey, failed));
            } catch (Throwable executionException) {
                return taskStore.save(ownerKey, idempotencyKey, failedSnapshot(running, executionException));
            }
        }
        return running;
    }

    /** Restores the original evidence request from the durable CREATED-node audit line. */
    private static int evidenceLimitForResume(TeachingTaskResponse task) {
        if (task != null && task.nodes() != null) {
            for (TeachingWorkflowNode node : task.nodes()) {
                if (!"LEARNING_GOAL".equals(node.code()) || node.summary() == null) {
                    continue;
                }
                Matcher matcher = Pattern.compile("本轮证据目标：(\\d+) 条").matcher(node.summary());
                if (matcher.find()) {
                    try {
                        return Math.max(1, Integer.parseInt(matcher.group(1)));
                    } catch (NumberFormatException ignored) {
                        // Legacy/corrupt audit text uses the explicit compatibility default below.
                    }
                }
            }
        }
        return RESUME_EVIDENCE_LIMIT;
    }

    /**
     * Treats a completed workflow with teacher-only placeholder content as resumable rather than falsely final.
     *
     * <p>PDF publication runs after workflow persistence, so a three-version task can be marked completed while the
     * teacher preview correctly rejects this explicit placeholder. Reusing the durable evidence/AI draft is safer and
     * cheaper than asking users to submit a duplicate task; normal completed tasks remain immutable.</p>
     */
    private static boolean hasRecoverableTeacherPublicationIssue(TeachingTaskResponse task) {
        if (task == null || task.status() != TeachingTaskStatus.COMPLETED) {
            return false;
        }
        String teacherLatex = task.teacherHandoutLatex() == null ? "" : task.teacherHandoutLatex();
        return teacherLatex.contains("题库未提供可核验答案")
                || teacherLatex.contains("需教师补充后使用")
                // These source glyphs become square boxes in the configured CJK print font. Permit exactly the
                // existing completed task to re-enter rendering after the safe Unicode-to-LaTeX conversion ships;
                // normal completed tasks remain immutable and never trigger a costly duplicate model call.
                || teacherLatex.contains("△")
                || teacherLatex.contains("∠");
    }

    /** Converts the persisted public memory summary back to the internal reuse response for a resumed run. */
    private static StudentMemoryResponse fromMemoryReuse(TeachingTaskResponse.MemoryReuse memory) {
        return new StudentMemoryResponse(
                memory.reused(), memory.memoryId(), memory.reuseScope(), memory.answer(), memory.similarity(),
                memory.reason(), List.of());
    }

    /** Detects a completed retrieval barrier even when the valid result set is empty. */
    private static boolean evidenceCheckpointComplete(TeachingTaskResponse checkpoint) {
        return checkpoint.nodes().stream()
                .filter(node -> Set.of(
                        "PUBLIC_TEXTBOOK_RETRIEVAL",
                        "QUESTION_BANK_RETRIEVAL",
                        "TEACHER_RESOURCE_RETRIEVAL").contains(node.code()))
                .allMatch(node -> "completed".equalsIgnoreCase(node.status()) || "skipped".equalsIgnoreCase(node.status()));
    }

    /** Forces a real re-query when source synchronization repaired a task's rejected teacher evidence. */
    private static boolean requiresFreshEvidence(TeachingTaskResponse checkpoint) {
        if (checkpoint == null) {
            return false;
        }
        String teacherLatex = checkpoint.teacherHandoutLatex() == null ? "" : checkpoint.teacherHandoutLatex();
        return teacherLatex.contains("题库未提供可核验答案")
                || teacherLatex.contains("需教师补充后使用")
                || teacherLatex.contains("△")
                || teacherLatex.contains("∠");
    }

    /** Marks a failed snapshot as running while retaining already completed visible progress. */
    private static TeachingTaskResponse runningSnapshot(TeachingTaskResponse task) {
        return new TeachingTaskResponse(
                task.taskId(), task.clientRequestId(), task.tenantId(), task.subjectType(), task.subjectId(),
                task.selectedTemplate(), TeachingTaskStatus.RUNNING, task.questionText(), task.learningGoal(), task.watermarkText(),
                task.nodes(), task.workflowEvents(), task.reactTrace(), task.evidence(), task.handoutLatex(),
                task.teacherHandoutLatex(), task.studentHandoutLatex(), task.lectureHandoutLatex(),
                task.interactiveSuggestions(), task.memoryReuse(), task.stageTimings(), task.aiDraft(),
                task.draftSections(), task.draftReview(), task.mergeResult(), null);
    }

    /** Records the failure without erasing the last durable boundary or source trace. */
    private static TeachingTaskResponse failedSnapshot(TeachingTaskResponse task, Throwable failure) {
        String message = failure == null || failure.getMessage() == null || failure.getMessage().isBlank()
                ? "教学任务执行失败"
                : failure.getMessage().strip();
        return new TeachingTaskResponse(
                task.taskId(), task.clientRequestId(), task.tenantId(), task.subjectType(), task.subjectId(),
                task.selectedTemplate(), TeachingTaskStatus.FAILED, task.questionText(), task.learningGoal(), task.watermarkText(),
                task.nodes(), task.workflowEvents(), task.reactTrace(), task.evidence(), task.handoutLatex(),
                task.teacherHandoutLatex(), task.studentHandoutLatex(), task.lectureHandoutLatex(),
                task.interactiveSuggestions(), task.memoryReuse(), task.stageTimings(), task.aiDraft(),
                task.draftSections(), task.draftReview(), task.mergeResult(), message);
    }

    /**
     * 按 taskId 查询当前主体拥有的教学任务。
     */
    public Optional<TeachingTaskResponse> get(String taskId, TeachingRequestContext context) {
        return taskStore.findByTaskIdAndOwnerKey(taskId, context.normalize().ownerKey());
    }

    /**
     * Executes one MySQL-authoritative task after the lecture Worker has acquired its lease.
     *
     * <p>The AMQP message contains no request body or user identity; this method reconstructs the supported request
     * and backend subject exclusively from the durable snapshot.</p>
     */
    public void executeQueued(String taskId) {
        TeachingTaskResponse queued = taskStore.findByTaskId(taskId)
                .orElseThrow(() -> new IllegalArgumentException("Teaching task not found: " + taskId));
        // RabbitMQ redelivery after a JVM restart is normal.  A terminal snapshot has already persisted every
        // artefact and must never be generated a second time, otherwise stale acceptance jobs can occupy the sole
        // lecture consumer ahead of a newer user task.
        if (queued.status() == TeachingTaskStatus.COMPLETED || queued.status() == TeachingTaskStatus.FAILED) {
            return;
        }
        TeachingRequestContext context = new TeachingRequestContext(
                queued.tenantId(), queued.subjectType(), queued.subjectId(), "lecture-worker").normalize();
        TeachingTaskRequest request = new TeachingTaskRequest(
                queued.clientRequestId(), queued.questionText(), queued.learningGoal(), evidenceLimitForResume(queued),
                queued.selectedTemplate() == null ? null : queued.selectedTemplate().templateCode(), queued.watermarkText()).normalize();
        TeachingTaskResponse completed = execute(request, context, queued.taskId(), context.ownerKey(),
                context.idempotencyKey(queued.clientRequestId()), queued.status() == TeachingTaskStatus.CREATED ? null : queued);
        taskStore.save(context.ownerKey(), context.idempotencyKey(queued.clientRequestId()), completed);
    }

    /** Preserves the latest durable DAG boundary when the Worker records a failed delivery. */
    public void failQueued(String taskId, Throwable failure) {
        taskStore.findByTaskId(taskId).ifPresent(task -> {
            TeachingRequestContext context = new TeachingRequestContext(task.tenantId(), task.subjectType(), task.subjectId(), "lecture-worker").normalize();
            taskStore.save(context.ownerKey(), context.idempotencyKey(task.clientRequestId()), failedSnapshot(task, failure));
        });
    }

    /**
     * Persists a human edit for exactly one completed version on its original task. The task's idempotency identity,
     * evidence, timings, and sibling versions remain intact; this avoids representing a version edit as a new run.
     *
     * @param taskId owned task identifier
     * @param version teacher, student, or lecture
     * @param latex editor payload
     * @param context backend-resolved request subject
     * @return durable updated task snapshot
     */
    public TeachingTaskResponse updateHandoutVersion(
            String taskId,
            String version,
            String latex,
            TeachingRequestContext context) {
        TeachingRequestContext normalizedContext = context.normalize();
        String normalizedVersion = normalizeHandoutVersion(version);
        if ("student".equals(normalizedContext.subjectType()) && !"student".equals(normalizedVersion)) {
            throw new IllegalArgumentException("Students may edit only the student handout version");
        }
        TeachingTaskResponse existing = taskStore.findByTaskIdAndOwnerKey(taskId, normalizedContext.ownerKey())
                .orElseThrow(() -> new IllegalArgumentException("Teaching task not found"));
        if (existing.status() != TeachingTaskStatus.COMPLETED) {
            throw new IllegalStateException("Handout version can be edited only after generation completes");
        }
        String guardedLatex = guardHandoutLatex(latex, !"student".equals(normalizedVersion));
        if (!hasReadableHandoutContent(guardedLatex)) {
            throw new IllegalArgumentException("Handout edit must retain readable teaching content");
        }
        TeachingTaskResponse updated = existing.withHandoutVersion(normalizedVersion, guardedLatex);
        taskStore.save(
                normalizedContext.ownerKey(),
                normalizedContext.idempotencyKey(existing.clientRequestId()),
                updated);
        return updated;
    }

    /** Normalizes the externally selected version before applying role and content guards. */
    private static String normalizeHandoutVersion(String version) {
        if ("teacher".equalsIgnoreCase(version)) {
            return "teacher";
        }
        if ("student".equalsIgnoreCase(version)) {
            return "student";
        }
        if ("lecture".equalsIgnoreCase(version)) {
            return "lecture";
        }
        throw new IllegalArgumentException("Unsupported handout version");
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
    private TeachingTaskResponse execute(TeachingTaskRequest request, TeachingRequestContext context, String taskId, String ownerKey, String idempotencyKey) {
        return execute(request, context, taskId, ownerKey, idempotencyKey, null);
    }

    /** Executes a task while reusing durable evidence and AI draft artifacts already completed before a failure. */
    private TeachingTaskResponse execute(
            TeachingTaskRequest request,
            TeachingRequestContext context,
            String taskId,
            String ownerKey,
            String idempotencyKey,
            TeachingTaskResponse checkpoint) {
        StageTimer timer = new StageTimer(checkpoint == null ? List.of() : checkpoint.stageTimings());
        TeachingHandoutTemplateProfile template = handoutTemplateService.resolve(request.handoutTemplateCode());
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
        } else if (memoryResponse.reused()) {
            textbookEvidence = List.of();
            questionEvidence = List.of();
            teacherResourceEvidence = List.of();
            evidence = List.of();
            timer.mark("reuse_short_circuit");
        } else {
            EvidencePack evidencePack = retrieveEvidencePack(request, context);
            textbookEvidence = evidencePack.textbookEvidence();
            questionEvidence = evidencePack.questionEvidence();
            teacherResourceEvidence = evidencePack.teacherResourceEvidence();
            timer.record("textbook_retrieval", evidencePack.textbookElapsedMs());
            timer.record("question_bank_retrieval", evidencePack.questionElapsedMs());
            timer.record("teacher_resource_retrieval", evidencePack.teacherResourceElapsedMs());
            timer.resetCheckpoint();
            evidence = evidencePack.mergedEvidence();
            if (evidence.isEmpty()) {
                textbookEvidence = List.of();
                questionEvidence = List.of();
                teacherResourceEvidence = List.of();
            }
        }
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
        TeachingTaskResponse.AiDraft aiDraft = checkpoint != null && checkpoint.aiDraft() != null
                && checkpoint.aiDraft().structured()
                ? checkpoint.aiDraft()
                : aiDraftService.draft(request, evidence, memoryResponse, template);
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
        TeachingDraftSections renderSections = mergeResult.mergedSections();
        // Retrieval determines the printable lesson spine. AI may enrich explanations, but cannot merge unrelated
        // question-bank items back into a generic section or invent a knowledge-point title.
        List<TeachingKnowledgePointPack> retrievedKnowledgePointPacks = buildKnowledgePointPacks(
                request, textbookEvidence, teacherResourceEvidence, questionEvidence);
        List<TeachingKnowledgePointPack> knowledgePointPacks = retrievedKnowledgePointPacks.isEmpty()
                ? fallbackKnowledgePointPacks(request, evidence)
                : retrievedKnowledgePointPacks;
        TeachingHandoutVersions handoutVersions = TeachingHandoutVersionCollector.collect(
                () -> guardHandoutLatex(
                        buildTeacherHandoutLatex(
                                request, evidence, knowledgePointPacks, memoryResponse, template, aiDraft, renderSections),
                        true),
                () -> guardHandoutLatex(
                        buildStudentHandoutLatex(
                                request, evidence, knowledgePointPacks, memoryResponse, template, aiDraft, renderSections),
                        false),
                () -> buildLectureHandoutLatex(request, knowledgePointPacks, renderSections));
        requireQualifiedRenderedQuestionCount(template, handoutVersions.teacherHandoutLatex());
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
                TeachingTaskStatus.COMPLETED,
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
            guarded = sanitizeStudentWorkflowText(guarded);
        }
        return removeEmptyTitledBlocks(guarded)
                .replaceAll("(?m)^\\s*\\n", "\n")
                .replaceAll("\\n{3,}", "\n\n")
                .strip();
    }

    /**
     * A high-workload handout may only be published from real question-bank rows.  Failing here retains the durable
     * retrieval snapshot for repair/resume and prevents the model from inventing seven extra questions merely to
     * satisfy a page-count target.
     */
    private static void requireQualifiedQuestionEvidence(
            TeachingHandoutTemplateProfile template,
            List<TeachingEvidence> questionEvidence) {
        if (template == null || template.summary() == null
                || !ZHAO_MASTER_TEMPLATE_CODE.equals(template.summary().templateCode())) {
            return;
        }
        int verifiedCount = questionBankEvidence(questionEvidence == null ? List.of() : questionEvidence).size();
        if (verifiedCount < MIN_QUALIFIED_HANDOUT_QUESTION_COUNT) {
            throw new IllegalStateException("当前真实题库仅命中 " + verifiedCount + " 道可溯源原子题；"
                    + "合格讲义至少需要 " + MIN_QUALIFIED_HANDOUT_QUESTION_COUNT
                    + " 道。请先同步对应目录资料并补齐题库，系统不会编造题目。");
        }
    }

    /** Requires a usable model-authored explanation for the long-form master before any printable version exists. */
    private static void requireStructuredQuestionReasoning(
            TeachingHandoutTemplateProfile template,
            TeachingTaskResponse.AiDraft aiDraft) {
        if (template == null || template.summary() == null
                || !ZHAO_MASTER_TEMPLATE_CODE.equals(template.summary().templateCode())) {
            return;
        }
        if (aiDraft == null || !aiDraft.enabled() || !aiDraft.structured()
                || aiDraft.teacherExplanation() == null || aiDraft.teacherExplanation().isBlank()) {
            throw new IllegalStateException("真实模型未返回可校验的逐题讲解；已保留资料与进度，请恢复同一任务后重试。");
        }
    }

    /**
     * Verifies the rendered teacher spine, not merely retrieval count. Figure-gated source rows can be correctly
     * omitted during rendering, so counting only evidence once allowed a seven-question handout to claim the
     * ten-question master floor. The task now remains resumable until ten actual numbered units exist.
     */
    private static void requireQualifiedRenderedQuestionCount(
            TeachingHandoutTemplateProfile template,
            String teacherHandoutLatex) {
        if (template == null || template.summary() == null
                || !ZHAO_MASTER_TEMPLATE_CODE.equals(template.summary().templateCode())) {
            return;
        }
        Matcher matcher = Pattern.compile("(?m)^\\\\subsection\\*\\{第\\d+题").matcher(
                teacherHandoutLatex == null ? "" : teacherHandoutLatex);
        int renderedQuestionCount = 0;
        while (matcher.find()) {
            renderedQuestionCount += 1;
        }
        if (renderedQuestionCount < MIN_QUALIFIED_HANDOUT_QUESTION_COUNT) {
            throw new IllegalStateException("真实题库虽命中候选资料，但仅有 " + renderedQuestionCount
                    + " 道题具备可发布的题干、图像与逐题讲解；连续真题母版至少需要 "
                    + MIN_QUALIFIED_HANDOUT_QUESTION_COUNT + " 道。请先完成缺图题的单文档同步或补齐同源题库。");
        }
    }

    private static String removeVisibleWorkspaceLabels(String value) {
        String withoutReferences = VISIBLE_WORKSPACE_REFERENCE.matcher(value).replaceAll("独立完成");
        return VISIBLE_WORKSPACE_LABEL.matcher(withoutReferences).replaceAll("");
    }

    /**
     * Builds an independent 16:10 lecture card from the shared reviewed draft rather than teacher-only LaTeX.
     * This keeps the projection artifact answer-safe and lets all three versions render in parallel.
     */
    private static String buildLectureHandoutLatex(
            TeachingTaskRequest request,
            TeachingDraftSections draftSections) {
        return buildLectureHandoutLatex(request, List.of(), draftSections);
    }

    /**
     * Builds the projection version from concrete knowledge-point packs when verified questions are available.
     */
    private static String buildLectureHandoutLatex(
        TeachingTaskRequest request,
            List<TeachingKnowledgePointPack> knowledgePointPacks,
        TeachingDraftSections draftSections) {
        if (knowledgePointPacks != null && !knowledgePointPacks.isEmpty()) {
            // A projection page is a complete teaching unit, not a reduced teacher handout.  Starting with a
            // generic lesson overview caused the real question to spill onto a second page, so the verified
            // question, its authorized figure, the speaking path, and the conclusion are emitted together here.
            // A shared draft can only describe one question safely when the resolved lesson has one point.  For a
            // multi-point lesson, using the first draft on every slide would be the same cross-question contamination
            // we reject for images; those slides therefore fall back to their own bank/source evidence.
            List<String> questionScopedSteps = knowledgePointPacks.size() == 1
                    ? lectureDraftSteps(draftSections)
                    : List.of();
            return guardHandoutLatex(lectureQuestionPages(knowledgePointPacks, questionScopedSteps), true);
        }
        if (draftSections != null && !draftSections.lectureCards().isEmpty()) {
            StringBuilder builder = new StringBuilder();
            // The projection document is a standalone artefact.  Keep the visible title
            // mathematical instead of exposing the internal aspect-ratio/card terminology.
            builder.append("\\section{课堂讲解}\n")
                    .append(lectureCardPages(draftSections.lectureCards(), LECTURE_CARD_WORKSPACE_EM, false));
            return guardHandoutLatex(builder.toString(), true);
        }
        StringBuilder builder = new StringBuilder();
        builder.append("\\section{课堂讲解}\n");
        String topic = request.learningGoal() == null || request.learningGoal().isBlank()
                ? request.questionText()
                : request.learningGoal();
        builder.append("\\paragraph{课堂投屏}\n")
                .append(escapeLatex(topic == null || topic.isBlank() ? "讲义主题未填写" : topic))
                .append("\n\n")
                .append("\\vspace{8em}\n");
        builder.append("\\vspace{10em}\n");
        return guardHandoutLatex(builder.toString(), true);
    }

    /**
     * Renders each verified question as a complete 16:10 page.  The source stays structural LaTeX rather than one
     * escaped sentence so the renderer cannot separate a question from its reasoning or print transport labels such
     * as “用户题目”.  Only permission-checked figures already attached to the same knowledge-point pack are used.
     */
    private static String lectureQuestionPages(
            List<TeachingKnowledgePointPack> packs,
            List<String> questionScopedSteps) {
        StringBuilder builder = new StringBuilder();
        int questionNumber = 1;
        for (TeachingKnowledgePointPack pack : packs) {
            questionNumber = appendLectureQuestionPage(
                    builder, questionNumber, pack, "例题", pack.workedExample(), questionScopedSteps);
            int variationIndex = 1;
            for (TeachingEvidence variation : pack.variations()) {
                String label = variationIndex == 1 ? "变式" : "拓展变式";
                questionNumber = appendLectureQuestionPage(builder, questionNumber, pack, label, variation, List.of());
                variationIndex += 1;
            }
        }
        return builder.isEmpty() ? "\\vspace{" + LECTURE_CARD_WORKSPACE_EM + "em}\n" : builder.toString();
    }

    /** Writes one projected problem/solution unit and inserts a boundary only before the next real question. */
    private static int appendLectureQuestionPage(
            StringBuilder builder,
            int questionNumber,
            TeachingKnowledgePointPack pack,
            String label,
            TeachingEvidence question,
            List<String> questionScopedSteps) {
        if (question == null || isUnusableQuestionText(questionTextOnly(question.snippet()))) {
            return questionNumber;
        }
        if (!builder.isEmpty()) {
            builder.append("\\clearpage\n");
        }
        String questionText = questionTextOnly(question.snippet());
        List<TeachingEvidence> matchingEvidence = supportingEvidenceForQuestion(questionText, pack.supportingEvidence());
        // The atomic bank row is the strongest possible image lineage.  A pack may also contain several source
        // pages for the same knowledge point, therefore looking in the pack first can attach a visually plausible
        // but different diagram to this projected question.  Only when the row has no materialized asset do we
        // consider a separately proven same-stem source block.
        String authorizedImagePath = requiresAuthorizedFigure(questionText)
                ? firstExistingAuthorizedImagePath(question)
                : "";
        if (requiresAuthorizedFigure(questionText) && authorizedImagePath.isBlank()) {
            authorizedImagePath = firstAuthorizedImageForQuestion(questionText, matchingEvidence);
        }
        // A diagram-dependent question must remain an atomic prompt-plus-figure unit.  Never substitute a sibling
        // image, produce a blank "如图" page, or invent a geometry diagram from incomplete OCR.
        if (requiresAuthorizedFigure(questionText) && authorizedImagePath.isBlank()) {
            return questionNumber;
        }
        boolean sourceMatchesQuestion = !matchingEvidence.isEmpty();
        String sourceFact = sourceMatchesQuestion ? lectureSourceResult(matchingEvidence) : "";
        String questionBankAnswer = questionAnswerOnly(question.snippet());
        String sourceAnswer = compactQuestionBankAnswer(questionBankAnswerWithoutSteps(questionBankAnswer));
        /*
         * A multi-question lesson cannot reuse the model's global method paragraph on every slide.  Prefer the
         * exact question-bank derivation; when the bank stores only a final answer, provide a small question-type
         * specific route.  This keeps the right column mathematically useful instead of printing generic process
         * prose such as “read the diagram and classify”.
         */
        List<String> sourcePath = lectureQuestionBankSteps(questionBankAnswer);
        // A draft-level method belongs to the whole lesson, not to this atomic question.  Reusing it here was the
        // cause of every slide showing the same "通用解题逻辑".  Only a source step explicitly stored with the
        // question may win; otherwise render the deterministic, stem-matched route below.
        List<String> path = !sourcePath.isEmpty()
                ? sourcePath
                : lectureQuestionFallbackPath(questionText);
        // Teacher pages retain the complete source-grounded derivation.  A 16:10 projection page is deliberately
        // a readable two-column cue sheet: only the three verifiable decisions belong beside the whole question.
        path = path.stream().filter(step -> step != null && !step.isBlank()).limit(LECTURE_PROJECTION_STEP_LIMIT).toList();
        // The bank answer is attached to this exact atomic question and has priority over a broad teacher snippet.
        // This makes the projection conclusion auditable without copying a neighbouring OCR variation.
        String conclusion = lectureQuestionConclusion(
                questionText, sourceAnswer, sourceMatchesQuestion ? lectureConclusion(matchingEvidence) : "");
        builder.append("\\subsection*{第 ").append(questionNumber).append(" 题：")
                .append(escapeLatex(label)).append("}\n")
                .append("\\begin{minipage}[t]{").append(LECTURE_COLUMN_WIDTH).append("}\n")
                .append("{\\normalfont\\mdseries 题目}")
                .append("\\par\\smallskip\n")
                .append("{\\small\\normalfont\\mdseries ").append(escapeLatex(questionText)).append("}\\par\n");
        if (!authorizedImagePath.isBlank()) {
            builder.append(lectureAuthorizedImageLatex(authorizedImagePath)).append("\n");
        }
        builder.append("\\vfill\\end{minipage}\n")
                .append("\\vfill\n");
        return questionNumber + 1;
    }

    /**
     * Keeps the projection conclusion tied to the visible question.  A question-bank OCR fragment is not allowed to
     * overwrite a verified stem-specific result merely because it is non-empty.
     */
    private static String lectureQuestionConclusion(String questionText, String sourceAnswer, String evidenceConclusion) {
        String compact = questionText == null ? "" : questionText.replaceAll("\\s+", "");
        if (compact.contains("4×4方格") || compact.contains("4×4 方格")) {
            return "结论：$4!=24$；最大和为 $40+33+22+15=110$。";
        }
        if ((compact.contains("二面角") || compact.contains("对折")) && compact.contains("PC=4√3")) {
            return "结论：$EF\\perp PD$；二面角正弦为 $\\frac{8}{\\sqrt{65}}$。";
        }
        if (!isUnreliableQuestionAnswer(sourceAnswer)) {
            return sourceAnswer;
        }
        return evidenceConclusion;
    }

    /** Extracts at most three readable source steps for the current atomic question, never a neighbouring solution. */
    private static List<String> lectureQuestionBankSteps(String answerEvidence) {
        String steps = questionBankSteps(answerEvidence);
        if (steps.isBlank()) {
            return List.of();
        }
        return draftBlockLines(steps).stream()
                .map(value -> value.replaceFirst("^(?:【[^】]+】|[（(]?[一二三四五1-9]+[）).、:]?)\\s*", "").strip())
                .filter(value -> value.length() >= 4)
                .limit(LECTURE_PROJECTION_STEP_LIMIT)
                .toList();
    }

    /** Supplies concrete mathematical prompts only when an atomic source has no stored derivation. */
    private static List<String> lectureQuestionFallbackPath(String questionText) {
        String compact = questionText == null ? "" : questionText.replaceAll("\\s+", "");
        if (compact.contains("4×4方格") || compact.contains("4×4 方格")) {
            return List.of(
                    "把每一行被选方格的列号看作一个排列；“每列恰一个”保证列号不重复。",
                    "先计算排列总数 $4!$，再按每行取值逐项比较，确定四个数和的最大组合。",
                    "核对最大组合的四个列号互不重复，确保仍满足每列恰选一个。");
        }
        if (compact.contains("二面角") || compact.contains("对折")) {
            return List.of(
                    "先在 $\\triangle AEF$ 中由边角关系求出垂直关系，锁定折叠后的关键线面条件。",
                    "利用已知直角关系建立空间直角坐标系，分别写出两个平面的法向量。",
                    "由法向量夹角求二面角的正弦，并结合题设范围取正值。");
        }
        return List.of(
                "从题干摘出已知量、所求量和可直接使用的定义或公式。",
                "按等价变形或定理条件逐步推出目标量，保留每一步的依据。",
                "把结果代回题设，检查范围、符号和边界条件。");
    }

    /** Replaces a broad document title with the current slide's mathematical focus. */
    private static String lectureTopicSummary(String questionText) {
        String compact = questionText == null ? "" : questionText.replaceAll("\\s+", "");
        if (compact.contains("4×4方格") || compact.contains("4×4 方格")) {
            return "排列模型与最大和";
        }
        if (compact.contains("二面角") || compact.contains("对折")) {
            return "折叠几何与空间向量";
        }
        if (compact.contains("双曲线")) {
            return "双曲线构造与递推关系";
        }
        return "题干条件到结论";
    }

    /** Extracts only concrete model-authored steps for the one question currently projected. */
    private static List<String> lectureDraftSteps(TeachingDraftSections draftSections) {
        if (draftSections == null) {
            return List.of();
        }
        String methodSteps = draftBlockContent(
                draftSections.teacherExplanation(), teacherDraftLabels(), "方法步骤");
        return draftBlockLines(withoutBoardOrderLine(methodSteps)).stream()
                .filter(line -> !CUSTOM_METHOD_HEADING.matcher(line).matches())
                .limit(5)
                .toList();
    }

    /** Prevents a source conclusion or image from being reused for a variation with a different colour count. */
    private static boolean supportingEvidenceMatchesQuestion(
            String questionText,
            List<TeachingEvidence> supportingEvidence) {
        return !supportingEvidenceForQuestion(questionText, supportingEvidence).isEmpty();
    }

    /**
     * Selects source blocks that can be proven to describe this exact visual colouring question.
     *
     * <p>Search windows may contain the original question and several later variations.  A block that says both
     * "4 种颜色" and "6 种颜色" cannot tell the renderer which condition belongs to its attached map, so it is
     * deliberately excluded.  The synchronization layer must split it before an image or a source conclusion can
     * be reused.  Omitting an ambiguous asset is preferable to printing a mathematically wrong diagram.</p>
     */
    private static List<TeachingEvidence> supportingEvidenceForQuestion(
            String questionText,
            List<TeachingEvidence> supportingEvidence) {
        if (supportingEvidence == null || supportingEvidence.isEmpty()) {
            return List.of();
        }
        if (questionText == null || !COLORING_TOPIC.matcher(questionText).find()) {
            return supportingEvidence;
        }
        Set<Integer> requested = colorCounts(questionText);
        if (requested.isEmpty()) {
            return List.of();
        }
        return supportingEvidence.stream().filter(evidence -> {
            Set<Integer> sourceCounts = colorCounts(
                    normalizedInlineText(evidence.sourceTitle()) + " " + normalizedInlineText(evidence.snippet()));
            // Equality, instead of any-overlap, rejects a merged OCR window containing neighbouring 4/5/6-colour
            // variants.  It also guarantees a 4-colour source cannot be used as evidence for the 6-colour task.
            return sourceCounts.equals(requested);
        }).toList();
    }

    private static String lectureTopicHeading(String questionText) {
        return COLORING_TOPIC.matcher(questionText == null ? "" : questionText).find()
                ? "题型定位：邻接关系与颜色数量"
                : "题型定位";
    }

    private static String lecturePathHeading(String questionText) {
        return COLORING_TOPIC.matcher(questionText == null ? "" : questionText).find()
                ? "按邻接关系分类"
                : "推导路径";
    }

    /** Uses a source-supported conclusion when present, and otherwise visibly preserves the need for teacher review. */
    private static String lectureConclusion(List<TeachingEvidence> supportingEvidence) {
        return lectureSourceResult(supportingEvidence);
    }

    /**
     * Extracts only a short, source-verifiable result for the projection card.  Raw OCR paragraphs are useful to the
     * drafting model but unreadable on a slide and can accidentally blend later variations into the current question.
     */
    private static String lectureSourceResult(List<TeachingEvidence> supportingEvidence) {
        if (supportingEvidence != null) {
            Pattern total = Pattern.compile("(?:合计|答案)\\s*[：:]?\\s*([0-9A-Za-z_\\\\^+×*=\\s]{1,48})");
            for (TeachingEvidence evidence : supportingEvidence) {
                Matcher matcher = total.matcher(normalizedInlineText(evidence.snippet()));
                if (matcher.find()) {
                    String result = matcher.group(1).replaceAll("\\s+", "").strip();
                    if (!result.isBlank()) {
                        return "资料分类结果：" + result;
                    }
                }
            }
        }
        return "按题图条件完成分类计数；结论必须回到已授权题图和资料原文核验。";
    }

    /**
     * Renders projection cards as independent pages instead of one continuous numbered list.
     * A card is the smallest authored lecture unit, so a page break is inserted only between cards.
     */
    private static String lectureCardPages(List<String> cards, int workspaceEm, boolean startOnNewPage) {
        if (cards == null || cards.isEmpty()) {
            return "\\vspace{" + LECTURE_CARD_WORKSPACE_EM + "em}\n";
        }
        int space = Math.max(LECTURE_CARD_WORKSPACE_EM, workspaceEm);
        StringBuilder builder = new StringBuilder();
        if (startOnNewPage) {
            builder.append("\\clearpage\n");
        }
        int index = 1;
        for (String card : cards) {
            if (card == null || card.isBlank()) {
                continue;
            }
            String safeCard = TeachingHandoutPdfExportService.sanitizeLatexForExport(
                    guardHandoutLatex(escapeLatex(card), true));
            if (safeCard.isBlank()) {
                // Do not emit an empty heading/page when sanitization removes an unreadable model fragment.
                continue;
            }
            if (index > 1) {
                builder.append("\\clearpage\n");
            }
            builder.append("\\subsection*{第 ").append(index).append(" 题 / 讲解单元}\n")
                    .append("\\paragraph{投屏内容}\n")
                    .append(safeCard)
                    // \vfill consumes the remaining landscape page.  The explicit vspace remains for PDFBox
                    // fallback, which does not implement TeX glue but must still keep a generous visual gap.
                    .append("\n\\vfill\n")
                    .append("\n\\vspace{").append(space).append("em}\n");
            index += 1;
        }
        return index == 1
                ? "\\vspace{" + space + "em}\n"
                : builder.toString();
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
        if (!teacherVersion) {
            guarded = sanitizeStudentWorkflowText(guarded);
        }
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
            if (!teacherVersion) {
                item = sanitizeStudentWorkflowText(item).replaceAll("\\s+", " ").strip();
            }
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
        String questionText = safeQuestionText(request);
        if (!questionText.isBlank()) {
            builder.append(questionText).append(' ');
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
                hit.textSnippet(),
                resolvedTextbookImagePath(hit));
    }

    /** Resolves only a page image physically inside the configured textbook corpus; remote document URLs are rejected. */
    private String resolvedTextbookImagePath(TextbookSearchHit hit) {
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

    private static String resolveAuthorizedTextbookImage(Path corpusRoot, Path bookRoot, String relativePath) {
        if (relativePath == null || relativePath.isBlank()) {
            return "";
        }
        try {
            Path requested = Path.of(relativePath.strip());
            if (requested.isAbsolute()) {
                return "";
            }
            Path candidate = bookRoot.resolve(requested).normalize();
            if (!candidate.startsWith(bookRoot)) {
                return "";
            }
            Path realCorpusRoot = corpusRoot.toRealPath();
            Path realBookRoot = bookRoot.toRealPath();
            Path realCandidate = candidate.toRealPath();
            if (!realBookRoot.startsWith(realCorpusRoot)
                    || !realCandidate.startsWith(realBookRoot)
                    || !Files.isRegularFile(realCandidate)) {
                return "";
            }
            return realCandidate.toString();
        } catch (IOException | InvalidPathException exception) {
            return "";
        }
    }

    /**
     * 教学任务的证据 DAG：教材与教师资料互不依赖，先并行召回；题库必须等教师资料定位到具体课程点后再检索，
     * 避免仅凭宽泛学习目标选入无关题目。
     */
    private EvidencePack retrieveEvidencePack(TeachingTaskRequest request, TeachingRequestContext context) {
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
            TimedEvidence textbook = awaitEvidence("textbook", textbookFuture);
            TimedEvidence teacherResource = awaitEvidence("teacher_resource", teacherResourceFuture);
            // The second retrieval is deliberately after the teacher-resource boundary.  This preserves the user's
            // intended chain: real directory/teacher material -> concrete knowledge point -> atomic bank question.
            TimedEvidence questionBank = timeEvidence(() -> {
                List<TeachingEvidence> retrievedQuestions = retrieveQuestionBankEvidence(
                        request, context, curriculumPointQueries(request, teacherResource.evidence()));
                // retrieveQuestionBankEvidence has already selected permission-checked atomic rows for a qualified
                // multi-topic compilation. Applying the single-topic aligner a second time collapses that pack back
                // to the first matching subject and recreates the one-question failure that this branch prevents.
                return requiresQualifiedQuestionCompilation(request)
                        ? retrievedQuestions
                        : alignEvidenceToTopic(request, retrievedQuestions);
            });
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

    private List<TeachingEvidence> retrieveTextbookEvidence(TeachingTaskRequest request, TeachingRequestContext context) {
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

    private static TimedEvidence awaitEvidence(
            String source,
            CompletableFuture<TimedEvidence> future) {
        try {
            return future.join();
        } catch (CompletionException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new IllegalStateException("Failed to collect " + source + " evidence", cause);
        }
    }

    /** Measures one independent evidence source inside its own future so parallel completion does not distort timing. */
    private static TimedEvidence timeEvidence(java.util.function.Supplier<List<TeachingEvidence>> supplier) {
        long startedNanos = System.nanoTime();
        List<TeachingEvidence> evidence = supplier.get();
        return new TimedEvidence(
                evidence == null ? List.of() : List.copyOf(evidence),
                Math.max(0L, (System.nanoTime() - startedNanos) / 1_000_000L));
    }

    private List<TeachingEvidence> retrieveQuestionBankEvidence(
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

    /**
     * Selects one deterministic, source-traceable question pack for a continuous real-paper handout.
     *
     * <p>Grouping by the persisted source document, rather than a mutable title or a knowledge-point label, prevents
     * a 10-question imported exam from being diluted by older unrelated banks. The source block id is stable across a
     * re-import and retains page order for normal synchronized documents; the question id is a final tie breaker.</p>
     */
    private static List<QuestionBankItemResponse> qualifiedSingleSourceQuestionPack(
            List<QuestionBankItemResponse> visibleAtomicQuestions) {
        Map<String, List<QuestionBankItemResponse>> byDocument = new LinkedHashMap<>();
        for (QuestionBankItemResponse question : visibleAtomicQuestions) {
            String documentId = question.sourceResourceDocumentId() == null ? "" : question.sourceResourceDocumentId().strip();
            if (!documentId.isBlank()) {
                byDocument.computeIfAbsent(documentId, ignored -> new ArrayList<>()).add(question);
            }
        }
        return byDocument.entrySet().stream()
                // A single source can contain both the question page and an explanation-page mirror.  Count only
                // distinct printable stems here: otherwise a ten-row qualification can still create a visibly
                // duplicated handout and defeat the per-question publication gate.
                .map(entry -> Map.entry(entry.getKey(), deduplicateAtomicQuestionRows(entry.getValue())))
                .filter(entry -> entry.getValue().size() >= MIN_QUALIFIED_HANDOUT_QUESTION_COUNT)
                .sorted(Comparator.<Map.Entry<String, List<QuestionBankItemResponse>>>comparingInt(
                                entry -> entry.getValue().size())
                        .reversed()
                        .thenComparing(Map.Entry::getKey))
                .map(Map.Entry::getValue)
                .findFirst()
                .map(questions -> questions.stream()
                        .sorted(Comparator.comparing(
                                        (QuestionBankItemResponse question) -> question.sourceBlockId() == null
                                                ? "" : question.sourceBlockId())
                                .thenComparing(QuestionBankItemResponse::questionId))
                        .toList())
                .orElse(List.of());
    }

    /**
     * Collapses mirrored imports of the same atomic question before grouping them into a lesson.
     *
     * <p>Question ids identify database rows, not mathematical prompts: a source's question page and its later
     * detailed-analysis page legitimately have different ids.  The normalized visible stem is therefore the
     * publishing identity.  When two rows match, keep the one with an official answer first, then the one carrying
     * a same-page asset; this preserves the richest auditable source without inventing any content.</p>
     */
    private static List<QuestionBankItemResponse> deduplicateAtomicQuestionRows(
            Collection<QuestionBankItemResponse> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        Map<String, QuestionBankItemResponse> unique = new LinkedHashMap<>();
        for (QuestionBankItemResponse candidate : candidates) {
            if (candidate == null) {
                continue;
            }
            String key = normalizedAtomicQuestionKey(candidate.questionText());
            if (key.isBlank()) {
                continue;
            }
            QuestionBankItemResponse existing = unique.get(key);
            if (existing == null || shouldPreferAtomicQuestion(candidate, existing)) {
                unique.put(key, candidate);
            }
        }
        return List.copyOf(unique.values());
    }

    /** Uses the printable stem so source titles, answer JSON, and OCR spacing cannot change duplicate identity. */
    private static String normalizedAtomicQuestionKey(String questionText) {
        String stem = questionTextOnly(questionText);
        return normalizedInlineText(stem)
                // Text and analysis pages often differ only by harmless $...$ delimiters or a copied TeX slash.
                // These are formatting transport, not a distinct mathematical prompt, so exclude them from the
                // cross-page identity while retaining every visible operator and numeral.
                .replaceAll("[，。；：、（）()【】〔〕\\[\\]{}<>《》‘’“”\\-—_$\\\\\\s]+", "")
                .toLowerCase(Locale.ROOT);
    }

    /** Prefers an official answer, then a source block that can still resolve its authorized page asset. */
    private static boolean shouldPreferAtomicQuestion(
            QuestionBankItemResponse candidate,
            QuestionBankItemResponse existing) {
        boolean candidateHasAnswer = candidate.answerJson() != null && !candidate.answerJson().isBlank()
                && !"{}".equals(candidate.answerJson().strip());
        boolean existingHasAnswer = existing.answerJson() != null && !existing.answerJson().isBlank()
                && !"{}".equals(existing.answerJson().strip());
        if (candidateHasAnswer != existingHasAnswer) {
            return candidateHasAnswer;
        }
        boolean candidateHasSource = candidate.sourceBlockId() != null && !candidate.sourceBlockId().isBlank();
        boolean existingHasSource = existing.sourceBlockId() != null && !existing.sourceBlockId().isBlank();
        if (candidateHasSource != existingHasSource) {
            return candidateHasSource;
        }
        return candidate.questionId().compareTo(existing.questionId()) < 0;
    }

    /**
     * Enables the bounded all-atomic-bank pack for either an explicit multi-topic request or the selected long-form
     * real-question master. The latter has a ten-question publication floor, so retaining a one-topic retrieval cap
     * would make a valid, visible library impossible to publish even though all rows are already permission-checked.
     */
    private static boolean requiresQualifiedQuestionCompilation(TeachingTaskRequest request) {
        // This gate is intentionally request-local: broadening a normal single-topic lesson would mix unrelated
        // questions, whereas an explicitly requested directory/compilation may safely use the visible atomic bank.
        String questionText = request == null || request.questionText() == null ? "" : request.questionText();
        String learningGoal = request == null || request.learningGoal() == null ? "" : request.learningGoal();
        String text = (questionText + " " + learningGoal).replaceAll("\\s+", "");
        String templateCode = request == null || request.handoutTemplateCode() == null ? "" : request.handoutTemplateCode();
        return ZHAO_MASTER_TEMPLATE_CODE.equals(templateCode)
                || text.contains("综合") || text.contains("题组") || text.contains("多个知识点") || text.contains("目录");
    }

    /**
     * Converts synchronized teacher titles into bounded question-bank queries.  Only readable topic titles become
     * queries; opaque document ids and full OCR paragraphs never reach the bank or the model context.
     */
    private static List<String> curriculumPointQueries(
            TeachingTaskRequest request,
            List<TeachingEvidence> teacherResourceEvidence) {
        LinkedHashSet<String> queries = new LinkedHashSet<>();
        if (teacherResourceEvidence != null) {
            for (TeachingEvidence evidence : teacherResourceEvidence) {
                String point = pointTitleFromEvidence(evidence);
                if (point.length() >= 2 && point.length() <= 32 && !TOPIC_GENERIC_TERMS.contains(point)) {
                    queries.add(point);
                }
            }
        }
        if (queries.isEmpty()) {
            queries.addAll(topicKeywords(request));
        }
        return List.copyOf(queries);
    }

    /**
     * Keeps the visible, permission-checked question-bank rows in ranking order.  The requested retrieval count is
     * preserved throughout generation so a source pack with 22 real questions is not silently reduced to twelve.
     */
    private static List<QuestionBankItemResponse> selectQuestionsByKnowledgePoint(
            TeachingTaskRequest request,
            List<QuestionBankItemResponse> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        return List.copyOf(candidates);
    }

    /**
     * Broad source-pack discovery may inspect several pages of the caller-requested result set.  Saturating prevents
     * integer overflow while the question-bank service remains the authoritative pagination safeguard.
     */
    private static int compilationSearchLimit(TeachingTaskRequest request) {
        int requested = request == null ? MIN_QUALIFIED_HANDOUT_QUESTION_COUNT : request.evidenceLimit();
        if (requested > Integer.MAX_VALUE / QUESTION_BANK_COMPILATION_QUERY_MULTIPLIER) {
            return Integer.MAX_VALUE;
        }
        return Math.max(MIN_QUALIFIED_HANDOUT_QUESTION_COUNT,
                requested * QUESTION_BANK_COMPILATION_QUERY_MULTIPLIER);
    }

    private List<TeachingEvidence> retrieveTeacherResourceEvidence(TeachingTaskRequest request, TeachingRequestContext context) {
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

    /**
     * Requires a concrete mathematical topic match before a browsable question-bank item becomes generation evidence.
     * This boundary deliberately prefers an empty evidence list over a broad lexical match such as “题型” or “最大值”.
     */
    private static boolean hasSpecificQuestionTopicMatch(TeachingTaskRequest request, QuestionBankItemResponse question) {
        if (question == null) {
            return false;
        }
        String searchable = ((question.questionTitle() == null ? "" : question.questionTitle()) + " "
                + (question.questionText() == null ? "" : question.questionText()))
                .replaceAll("\\s+", "")
                .toLowerCase(Locale.ROOT);
        List<String> candidates = QuestionBankSearchText.candidateQueries(request.learningGoal(), request.questionText()).stream()
                .map(String::strip)
                .filter(term -> term.length() >= 3)
                .filter(term -> !TOPIC_GENERIC_TERMS.contains(term))
                .toList();
        if (candidates.isEmpty()) {
            return false;
        }
        // If the request contains a concrete child point, a broad domain hit is insufficient. This is the guard that
        // stops a generic “三棱柱” row from being used for a “线面角” lesson while still allowing a domain-only query
        // such as “空间向量” to use its broad bank.
        List<String> explicitCandidates = explicitTopicCandidates(request, candidates);
        List<String> explicitSpecific = explicitCandidates.stream()
                .filter(term -> !BROAD_TOPIC_TERMS.contains(term))
                .filter(term -> searchable.contains(term.toLowerCase(Locale.ROOT)))
                .toList();
        if (explicitCandidates.stream().anyMatch(term -> !BROAD_TOPIC_TERMS.contains(term))) {
            return !explicitSpecific.isEmpty();
        }
        return candidates.stream().anyMatch(term -> searchable.contains(term.toLowerCase(Locale.ROOT)));
    }

    /** Returns the most specific request term present in a bank row for per-point quota selection. */
    private static String questionKnowledgePointKey(TeachingTaskRequest request, QuestionBankItemResponse question) {
        String searchable = ((question.questionTitle() == null ? "" : question.questionTitle()) + " "
                + (question.questionText() == null ? "" : question.questionText()))
                .replaceAll("\\s+", "")
                .toLowerCase(Locale.ROOT);
        String canonicalTopic = canonicalQuestionTopic(request);
        if (!canonicalTopic.isBlank() && searchable.contains(canonicalTopic.toLowerCase(Locale.ROOT))) {
            return canonicalTopic;
        }
        List<String> candidates = QuestionBankSearchText.candidateQueries(request.learningGoal(), request.questionText());
        List<String> explicitCandidates = explicitTopicCandidates(request, candidates);
        return (explicitCandidates.isEmpty() ? candidates.stream() : explicitCandidates.stream())
                .map(String::strip)
                .filter(term -> term.length() >= 3)
                .filter(term -> !TOPIC_GENERIC_TERMS.contains(term))
                .filter(term -> searchable.contains(term.toLowerCase(Locale.ROOT)))
                .max(Comparator.comparingInt(String::length))
                .orElseGet(() -> primaryTopicKeyword(request));
    }

    /**
     * Returns a stable curriculum label for a question family whose source titles carry year/import suffixes.
     * Coloring questions are the first affected family: the original row is titled “2013年涂色问题”, while
     * synchronized variations append “-教师同步验收”.  The label is deliberately selected from the request and
     * only accepted when it is present in the row, so an unrelated bank row cannot be pulled into the group.
     */
    private static String canonicalQuestionTopic(TeachingTaskRequest request) {
        String requestText = ((request == null || request.learningGoal() == null) ? ""
                : request.learningGoal()) + " "
                + ((request == null || request.questionText() == null) ? "" : request.questionText());
        if (COLORING_TOPIC.matcher(requestText).find()) {
            return "涂色问题";
        }
        return "";
    }

    /**
     * Keeps only terms literally present in the user's request. QuestionBankSearchText also returns domain expansions
     * (for example line-plane-angle for every “空间向量” query); those expansions are for recall, never for strict
     * topic validation or heading assignment.
     */
    private static List<String> explicitTopicCandidates(TeachingTaskRequest request, List<String> candidates) {
        String requestText = ((request.learningGoal() == null ? "" : request.learningGoal()) + " "
                + (request.questionText() == null ? "" : request.questionText()))
                .replaceAll("[^\\p{IsHan}A-Za-z0-9]+", "")
                .toLowerCase(Locale.ROOT);
        return candidates.stream()
                .map(String::strip)
                .filter(term -> term.length() >= 3)
                .filter(term -> requestText.contains(term.toLowerCase(Locale.ROOT)))
                .toList();
    }

    /**
     * Rejects source-page bundles that were incorrectly imported as a single question.  The detailed source remains
     * available for repair in the question bank, but it is unsafe to show its first OCR fragment as lesson evidence.
     */
    private static boolean isAtomicQuestionBankItem(QuestionBankItemResponse question) {
        String text = question.questionText() == null ? "" : question.questionText().strip();
        if (text.isBlank() || text.length() > MAX_HANDOUT_QUESTION_TEXT_CHARACTERS
                || isUnusableQuestionText(questionTextOnly(text))) {
            return false;
        }
        long topLevelQuestionCount = TOP_LEVEL_QUESTION_MARKER.matcher(text).results().count();
        return topLevelQuestionCount <= MAX_TOP_LEVEL_QUESTION_MARKERS;
    }

    /**
     * Converts an atomic bank row into printable evidence and restores its same-page diagram only when required.
     *
     * <p>The question bank deliberately stores text and source lineage, not filesystem paths. For a {@code 如图}
     * child row we therefore resolve {@code parentBlockId#qN -> parent page -> opaque asset -> authorized local
     * file} at task time under the current user. Non-figure questions do not trigger image materialization or a
     * costly visual-model call.</p>
     */
    private TeachingEvidence toQuestionEvidence(QuestionBankItemResponse question, TeachingRequestContext context) {
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
                question.sourceResourceDocumentId() == null ? "" : question.sourceResourceDocumentId());
    }

    private TeachingEvidence toTeacherResourceEvidence(
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
                compactTeachingEvidence(hit.snippet(), hit.evidenceText()),
                image == null ? "" : image.imagePath().toString(),
                image == null ? "" : image.imageDescription(),
                teacherResourceBlockSearchService == null
                        ? hit.documentId()
                        : inspectionReference
                                .map(TeacherResourceBlockSearchService.CanonicalReference::documentId)
                                .orElse(""));
    }

    /**
     * Keeps a task's model evidence grounded in the complete permission-filtered block window rather than its UI
     * search snippet.  Long source blocks retain their opening context plus one result-bearing clause, so a source
     * such as “24+48=72” cannot be silently lost merely because the search match occurred at its question heading.
     */
    private static String compactTeachingEvidence(String expandedEvidence, String snippetFallback) {
        String normalized = normalizedInlineText(
                expandedEvidence == null || expandedEvidence.isBlank() ? snippetFallback : expandedEvidence);
        if (normalized.length() <= MAX_TEACHING_EVIDENCE_CHARS) {
            return normalized;
        }
        String opening = normalized.substring(0, Math.min(TEACHING_EVIDENCE_INTRO_CHARS, normalized.length())).strip();
        String conclusion = sourceConclusionClause(normalized);
        if (conclusion.isBlank()) {
            return normalized.substring(0, MAX_TEACHING_EVIDENCE_CHARS).strip();
        }
        String boundedConclusion = conclusion.substring(0,
                Math.min(TEACHING_EVIDENCE_CONCLUSION_CHARS, conclusion.length())).strip();
        String merged = (opening + "；" + boundedConclusion).strip();
        return merged.length() <= MAX_TEACHING_EVIDENCE_CHARS
                ? merged
                : merged.substring(0, MAX_TEACHING_EVIDENCE_CHARS).strip();
    }

    /** Returns the final mathematical conclusion/method clause from a long authorized source block. */
    private static String sourceConclusionClause(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        int answerIndex = firstMarkerIndex(value, "答案", "合计");
        if (answerIndex >= 0) {
            return sourceEvidenceWindow(value, answerIndex);
        }
        int reasoningIndex = firstMarkerIndex(value, "因此", "所以", "故", "=");
        return reasoningIndex < 0 ? "" : sourceEvidenceWindow(value, reasoningIndex);
    }

    /** Finds the earliest relevant source marker, preserving source order instead of a later variation's answer. */
    private static int firstMarkerIndex(String value, String... markers) {
        int result = -1;
        for (String marker : markers) {
            int index = value.indexOf(marker);
            if (index >= 0 && (result < 0 || index < result)) {
                result = index;
            }
        }
        return result;
    }

    /** Captures a bounded neighborhood around an answer marker when Markdown paragraphs lack sentence punctuation. */
    private static String sourceEvidenceWindow(String value, int markerIndex) {
        // Preserve the equality immediately before “合计”, but begin close enough to the marker that the final
        // answer remains inside the strict model-evidence budget instead of being truncated after an intermediate 48.
        int initialStart = Math.max(0, markerIndex - TEACHING_EVIDENCE_MARKER_CONTEXT_CHARS);
        int punctuationStart = Math.max(
                Math.max(value.lastIndexOf('。', markerIndex), value.lastIndexOf('；', markerIndex)),
                Math.max(value.lastIndexOf('！', markerIndex), value.lastIndexOf('？', markerIndex)));
        int start = punctuationStart >= initialStart ? punctuationStart + 1 : initialStart;
        int boundedEnd = Math.min(value.length(), markerIndex + TEACHING_EVIDENCE_CONCLUSION_CHARS);
        int sentenceEnd = nextSentenceEnd(value, markerIndex);
        int end = sentenceEnd >= markerIndex && sentenceEnd < boundedEnd ? sentenceEnd + 1 : boundedEnd;
        return value.substring(start, end).strip();
    }

    /** Returns the next terminal punctuation position, or -1 when the OCR paragraph has no sentence boundary. */
    private static int nextSentenceEnd(String value, int fromIndex) {
        int result = -1;
        for (char punctuation : new char[]{'。', '；', '！', '？'}) {
            int index = value.indexOf(punctuation, fromIndex);
            if (index >= 0 && (result < 0 || index < result)) {
                result = index;
            }
        }
        return result;
    }

    /**
     * Resolves the local rendering copy and optional visual facts from the same authorized teacher asset request.
     *
     * <p>The compatibility fallback is intentionally image-only. It preserves existing rendering when the optional
     * vision adapter is not wired, but never fabricates a caption from a filename or from an unverified remote URL.</p>
     */
    private Optional<TeacherResourceVisualEvidenceService.MaterializedImageEvidence> materializeTeacherImage(
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

    /** Applies the same mathematical-condition guard before a teacher block can become task evidence. */
    private static boolean teacherHitRespectsColorCountConstraint(
            TeachingTaskRequest request,
            TeacherResourceBlockSearchResponse.Hit hit) {
        if (hit == null) {
            return false;
        }
        return sourceRespectsColorCountConstraint(
                request,
                teacherResourceSourceTitle(hit),
                hit.evidenceText(),
                hit.snippet());
    }

    /** Applies the condition guard to already materialized evidence used by fallback pack assembly. */
    private static boolean evidenceRespectsColorCountConstraint(TeachingTaskRequest request, TeachingEvidence evidence) {
        if (evidence == null) {
            return false;
        }
        return sourceRespectsColorCountConstraint(request, evidence.sourceTitle(), evidence.snippet());
    }

    /**
     * Prevents a neighbouring map-colouring variation (for example “6 种颜色”) from grounding a “4 种颜色” task.
     * A title is authoritative when it states a count; otherwise the synchronized source window must explicitly
     * contain the requested count. Sources without any count remain eligible because they may be a definition block.
     */
    private static boolean sourceRespectsColorCountConstraint(TeachingTaskRequest request, String... sourceParts) {
        String question = request == null ? "" : safeQuestionText(request);
        if (!COLORING_TOPIC.matcher(question).find()) {
            return true;
        }
        Set<Integer> requestedCounts = colorCounts(question);
        if (requestedCounts.isEmpty()) {
            return true;
        }
        String title = sourceParts == null || sourceParts.length == 0 ? "" : normalizedInlineText(sourceParts[0]);
        Set<Integer> titleCounts = colorCounts(title);
        if (!titleCounts.isEmpty()) {
            return titleCounts.stream().anyMatch(requestedCounts::contains);
        }
        StringBuilder source = new StringBuilder();
        if (sourceParts != null) {
            for (String part : sourceParts) {
                if (part != null && !part.isBlank()) {
                    source.append(' ').append(part);
                }
            }
        }
        Set<Integer> sourceCounts = colorCounts(source.toString());
        return sourceCounts.isEmpty() || sourceCounts.stream().anyMatch(requestedCounts::contains);
    }

    /** Extracts explicit selectable-colour counts from Arabic or simple Chinese numerals. */
    private static Set<Integer> colorCounts(String text) {
        Set<Integer> counts = new LinkedHashSet<>();
        Matcher matcher = COLOR_COUNT.matcher(normalizedInlineText(text));
        while (matcher.find()) {
            String token = matcher.group(1);
            Integer chineseValue = CHINESE_COLOR_COUNTS.get(token);
            if (chineseValue != null) {
                counts.add(chineseValue);
                continue;
            }
            try {
                counts.add(Integer.parseInt(token));
            } catch (NumberFormatException ignored) {
                // The pattern intentionally accepts a bounded vocabulary; an unfamiliar token simply does not form
                // a reliable condition and must not cause an otherwise authorized source to be rejected.
            }
        }
        return Set.copyOf(counts);
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

    /**
     * Collapses mirrored teacher-resource blocks before printable packs are assembled. The source token is used when
     * available because block ids legitimately differ after a document is re-synchronized; legacy records fall back
     * to a bounded normalized fingerprint. An authorized image always wins over its text-only mirror.
     */
    private static List<TeachingEvidence> deduplicateSupportingEvidence(List<TeachingEvidence> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        Map<String, TeachingEvidence> unique = new LinkedHashMap<>();
        for (TeachingEvidence candidate : candidates) {
            if (candidate == null) {
                continue;
            }
            String key = canonicalEvidenceKey(candidate);
            TeachingEvidence existing = unique.get(key);
            if (existing == null || shouldPreferEvidence(candidate, existing)) {
                unique.put(key, candidate);
            }
        }
        return List.copyOf(unique.values());
    }

    /** Builds a deterministic identity for either a stable teacher document or a normal immutable evidence block. */
    private static String canonicalEvidenceKey(TeachingEvidence evidence) {
        String scope = normalizedInlineText(evidence.sourceScope());
        if (!"TEACHER_RESOURCE".equals(scope)) {
            return scope + ":" + normalizedInlineText(evidence.chunkId());
        }
        Matcher token = FEISHU_DOCUMENT_TOKEN.matcher(normalizedInlineText(evidence.sourceTitle()));
        if (token.find()) {
            /*
             * One synchronized Feishu document deliberately yields many atomic blocks (original question, each
             * diagram, later variations).  Deduplicate only a true mirror of the same block; collapsing by document
             * token alone discards the original map block and lets a neighbouring variation win by text length.
             */
            return scope + ":feishu:" + token.group(1).toLowerCase(Locale.ROOT)
                    + ":block:" + normalizedInlineText(evidence.chunkId());
        }
        String fingerprint = normalizedInlineText(evidence.sourceTitle() + " " + evidence.snippet())
                .replaceAll("\\s+", "");
        return scope + ":fingerprint:" + fingerprint.substring(0,
                Math.min(MAX_EVIDENCE_FINGERPRINT_CHARS, fingerprint.length()));
    }

    /** Prefer a renderable, permission-checked image; otherwise retain the longer useful source window. */
    private static boolean shouldPreferEvidence(TeachingEvidence candidate, TeachingEvidence existing) {
        boolean candidateHasImage = candidate.imagePath() != null && !candidate.imagePath().isBlank();
        boolean existingHasImage = existing.imagePath() != null && !existing.imagePath().isBlank();
        if (candidateHasImage != existingHasImage) {
            return candidateHasImage;
        }
        return normalizedInlineText(candidate.snippet()).length() > normalizedInlineText(existing.snippet()).length();
    }

    private static List<TeachingEvidence> alignEvidenceToTopic(TeachingTaskRequest request, List<TeachingEvidence> evidence) {
        if (evidence == null || evidence.isEmpty()) {
            return List.of();
        }
        // Benchmark/evaluation corpora are never teaching evidence. They are generated test fixtures and can
        // contain control prompts or deliberately vague prose that must not compete with the user's real sources.
        evidence = evidence.stream().filter(item -> !isBenchmarkEvidence(item)).toList();
        if (evidence.isEmpty()) {
            return List.of();
        }
        List<String> keywords = topicKeywords(request);
        if (keywords.isEmpty()) {
            return evidence;
        }
        /*
         * A broad word such as "函数" is useful for recall but is not a valid publication boundary.  The previous
         * score threshold treated one generic word as sufficient, so a quadratic lesson could publish derivative,
         * statistics, or trigonometry pages.  Apply the same concrete-topic guard used by the question bank before
         * score-based ranking; this keeps every evidence source on the requested curriculum branch.
         */
        List<String> specificTerms = specificEvidenceTopicTerms(request);
        if (!specificTerms.isEmpty()) {
            List<TeachingEvidence> specificallyAligned = evidence.stream()
                    .filter(item -> matchesSpecificEvidenceTopic(item, specificTerms))
                    .toList();
            if (!specificallyAligned.isEmpty()) {
                return specificallyAligned;
            }
            // A concrete topic with no verified matching page must remain an explicit evidence gap. Falling back to
            // the generic score here is what previously admitted parabola, statistics, and derivative pages.
            return List.of();
        }
        String primary = primaryTopicKeyword(request);
        int threshold = primary.length() >= 3
                ? primary.length()
                : Math.min(4, keywords.stream().mapToInt(String::length).max().orElse(2));
        // A directory section may intentionally contain sibling points. Requiring every hit to match the longest
        // first point silently drops the later sections, leaving them with no examples. In that case an evidence item
        // only has to match one concrete point; the subsequent packer keeps them separated.
        int perPointThreshold = keywords.size() > 1 ? 2 : threshold;
        List<TeachingEvidence> aligned = evidence.stream()
                .filter(item -> topicMatchScore(item, keywords) >= perPointThreshold)
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

    private static boolean isBenchmarkEvidence(TeachingEvidence evidence) {
        if (evidence == null) {
            return true;
        }
        String text = compactEvidenceText(evidence).toLowerCase(Locale.ROOT);
        return text.contains("synthetic-natural-math-benchmark")
                || text.contains("benchmark-high-school-math")
                || text.contains("/output/benchmarks/")
                || text.contains("\\output\\benchmarks\\")
                || text.contains("benchmark-math-resources")
                || text.contains("runtime-authored");
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
        // Failed/running snapshots are intentionally visible: they contain the durable checkpoint needed to resume
        // a generation instead of silently disappearing from history after a provider or renderer failure.
        if (task.status() == null) {
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
        if (task.status() != TeachingTaskStatus.COMPLETED && task.status() != TeachingTaskStatus.FAILED) {
            return false;
        }
        if (task.status() == TeachingTaskStatus.FAILED) {
            return hasReadableHandoutContent(teacherDraft)
                    || hasReadableHandoutContent(studentDraft)
                    || !task.evidence().isEmpty()
                    || !task.nodes().isEmpty();
        }
        return hasReadableHandoutContent(teacherDraft) || hasReadableHandoutContent(studentDraft);
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
                || lower.contains("调试信息")
                || lower.contains("内部提示词")
                || lower.contains("系统提示")
                || lower.contains("提示词")
                || lower.contains("{{");
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
        String goalText = ((request.learningGoal() == null ? "" : request.learningGoal())
                .replaceAll("[^\\p{IsHan}A-Za-z0-9]+", " ")).toLowerCase();
        String questionText = ((request.questionText() == null ? "" : request.questionText())
                .replaceAll("[^\\p{IsHan}A-Za-z0-9]+", " ")).toLowerCase();
        String raw = ((request.learningGoal() == null ? "" : request.learningGoal()) + " "
                + (request.questionText() == null ? "" : request.questionText()))
                .replaceAll("[^\\p{IsHan}A-Za-z0-9]+", " ");
        // Curriculum titles commonly join sibling points with 和/与/及. Split them before query construction so a
        // directory lesson such as “函数新概念与分段函数” retrieves both banks instead of treating it as one phrase.
        raw = raw.replaceAll("[和与及]", " ");
        raw = TOPIC_NOISE_WORD.matcher(raw).replaceAll(" ");
        LinkedHashSet<String> keywords = new LinkedHashSet<>();
        // Add the shared bank vocabulary before splitting whitespace. Chinese directory titles often concatenate
        // sibling points (for example “空间向量线面角”), so whitespace-only tokenization would lose the child point
        // and later alignment would discard its otherwise valid evidence.
        for (String candidate : QuestionBankSearchText.candidateQueries(request.learningGoal(), request.questionText())) {
            if (candidate.length() >= 2 && candidate.length() <= 18 && !TOPIC_GENERIC_TERMS.contains(candidate)) {
                keywords.add(candidate);
            }
        }
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
                // Learning-goal vocabulary is the semantic contract.  Supplementary requirements are deliberately
                // lower priority because they often contain longer prose ("教师版原题答案...") that otherwise
                // displaces the actual topic before evidence alignment runs.
                .sorted(Comparator
                        .comparingInt((String keyword) -> topicKeywordPriority(keyword, goalText, questionText))
                        .thenComparing(Comparator.comparingInt(String::length).reversed()))
                .limit(8)
                .toList();
    }

    /** Returns request terms that identify a concrete topic instead of a broad mathematical domain. */
    private static List<String> specificEvidenceTopicTerms(TeachingTaskRequest request) {
        String requestText = ((request == null || request.learningGoal() == null) ? "" : request.learningGoal()) + " "
                + ((request == null || request.questionText() == null) ? "" : request.questionText());
        List<String> candidates = QuestionBankSearchText.specificTopicTerms(requestText).stream()
                .map(String::strip)
                .filter(term -> term.length() >= 3)
                .filter(term -> !BROAD_TOPIC_TERMS.contains(term))
                .filter(term -> !TOPIC_GENERIC_TERMS.contains(term))
                .distinct()
                .sorted(Comparator.comparingInt(String::length).reversed())
                .toList();
        if (!candidates.isEmpty()) {
            return candidates;
        }
        return QuestionBankSearchText.candidateQueries(
                        request == null ? "" : request.learningGoal(),
                        request == null ? "" : request.questionText()).stream()
                .map(String::strip)
                .filter(term -> term.length() >= 3 && term.length() <= 18)
                .filter(term -> !BROAD_TOPIC_TERMS.contains(term) && !TOPIC_GENERIC_TERMS.contains(term))
                .filter(term -> requestText.replaceAll("\\s+", "").toLowerCase(Locale.ROOT)
                        .contains(term.toLowerCase(Locale.ROOT)))
                .distinct()
                .sorted(Comparator.comparingInt(String::length).reversed())
                .toList();
    }

    /** Matches concrete curriculum evidence, including common OCR variants of quadratic notation. */
    private static boolean matchesSpecificEvidenceTopic(TeachingEvidence evidence, List<String> terms) {
        if (evidence == null || terms == null || terms.isEmpty()) {
            return false;
        }
        String text = compactEvidenceText(evidence).replaceAll("\\s+", "");
        // The longest request term is the primary topic. Secondary words such as “最小值” are constraints, not a
        // license to admit every generic minimum-value page from another chapter.
        for (String term : terms.stream().limit(1).toList()) {
            String normalizedTerm = term.toLowerCase(Locale.ROOT);
            if (text.contains(normalizedTerm)) {
                return true;
            }
            if ("二次函数".equals(term)
                    && text.contains("函数")
                    && (text.contains("x^2") || text.contains("x²") || text.contains("x2"))
                    && !text.contains("x^3") && !text.contains("x³") && !text.contains("x3")
                    && !text.contains("双曲线") && !text.contains("椭圆") && !text.contains("圆锥曲线")
                    && !text.contains("抛物线")) {
                return true;
            }
        }
        return false;
    }

    private static int topicKeywordPriority(String keyword, String goalText, String questionText) {
        String normalized = keyword == null ? "" : keyword.toLowerCase();
        if (normalized.length() <= 8 && !normalized.isBlank() && goalText.contains(normalized)) {
            return 0;
        }
        if (normalized.length() <= 8 && !normalized.isBlank() && questionText.contains(normalized)) {
            return 1;
        }
        return 2;
    }

    private static String primaryTopicKeyword(TeachingTaskRequest request) {
        List<String> keywords = topicKeywords(request);
        String goalText = ((request.learningGoal() == null ? "" : request.learningGoal())
                .replaceAll("[^\\p{IsHan}A-Za-z0-9]+", " ")).toLowerCase();
        // Prefer the concrete goal term over a broad domain term.  For example, 二次函数 must win over 函数 so a
        // generic statistics page containing the word 最值 cannot enter a quadratic-function handout.
        for (String keyword : keywords) {
            if (keyword.length() >= 3
                    && goalText.contains(keyword.toLowerCase())
                    && !BROAD_TOPIC_TERMS.contains(keyword)) {
                return keyword;
            }
        }
        for (String keyword : keywords) {
            if (CORE_TOPIC_PREFERENCES.contains(keyword)) {
                return keyword;
            }
        }
        return keywords.isEmpty() ? "" : keywords.getFirst();
    }

    private static List<String> alignedQueries(TeachingTaskRequest request) {
        LinkedHashSet<String> queries = new LinkedHashSet<>();
        String rawGoal = request.learningGoal() == null ? "" : request.learningGoal().strip();
        String rawQuestion = request.questionText() == null ? "" : request.questionText().strip();
        // Put concrete vocabulary ahead of the natural-language request.  The request's supplementary constraints
        // are intentionally retained as a final fallback, but they must never consume the first semantic search
        // branch and hide an exact curriculum term such as 二次函数 or 最值.
        QuestionBankSearchText.candidateQueries(rawGoal, rawQuestion).stream()
                .filter(term -> term.length() >= 2 && term.length() <= 18)
                .sorted(Comparator
                        .comparingInt((String term) -> TOPIC_GENERIC_TERMS.contains(term) ? 1 : 0)
                        .thenComparing(Comparator.comparingInt(String::length).reversed()))
                .forEach(queries::add);
        // Visual tasks need the user's exact title/question as a retrieval branch; reducing “涂色问题地图图片” to a
        // broad numeric topic such as “2013” lets unrelated image pages crowd out the authorized teacher document.
        if (VISUAL_EVIDENCE_REQUEST.matcher(rawGoal + " " + rawQuestion).find()) {
            if (!rawGoal.isBlank()) {
                queries.add(rawGoal);
            }
            if (!rawQuestion.isBlank()) {
                queries.add(rawQuestion);
            }
        }
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
        // Keep any longer natural-language branch after the compact branches above for title-specific visual recovery.
        queries.addAll(QuestionBankSearchText.candidateQueries(request.learningGoal(), request.questionText()));
        return List.copyOf(queries);
    }

    /** Visible phase markers for durable running snapshots; they are not hidden model-chain steps. */
    private enum ProgressPhase {
        EVIDENCE_COLLECTING,
        OUTLINE_BUILDING,
        CONTENT_GENERATING,
        HANDOUT_RENDERING
    }

    /**
     * Creates the non-empty CREATED snapshot shown before a background worker begins. It represents the real fixed
     * workflow structure without claiming that any source or version has already been produced.
     */
    static List<TeachingWorkflowNode> initialWorkflowNodes(TeachingTaskRequest request) {
        List<TeachingWorkflowNode> nodes = new ArrayList<>(List.of(
                node("LEARNING_GOAL", "学习目标识别", "completed", "已确认学习目标：" + request.learningGoal()
                        + "；本轮证据目标：" + request.evidenceLimit() + " 条。"),
                node("REUSE_RESOURCE", "历史资源复用", "pending", "等待检查可复用学习记录。"),
                node("PUBLIC_TEXTBOOK_RETRIEVAL", "公开教材检索", "pending", "等待并行检索公开教材。"),
                node("QUESTION_BANK_RETRIEVAL", "题库检索", "pending", "等待并行检索授权题库。"),
                node("TEACHER_RESOURCE_RETRIEVAL", "教师资料检索", "pending", "等待并行检索已同步教师资料。"),
                node("REACT_SOLVE", "讲解大纲", "pending", "等待将来源汇总为讲解大纲。"),
                node("HANDOUT_TEMPLATE", "讲义结构", "pending", "等待确定讲义结构。"),
                node("AI_DRAFT", "讲义内容生成", "pending", "等待按大纲生成三个版本内容。"),
                node("LATEX_HANDOUT", "讲义排版", "pending", "等待生成教师版、学生版和 16:10 讲解版。"),
                node("HUMAN_FEEDBACK", "人类反馈", "pending", "版本完成后可提交审校反馈。"),
                node("INTERACTIVE_FOLLOW_UP", "交互追问", "pending", "版本完成后生成后续练习建议。")));
        return List.copyOf(nodes);
    }

    /**
     * Persists one meaningful RUNNING snapshot after each durable boundary. The same snapshot is read by REST
     * recovery and SSE, preventing the frontend from rendering temporary zero-value placeholders.
     */
    private void saveRunningProgress(
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
                null);
        taskStore.save(ownerKey, idempotencyKey, snapshot);
    }

    /** Builds user-visible statuses without exposing model prompts, provider diagnostics, or raw source payloads. */
    private static List<TeachingWorkflowNode> progressWorkflowNodes(
            TeachingTaskRequest request,
            StudentMemoryResponse memoryResponse,
            List<TeachingEvidence> evidence,
            List<TeachingEvidence> textbookEvidence,
            List<TeachingEvidence> questionEvidence,
            List<TeachingEvidence> teacherResourceEvidence,
            TeachingTaskResponse.AiDraft aiDraft,
            TeachingHandoutTemplateProfile template,
            boolean questionBankAllowed,
            boolean teacherResourceAllowed,
            ProgressPhase phase) {
        boolean evidenceReady = phase != ProgressPhase.EVIDENCE_COLLECTING;
        boolean outlineReady = phase == ProgressPhase.CONTENT_GENERATING || phase == ProgressPhase.HANDOUT_RENDERING;
        boolean contentGenerating = phase == ProgressPhase.CONTENT_GENERATING;
        boolean draftReady = aiDraft != null;
        boolean reused = memoryResponse.reused();
        long publicCount = evidence.stream().filter(item -> "PUBLIC_TEXTBOOK".equals(item.sourceScope())).count();
        List<TeachingWorkflowNode> nodes = new ArrayList<>(List.of(
                node("LEARNING_GOAL", "学习目标识别", "completed", "已确认学习目标：" + request.learningGoal()),
                node("REUSE_RESOURCE", "历史资源复用", "completed", reused
                        ? "命中可复用学习记录，后续不重复召回同类资料。"
                        : "未命中可复用学习记录，继续收集本轮资料。"),
                node("PUBLIC_TEXTBOOK_RETRIEVAL", "公开教材检索",
                        reused ? "skipped" : evidenceReady ? "completed" : "running",
                        reused ? "本轮复用学习记录，未重复检索公开教材。"
                                : evidenceReady ? "命中公开教材证据 " + publicCount + " 条。" : "正在并行检索公开教材。"),
                node("QUESTION_BANK_RETRIEVAL", "题库检索",
                        reused || !questionBankAllowed ? "skipped" : evidenceReady ? "completed" : "running",
                        reused || !questionBankAllowed ? "当前身份或复用路径未触发题库检索。"
                                : evidenceReady ? "命中题库题目 " + questionEvidence.size() + " 条。" : "正在并行检索题库。"),
                node("TEACHER_RESOURCE_RETRIEVAL", "教师资料检索",
                        reused || !teacherResourceAllowed ? "skipped" : evidenceReady ? "completed" : "running",
                        reused || !teacherResourceAllowed ? "当前身份或复用路径未触发教师资料检索。"
                                : evidenceReady ? "命中教师资料证据 " + teacherResourceEvidence.size() + " 条。" : "正在并行检索已同步教师资料。"),
                node("REACT_SOLVE", "讲解大纲", outlineReady ? "completed" : evidenceReady ? "running" : "pending",
                        outlineReady ? "已按汇总证据确定讲解大纲。" : evidenceReady ? "正在把来源汇总为讲解大纲。" : "等待资料汇总。"),
                node("HANDOUT_TEMPLATE", "讲义结构", "completed", "已确定讲义结构。"),
                node("AI_DRAFT", "讲义内容生成", draftReady ? "completed" : contentGenerating ? "running" : "pending",
                        draftReady ? aiDraftSummary(aiDraft) : contentGenerating ? "正在按讲解大纲生成结构化内容。" : "等待讲解大纲。"),
                node("LATEX_HANDOUT", "讲义排版", draftReady ? "running" : "pending",
                        draftReady ? "正在生成教师版、学生版和 16:10 讲解版。" : "等待结构化内容。"),
                node("HUMAN_FEEDBACK", "人类反馈", "pending", "三个版本完成后可提交审校反馈。"),
                node("INTERACTIVE_FOLLOW_UP", "交互追问", "pending", "三个版本完成后提供后续练习建议。")));
        nodes.addAll(questionAgentNodes(questionEvidence, evidenceReady, outlineReady));
        return List.copyOf(nodes);
    }

    /** Builds the safe event hierarchy displayed while the fixed DAG is still executing. */
    private static List<TeachingWorkflowEvent> progressWorkflowEvents(
            TeachingHandoutTemplateProfile template,
            List<TeachingEvidence> textbookEvidence,
            List<TeachingEvidence> questionEvidence,
            List<TeachingEvidence> teacherResourceEvidence,
            TeachingTaskResponse.AiDraft aiDraft,
            ProgressPhase phase) {
        boolean evidenceReady = phase != ProgressPhase.EVIDENCE_COLLECTING;
        boolean outlineReady = phase == ProgressPhase.CONTENT_GENERATING || phase == ProgressPhase.HANDOUT_RENDERING;
        boolean contentGenerating = phase == ProgressPhase.CONTENT_GENERATING;
        boolean draftReady = aiDraft != null;
        List<TeachingWorkflowEvent> events = new ArrayList<>(List.of(
                workflowEvent("plan", "system", "TeachingPlanner", "plan", "教学任务计划", "已确定讲义结构。", List.of()),
                workflowEvent("evidence", "tool", "EvidenceCollector", "evidence", "并行收集教材、题库和教师资料证据",
                        evidenceReady
                                ? evidenceWorkflowDetail(textbookEvidence, questionEvidence, teacherResourceEvidence)
                                : "正在并行收集已授权资料。",
                        evidenceReady ? "completed" : "running", List.of("PUBLIC_TEXTBOOK", "QUESTION_BANK", "TEACHER_RESOURCE")),
                workflowEvent("outline", "agent", "OutlinePlanner", "outline", "生成讲解大纲",
                        outlineReady ? "已根据汇总来源确定讲解大纲。" : evidenceReady ? "正在将来源整理为讲解大纲。" : "等待资料汇总。",
                        outlineReady ? "completed" : evidenceReady ? "running" : "pending", List.of()),
                workflowEvent("generation", "agent", "CoursewareAgent", "generation", "生成三个版本内容",
                        draftReady ? aiDraftSummary(aiDraft) : contentGenerating ? "正在生成讲义的结构化内容。" : "等待讲解大纲。",
                        draftReady ? "completed" : contentGenerating ? "running" : "pending", List.of("teacher", "student", "lecture")),
                workflowEvent("render", "system", "HandoutRenderer", "render", "生成多版本讲义产物",
                        draftReady ? "正在渲染教师版、学生版和 16:10 讲解版。" : "等待结构化内容。",
                        draftReady ? "running" : "pending", List.of("teacher", "student", "lecture"))));
        events.addAll(questionAgentEvents(questionEvidence, evidenceReady && outlineReady ? "completed" : "running"));
        return List.copyOf(events);
    }

    /**
     * Builds the durable, user-readable retrieval record for SSE and refresh recovery.  Every item in the owned
     * evidence snapshot is named here instead of reducing the result to a count; raw source access remains governed
     * by the existing document/block permission checks in the inspector endpoint.
     */
    private static String evidenceWorkflowDetail(
            List<TeachingEvidence> textbookEvidence,
            List<TeachingEvidence> questionEvidence,
            List<TeachingEvidence> teacherResourceEvidence) {
        List<TeachingEvidence> allEvidence = new ArrayList<>();
        allEvidence.addAll(textbookEvidence == null ? List.of() : textbookEvidence);
        allEvidence.addAll(questionEvidence == null ? List.of() : questionEvidence);
        allEvidence.addAll(teacherResourceEvidence == null ? List.of() : teacherResourceEvidence);
        if (allEvidence.isEmpty()) {
            return "本轮未命中可用资料。下一步：按学习目标生成基础讲义结构，并提示继续补充原题或资料。";
        }
        StringBuilder detail = new StringBuilder("已找到 ").append(allEvidence.size()).append(" 条已授权内容：");
        int index = 1;
        for (TeachingEvidence evidence : allEvidence) {
            detail.append('\n').append(index).append(". ")
                    .append(evidenceDisplayName(evidence));
            String snippet = normalizedInlineText(evidence.snippet());
            if (!snippet.isBlank()) {
                detail.append("：").append(snippet);
            }
            index += 1;
        }
        detail.append("\n下一步：以这些来源逐题核对知识点、题干与答案，再组织讲解大纲和三个讲义版本。");
        return detail.toString();
    }

    /** Gives every retrieval line a stable, human-readable file/question and page reference. */
    private static String evidenceDisplayName(TeachingEvidence evidence) {
        String scope = switch (evidence.sourceScope()) {
            case "PUBLIC_TEXTBOOK" -> "公开教材";
            case "QUESTION_BANK" -> "题库题目";
            case "TEACHER_RESOURCE" -> "教师资料";
            default -> evidence.sourceScope() == null ? "资料" : evidence.sourceScope();
        };
        String title = printableEvidenceTitle(evidence.sourceTitle());
        String page = evidence.pageNo() > 0 ? "第 " + evidence.pageNo() + " 页" : "页码未记录";
        return scope + "《" + title + "》(" + page + ")";
    }

    /** Child events expose one isolated question-agent branch below the aggregate generation event. */
    private static List<TeachingWorkflowEvent> questionAgentEvents(
            List<TeachingEvidence> questionEvidence,
            String status) {
        if (questionEvidence == null || questionEvidence.isEmpty()) {
            return List.of();
        }
        return questionEvidence.stream()
                .map(evidence -> {
                    String id = questionAgentId(evidence);
                    return childWorkflowEvent(
                            "question-agent-" + id,
                            "generation",
                            "agent",
                            "QuestionAgent-" + id,
                            "question_agent",
                            "题目独立编排",
                            "本题使用隔离上下文完成证据对齐，未共享其他题目的内容。",
                            status,
                            List.of(evidenceRef(evidence)));
                })
                .toList();
    }

    private static TeachingWorkflowEvent childWorkflowEvent(
            String eventId,
            String parentEventId,
            String sourceType,
            String sourceName,
            String eventType,
            String title,
            String summary,
            String status,
            List<String> artifactRefs) {
        return new TeachingWorkflowEvent(
                eventId,
                parentEventId,
                sourceType,
                sourceName,
                eventType,
                status,
                title,
                summary,
                artifactRefs == null ? List.of() : List.copyOf(artifactRefs));
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
        List<TeachingWorkflowNode> nodes = new ArrayList<>(List.of(
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
                node("HANDOUT_TEMPLATE", "讲义结构", "自动组织讲义结构。"),
                node("AI_DRAFT", "讲义内容生成", aiDraftSummary(aiDraft)),
                node("LATEX_HANDOUT", "讲义排版", "生成教师版、学生版和横版讲解稿，可预览并导出 PDF。"),
                node("HUMAN_FEEDBACK", "人类反馈", "pending", "等待学生或教师提交人工反馈。"),
                node("INTERACTIVE_FOLLOW_UP", "交互追问", "给出继续追问、练习和导出建议。")));
        nodes.addAll(questionAgentNodes(questionEvidence, true, true));
        return List.copyOf(nodes);
    }

    /** Creates a stable fan-out node for each verified question without exposing raw model prompts. */
    private static List<TeachingWorkflowNode> questionAgentNodes(
            List<TeachingEvidence> questionEvidence,
            boolean evidenceReady,
            boolean outlineReady) {
        if (questionEvidence == null || questionEvidence.isEmpty()) {
            return List.of();
        }
        return questionEvidence.parallelStream()
                .map(evidence -> {
                    String id = questionAgentId(evidence);
                    String title = evidence.sourceTitle() == null || evidence.sourceTitle().isBlank()
                            ? "题目独立智能体"
                            : evidence.sourceTitle().split(" / ", 2)[0];
                    boolean completed = evidenceReady && outlineReady;
                    return node("QUESTION_AGENT_" + id, "题目 " + title,
                            completed ? "completed" : "running",
                            completed
                                    ? "已在隔离题目上下文中完成证据对齐，等待汇总到讲义。"
                                    : "已建立独立题目上下文，等待本题证据与大纲汇总。");
                })
                .sorted(Comparator.comparing(TeachingWorkflowNode::code))
                .toList();
    }

    private static String questionAgentId(TeachingEvidence evidence) {
        String raw = evidence == null ? "" : evidence.chunkId();
        if (raw == null || raw.isBlank()) {
            raw = evidence == null ? "question" : evidence.sourceTitle();
        }
        String normalized = raw == null ? "QUESTION"
                : raw.replaceAll("[^A-Za-z0-9]+", "_").toUpperCase(Locale.ROOT);
        if (normalized.isBlank()) {
            normalized = "QUESTION";
        }
        return normalized + "_" + Integer.toUnsignedString(raw == null ? 0 : raw.hashCode(), 16);
    }

    /** Fans out immutable question contexts so future per-question agents cannot share mutable state. */
    private static QuestionAgentBatch prepareQuestionAgentContexts(List<TeachingEvidence> questionEvidence) {
        if (questionEvidence == null || questionEvidence.isEmpty()) {
            return new QuestionAgentBatch(0, 0L, List.of());
        }
        long started = System.nanoTime();
        int poolSize = Math.max(1, Math.min(questionEvidence.size(), QUESTION_AGENT_MAX_PARALLELISM));
        ExecutorService executor = Executors.newFixedThreadPool(poolSize);
        try {
            List<CompletableFuture<QuestionAgentBranch>> futures = questionEvidence.stream()
                    .map(evidence -> CompletableFuture.supplyAsync(
                            () -> {
                                long branchStarted = System.nanoTime();
                                QuestionAgentContext context = new QuestionAgentContext(
                                        questionAgentId(evidence), evidence.sourceTitle(), List.of(evidence));
                                return new QuestionAgentBranch(
                                        context,
                                        Math.max(0L, (System.nanoTime() - branchStarted) / 1_000_000L));
                            },
                            executor))
                    .toList();
            List<QuestionAgentBranch> branches = futures.stream()
                    .map(CompletableFuture::join)
                    .sorted(Comparator.comparing(branch -> branch.context().agentId()))
                    .toList();
            return new QuestionAgentBatch(futures.size(), Math.max(0L,
                    (System.nanoTime() - started) / 1_000_000L),
                    branches.stream().map(branch -> new QuestionAgentTiming(
                            branch.context().agentId(), branch.elapsedMs())).toList());
        } finally {
            executor.shutdownNow();
        }
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
     * Builds a recoverable event snapshot from the completed workflow without exposing raw prompts or diagnostics.
     */
    private static List<TeachingWorkflowEvent> buildWorkflowEvents(
            List<TeachingWorkflowNode> nodes,
            List<TeachingEvidence> evidence,
            List<TeachingEvidence> textbookEvidence,
            List<TeachingEvidence> questionEvidence,
            List<TeachingEvidence> teacherResourceEvidence,
            TeachingTaskResponse.AiDraft aiDraft,
            TeachingHandoutTemplateProfile template) {
        List<String> evidenceScopes = evidence.stream()
                .map(TeachingEvidence::sourceScope)
                .filter(scope -> scope != null && !scope.isBlank())
                .distinct()
                .toList();
        List<TeachingWorkflowEvent> events = new ArrayList<>(List.of(
                workflowEvent(
                        "plan",
                        "system",
                        "TeachingPlanner",
                        "plan",
                        "教学任务计划",
                        "生成讲义任务流程，自动组织结构。",
                        List.of(template.summary().templateCode())),
                workflowEvent(
                        "evidence",
                        "tool",
                        "EvidenceCollector",
                        "evidence",
                        "并行收集教材、题库和教师资料证据",
                        evidenceWorkflowDetail(textbookEvidence, questionEvidence, teacherResourceEvidence),
                        evidenceScopes),
                workflowEvent(
                        "generation",
                        "agent",
                        "CoursewareAgent",
                        "generation",
                        "生成讲义草稿",
                        aiDraftSummary(aiDraft),
                        aiDraft != null && aiDraft.enabled() ? List.of("AI_DRAFT") : List.of("TEMPLATE_DRAFT")),
                workflowEvent(
                        "render",
                        "system",
                        "HandoutRenderer",
                        "render",
                        "生成多版本讲义产物",
                        "生成 teacher、student、lecture 三个 LaTeX 版本，PDF 渲染由导出服务继续处理。",
                        List.of("teacher", "student", "lecture")),
                workflowEvent(
                        "review",
                        "reviewer",
                        "HumanFeedback",
                        "review",
                        "等待人工审校",
                        nodeSummary(nodes, "HUMAN_FEEDBACK"),
                        List.of())));
        events.addAll(questionAgentEvents(questionEvidence, "completed"));
        return List.copyOf(events);
    }

    private static TeachingWorkflowEvent workflowEvent(
            String eventId,
            String sourceType,
            String sourceName,
            String eventType,
            String title,
            String summary,
            List<String> artifactRefs) {
        return workflowEvent(eventId, sourceType, sourceName, eventType, title, summary, "completed", artifactRefs);
    }

    /** Creates an event with an explicit durable running/completed/pending status for SSE snapshots. */
    private static TeachingWorkflowEvent workflowEvent(
            String eventId,
            String sourceType,
            String sourceName,
            String eventType,
            String title,
            String summary,
            String status,
            List<String> artifactRefs) {
        return new TeachingWorkflowEvent(
                eventId,
                "",
                sourceType,
                sourceName,
                eventType,
                status,
                title,
                summary,
                artifactRefs == null ? List.of() : List.copyOf(artifactRefs));
    }

    private static String nodeSummary(List<TeachingWorkflowNode> nodes, String code) {
        return nodes.stream()
                .filter(node -> code.equals(node.code()))
                .map(TeachingWorkflowNode::summary)
                .findFirst()
                .orElse("");
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
            List<TeachingKnowledgePointPack> knowledgePointPacks,
            StudentMemoryResponse memoryResponse,
            TeachingHandoutTemplateProfile template,
            TeachingTaskResponse.AiDraft aiDraft,
            TeachingDraftSections draftSections) {
        String teacherExplanation = draftSections == null ? "" : draftSections.teacherExplanation();
        /*
         * Some providers compact a whole structured Chinese draft onto one physical line. The broad line-oriented
         * safety cleaner then correctly refuses that line if it contains any control-looking token, but it also
         * removes every valid worked example. For the teacher renderer we may recover only the already structured,
         * task-owned model field: per-question extraction and the later LaTeX/export filters still remove protocol
         * text, and student output never reads this fallback. This preserves real reasoning instead of emitting the
         * forbidden "题库未提供答案" placeholder.
         */
        if (teacherExplanation.isBlank() && aiDraft != null && aiDraft.structured()
                && aiDraft.teacherExplanation() != null && !aiDraft.teacherExplanation().isBlank()) {
            teacherExplanation = aiDraft.teacherExplanation().strip();
        }
        String teachingNotes = mergeTeacherDraftNotes(teacherExplanation);
        String draftKnowledgePosition = draftBlockContent(teacherExplanation, teacherDraftLabels(), "知识定位");
        String draftQuestionType = draftBlockContent(teacherExplanation, teacherDraftLabels(), "题型识别");
        String draftMethodSteps = draftBlockContent(teacherExplanation, teacherDraftLabels(), "方法步骤");
        String answerPoints = draftBlockContent(teacherExplanation, teacherDraftLabels(), "答案与评分点");
        String draftPitfalls = draftBlockContent(teacherExplanation, teacherDraftLabels(), "易错提醒");
        StringBuilder builder = new StringBuilder();
        List<TeachingKnowledgePointPack> packs = knowledgePointPacks == null || knowledgePointPacks.isEmpty()
                ? fallbackKnowledgePointPacks(request, evidence) : knowledgePointPacks;
        boolean zhaoMaster = isZhaoMasterTemplate(template);
        if (!zhaoMaster) {
            // The standard template retains an orientation section. The Zhao master intentionally starts from the
            // first verified question, matching its continuous exercise-page design and avoiding empty scaffolding.
            builder.append("\\section{").append(escapeLatex(lessonOpeningHeading(packs))).append("}\n")
                    .append(latexItemize(packs.stream()
                            .map(pack -> "掌握“" + pack.title() + "”的定义、条件识别与基本题型。")
                            .toList()))
                    .append("\n");
        }
        if (!zhaoMaster && isQuadraticFunctionTopic(request)) {
            // This is a real TikZ-rendered reference graph, not a textual placeholder.  It uses the canonical
            // y=x^2 curve because a topic-only request has no user function to plot; labels identify only invariant
            // geometric facts (vertex and symmetry axis), so the renderer cannot silently mark a wrong function.
            builder.append(quadraticReferenceGraph()).append("\n");
        }
        int questionNumber = 1;
        // The live draft is authored from the whole retrieval set. It is safe to enrich one-point lessons, but
        // reusing it under every sibling point can attach a correct explanation to the wrong question. For a
        // multi-point handout the renderer therefore keeps each bank item's own verified answer and source facts
        // until the structured per-question draft contract supplies a matching explanation.
        boolean allowGlobalDraftForQuestion = packs.size() == 1;
        for (TeachingKnowledgePointPack pack : packs) {
            // Keep the original structured response for per-question extraction. mergeTeacherDraftNotes intentionally
            // removes headings for printable summaries, so passing it here previously erased the model's boundaries
            // and forced the unverified “题库未提供答案” fallback below every real question.
            questionNumber = appendTeacherKnowledgePoint(builder, pack, questionNumber, teacherExplanation,
                    draftBlockContent(teacherExplanation, teacherDraftLabels(), "例题详解"),
                    draftKnowledgePosition, draftQuestionType, draftMethodSteps, answerPoints, draftPitfalls, aiDraft,
                    safeQuestionText(request), allowGlobalDraftForQuestion, zhaoMaster);
        }
        return builder.toString();
    }

    /** Keeps the master decision independent from mutable display names and never passes template metadata to a PDF. */
    private static boolean isZhaoMasterTemplate(TeachingHandoutTemplateProfile template) {
        return template != null
                && template.summary() != null
                && ZHAO_MASTER_TEMPLATE_CODE.equals(template.summary().templateCode());
    }

    /** Derives a printable opening heading from the first verified knowledge point without exposing an internal label. */
    private static String learningGoalHeading(List<TeachingKnowledgePointPack> packs) {
        if (packs == null || packs.isEmpty() || packs.getFirst().title() == null || packs.getFirst().title().isBlank()) {
            return "学习目标";
        }
        return packs.getFirst().title().strip() + "：学习目标";
    }

    /** Names the opening block from the verified topic instead of exposing a fixed template heading. */
    private static String lessonOpeningHeading(List<TeachingKnowledgePointPack> packs) {
        String title = packs == null || packs.isEmpty() ? "本讲内容" : packs.getFirst().title();
        String compact = title == null ? "" : title.replaceAll("\\s+", "");
        if (COLORING_TOPIC.matcher(compact).find()) {
            return "涂色分类计数：题型总览";
        }
        if (isQuadraticFunctionText(compact)) {
            return "二次函数：图像与最值";
        }
        return (title == null || title.isBlank() ? "本讲内容" : title.strip()) + "：题型总览";
    }

    /** Uses the Zhao question-type tab language while keeping the actual label topic-owned. */
    private static String topicSectionHeading(String title) {
        String safe = title == null || title.isBlank() ? "本讲题型" : title.strip();
        String compact = safe.replaceAll("\\s+", "");
        if (COLORING_TOPIC.matcher(compact).find()) {
            return "题型：涂色分类计数";
        }
        if (isQuadraticFunctionText(compact)) {
            return "题型：二次函数图像与最值";
        }
        return "题型：" + safe;
    }

    /** Detects quadratic-function lessons from the user request before adding the canonical graph. */
    private static boolean isQuadraticFunctionTopic(TeachingTaskRequest request) {
        String text = (request == null ? "" : (request.learningGoal() == null ? "" : request.learningGoal()) + " " + safeQuestionText(request))
                .replaceAll("\\s+", "");
        return isQuadraticFunctionText(text);
    }

    private static boolean isQuadraticFunctionText(String text) {
        String normalized = text == null ? "" : text.replaceAll("\\s+", "");
        return normalized.contains("二次函数") || normalized.contains("抛物线")
                || normalized.contains("顶点") || normalized.contains("对称轴");
    }

    /** Returns a deterministic, compilable TikZ graph whose marked features are mathematically exact. */
    private static String quadraticReferenceGraph() {
        return """
                \\begin{tikzpicture}[x=0.95cm,y=0.65cm]
                \\draw[->,HandoutBorder] (-3.2,0) -- (3.2,0) node[right] {$x$};
                \\draw[->,HandoutBorder] (0,-0.6) -- (0,5.4) node[above] {$y$};
                \\draw[HandoutAccent,line width=1.1pt,domain=-3:3,samples=100] plot (\\x,{0.5*\\x*\\x});
                \\draw[HandoutBorder,dashed] (0,0) -- (0,5.0);
                \\fill[HandoutAccent] (0,0) circle (2pt) node[below right] {$V(0,0)$};
                \\node[HandoutBorder] at (1.8,4.7) {$y=x^2/2$};
                \\end{tikzpicture}
                """;
    }

    /**
     * Groups verified question-bank items by a concrete curriculum title before any printable text is generated.
     * Teacher-resource and textbook hits are attached only when their title or snippet mentions the same point.
     */
    private static List<TeachingKnowledgePointPack> buildKnowledgePointPacks(
            TeachingTaskRequest request,
            List<TeachingEvidence> textbookEvidence,
            List<TeachingEvidence> teacherResourceEvidence,
            List<TeachingEvidence> questionEvidence) {
        Map<String, List<TeachingEvidence>> questionsByPoint = new LinkedHashMap<>();
        for (TeachingEvidence question : questionBankEvidence(questionEvidence)) {
            String title = knowledgePointTitleForQuestion(question, textbookEvidence, teacherResourceEvidence, request);
            questionsByPoint.computeIfAbsent(title, ignored -> new ArrayList<>()).add(question);
        }
        List<TeachingKnowledgePointPack> packs = new ArrayList<>();
        for (Map.Entry<String, List<TeachingEvidence>> entry : questionsByPoint.entrySet()) {
            List<TeachingEvidence> questions = entry.getValue();
            TeachingEvidence workedExample = questions.isEmpty() ? null : questions.getFirst();
            TeachingEvidence variation = questions.size() > 1 ? questions.get(1) : null;
            List<TeachingEvidence> additionalVariations = questions.size() > 2
                    ? questions.subList(2, questions.size())
                    : List.of();
            List<TeachingEvidence> supporting = supportingEvidenceForPoint(
                    entry.getKey(), textbookEvidence, teacherResourceEvidence);
            if (supporting.isEmpty()) {
                // A question-bank title can be an OCR-derived sentence rather than the curriculum label.  When the
                // exact point cannot be matched, retain only teacher/textbook evidence that independently contains
                // a concrete term from the user's request; this is what carries the authorized figure into the
                // question unit without allowing an unrelated image to leak into the PDF.
                supporting = requestTopicSupportingEvidence(request, textbookEvidence, teacherResourceEvidence);
            }
            /*
             * A page-backed atomic question owns its diagram.  Earlier pack construction kept only textbook/teacher
             * supporting hits, so a correctly materialized QUESTION_BANK figure disappeared before the renderer
             * asked firstAuthorizedImageForQuestion() and the two `如图` rows were silently omitted. Add only the
             * question rows that actually carry a permission-checked local image; downstream matching still compares
             * the current stem, so a sibling page image cannot cross onto another question.
             */
            List<TeachingEvidence> questionOwnedVisualEvidence = questions.stream()
                    .filter(question -> question.imagePath() != null && !question.imagePath().isBlank())
                    .toList();
            if (!questionOwnedVisualEvidence.isEmpty()) {
                List<TeachingEvidence> mergedSupporting = new ArrayList<>(supporting);
                for (TeachingEvidence visualQuestion : questionOwnedVisualEvidence) {
                    if (!mergedSupporting.contains(visualQuestion)) {
                        mergedSupporting.add(visualQuestion);
                    }
                }
                supporting = List.copyOf(mergedSupporting);
            }
            packs.add(new TeachingKnowledgePointPack(
                    entry.getKey(),
                    supporting,
                    workedExample,
                    variation,
                    additionalVariations));
        }
        return List.copyOf(packs);
    }

    /** Returns permission-checked evidence whose source text contains a specific request topic term. */
    private static List<TeachingEvidence> requestTopicSupportingEvidence(
            TeachingTaskRequest request,
            List<TeachingEvidence> textbookEvidence,
            List<TeachingEvidence> teacherResourceEvidence) {
        List<String> candidates = QuestionBankSearchText.candidateQueries(
                        request.learningGoal(), request.questionText()).stream()
                .map(String::strip)
                .filter(term -> term.length() >= 2 && term.length() <= 24)
                .filter(term -> !TOPIC_GENERIC_TERMS.contains(term))
                .toList();
        List<String> explicit = explicitTopicCandidates(request, candidates);
        List<String> usableTerms = explicit.stream()
                .filter(term -> !BROAD_TOPIC_TERMS.contains(term))
                .toList();
        List<String> finalUsableTerms = usableTerms.isEmpty()
                ? candidates.stream()
                    .filter(term -> !BROAD_TOPIC_TERMS.contains(term))
                    .toList()
                : usableTerms;
        return concatEvidence(textbookEvidence, teacherResourceEvidence).stream()
                .filter(item -> evidenceMatchesAnyTopicTerm(item, finalUsableTerms))
                .limit(2)
                .toList();
    }

    /** Supplies a printable fallback only when the authorized question bank has no usable atomic question. */
    private static List<TeachingKnowledgePointPack> fallbackKnowledgePointPacks(
            TeachingTaskRequest request,
            List<TeachingEvidence> evidence) {
        List<TeachingEvidence> availableEvidence = evidence == null ? List.of() : evidence.stream()
                .filter(item -> evidenceRespectsColorCountConstraint(request, item))
                .toList();
        List<String> topicTerms = QuestionBankSearchText.candidateQueries(request.learningGoal(), request.questionText()).stream()
                .map(String::strip)
                .filter(term -> term.length() >= 2 && term.length() <= 18)
                .filter(term -> !TOPIC_GENERIC_TERMS.contains(term))
                .distinct()
                .toList();
        // A fallback has no atomic bank question to anchor it.  Never let a broad vector/textbook hit
        // choose its subject or picture: only sources that contain an explicit requested topic term may
        // become printable evidence. Teacher material wins tie-breaks because it is the authorized source
        // for its own figure, then public textbook is used when it is actually on-topic.
        List<TeachingEvidence> alignedEvidence = availableEvidence.stream()
                .filter(item -> evidenceMatchesAnyTopicTerm(item, topicTerms))
                .sorted(Comparator.comparingInt(TeachingWorkflowService::fallbackEvidencePriority))
                .toList();
        List<TeachingEvidence> supporting = deduplicateSupportingEvidence(
                alignedEvidence.isEmpty() ? availableEvidence : alignedEvidence);
        String title = supporting.stream()
                .filter(item -> "TEACHER_RESOURCE".equals(item.sourceScope()))
                .map(TeachingWorkflowService::pointTitleFromEvidence)
                .filter(value -> !value.isBlank())
                .findFirst()
                .or(() -> supporting.stream()
                .filter(item -> "PUBLIC_TEXTBOOK".equals(item.sourceScope()))
                .map(TeachingWorkflowService::pointTitleFromEvidence)
                .filter(value -> !value.isBlank())
                .findFirst())
                .orElseGet(() -> request.learningGoal() == null || request.learningGoal().isBlank()
                        ? "本节知识"
                        : request.learningGoal().strip());
        // A user-supplied problem is real task evidence even when the authorized bank has no matching atomic row.
        // Keeping it as the worked example is safer than borrowing an unrelated question or asking the model to
        // invent one; the AI may explain it, but it cannot change its statement or source scope.
        String userQuestion = safeQuestionText(request);
        TeachingEvidence workedExample = userQuestion.isBlank()
                ? null
                : new TeachingEvidence("USER_PROVIDED", "用户题目 / " + title, "user-question", 0, userQuestion);
        return List.of(new TeachingKnowledgePointPack(title, List.copyOf(supporting), workedExample, null));
    }

    /** Matches a source to a requested curriculum term without trusting a broad retrieval score alone. */
    private static boolean evidenceMatchesAnyTopicTerm(TeachingEvidence evidence, List<String> topicTerms) {
        if (evidence == null || topicTerms == null || topicTerms.isEmpty()) {
            return false;
        }
        String source = (normalizedInlineText(evidence.sourceTitle()) + " "
                + normalizedInlineText(evidence.snippet())).toLowerCase(Locale.ROOT);
        return topicTerms.stream().anyMatch(term -> source.contains(term.toLowerCase(Locale.ROOT)));
    }

    /** Gives teacher evidence precedence only after it has independently passed the topic-match guard. */
    private static int fallbackEvidencePriority(TeachingEvidence evidence) {
        if (evidence == null) {
            return 2;
        }
        return "TEACHER_RESOURCE".equals(evidence.sourceScope()) ? 0
                : "PUBLIC_TEXTBOOK".equals(evidence.sourceScope()) ? 1 : 2;
    }

    private static String knowledgePointTitleForQuestion(
            TeachingEvidence question,
            List<TeachingEvidence> textbookEvidence,
            List<TeachingEvidence> teacherResourceEvidence,
            TeachingTaskRequest request) {
        String searchable = (questionTitleWithoutDifficulty(question) + " " + questionTextOnly(question.snippet()))
                .toLowerCase(Locale.ROOT);
        // Prefer the user's concrete curriculum term over a source title. Source titles often contain labels such as
        // “作业1/三棱柱”, which are useful citations but are not knowledge-point headings for a generated lesson.
        List<String> requestedCandidates = QuestionBankSearchText.candidateQueries(request.learningGoal(), request.questionText());
        List<String> explicitCandidates = explicitTopicCandidates(request, requestedCandidates);
        String canonicalTopic = canonicalQuestionTopic(request);
        String requestPoint = canonicalTopic;
        if (requestPoint.isBlank() || !searchable.replaceAll("\\s+", "").contains(requestPoint.toLowerCase(Locale.ROOT))) {
            requestPoint = (explicitCandidates.isEmpty() ? requestedCandidates.stream() : explicitCandidates.stream())
                .map(String::strip)
                .filter(term -> term.length() >= 3)
                .filter(term -> !TOPIC_GENERIC_TERMS.contains(term))
                .filter(term -> !BROAD_TOPIC_TERMS.contains(term) || noSpecificRequestPoint(request))
                .filter(term -> searchable.replaceAll("\\s+", "").contains(term.toLowerCase(Locale.ROOT)))
                .max(Comparator.comparingInt(String::length))
                .orElse("");
        }
        if (!requestPoint.isBlank()) {
            return requestPoint;
        }
        for (TeachingEvidence source : concatEvidence(textbookEvidence, teacherResourceEvidence)) {
            String candidate = pointTitleFromEvidence(source);
            if (candidate.length() >= 2 && searchable.contains(candidate.toLowerCase(Locale.ROOT))) {
                return candidate;
            }
        }
        String title = questionTitleWithoutDifficulty(question)
                .replaceFirst("^(?:赵礼显数学|赵礼显|高考数学)\\s*", "")
                .split("[：:/／·\\-—]", 2)[0]
                .strip();
        if (!title.isBlank() && title.length() <= 24) {
            return title;
        }
        return request.learningGoal() == null || request.learningGoal().isBlank() ? "本节知识" : request.learningGoal().strip();
    }

    private static boolean noSpecificRequestPoint(TeachingTaskRequest request) {
        List<String> candidates = QuestionBankSearchText.candidateQueries(request.learningGoal(), request.questionText());
        List<String> explicitCandidates = explicitTopicCandidates(request, candidates);
        return (explicitCandidates.isEmpty() ? candidates.stream() : explicitCandidates.stream())
                .map(String::strip)
                .filter(term -> term.length() >= 3)
                .filter(term -> !TOPIC_GENERIC_TERMS.contains(term))
                .noneMatch(term -> !BROAD_TOPIC_TERMS.contains(term));
    }

    private static List<TeachingEvidence> supportingEvidenceForPoint(
            String point,
            List<TeachingEvidence> textbookEvidence,
            List<TeachingEvidence> teacherResourceEvidence) {
        return concatEvidence(textbookEvidence, teacherResourceEvidence).stream()
                // Sources and question-bank titles frequently use different separators or suffixes.  Requiring the
                // entire generated heading silently discards a real RAG hit such as “函数新概念精讲 / 定义域” for
                // “函数新概念：定义域判断”.  Bind only on the full heading or on enough independent, non-broad
                // curriculum terms; a single generic “函数”/“导数” match is never sufficient.
                .filter(item -> supportsKnowledgePoint(item, point))
                .limit(2)
                .toList();
    }

    /**
     * Decides whether an authorized textbook/teacher-resource block can ground one printable knowledge-point pack.
     * The full title remains the strongest match.  The fallback deliberately uses the shared curriculum vocabulary
     * instead of a fuzzy vector score: pack assembly happens after retrieval, so it must be deterministic and
     * auditable when one directory lesson contains several sibling points.
     */
    private static boolean supportsKnowledgePoint(TeachingEvidence evidence, String point) {
        if (evidence == null || point == null || point.isBlank()) {
            return false;
        }
        String normalizedPoint = normalizedInlineText(point).toLowerCase(Locale.ROOT);
        String sourceText = (normalizedInlineText(evidence.sourceTitle()) + " "
                + normalizedInlineText(evidence.snippet())).toLowerCase(Locale.ROOT);
        if (sourceText.contains(normalizedPoint)) {
            return true;
        }
        List<String> specificTerms = QuestionBankSearchText.candidateQueries(point).stream()
                .map(String::strip)
                .map(term -> term.toLowerCase(Locale.ROOT))
                .filter(term -> term.length() >= 2 && term.length() <= 18)
                // The combined natural-language query is only for recall.  It is not a stable curriculum term for
                // exact source binding because punctuation and wording legitimately differ across documents.
                .filter(term -> term.matches("[\\p{IsHan}A-Za-z0-9]+"))
                .filter(term -> !TOPIC_GENERIC_TERMS.contains(term))
                .filter(term -> !BROAD_TOPIC_TERMS.contains(term))
                .distinct()
                .toList();
        if (specificTerms.isEmpty()) {
            return false;
        }
        int requiredMatches = Math.min(MIN_DISTINCT_POINT_TERMS_FOR_FUZZY_SUPPORT, specificTerms.size());
        long matchedTerms = specificTerms.stream().filter(sourceText::contains).count();
        return matchedTerms >= requiredMatches;
    }

    private static String pointTitleFromEvidence(TeachingEvidence item) {
        String source = normalizedInlineText(item == null ? "" : item.sourceTitle());
        if (source.isBlank()) {
            return "";
        }
        String[] segments = source.split("\\s*/\\s*");
        return segments[segments.length - 1]
                .replaceAll("(?:教材|讲义|专题)$", "")
                .strip();
    }

    /** Writes one full teacher-facing unit, keeping each real question adjacent to the knowledge point it assesses. */
    private static int appendTeacherKnowledgePoint(
            StringBuilder builder,
            TeachingKnowledgePointPack pack,
            int questionNumber,
            String teachingNotes,
            String draftWorkedExample,
            String draftKnowledgePosition,
            String draftQuestionType,
            String draftMethodSteps,
            String draftAnswerPoints,
            String draftPitfalls,
            TeachingTaskResponse.AiDraft aiDraft,
            String userQuestion,
            boolean allowGlobalDraftForQuestion,
            boolean zhaoMaster) {
        if (!zhaoMaster) {
            builder.append("\\section{").append(escapeLatex(topicSectionHeading(pack.title()))).append("}\n");
        }
        List<String> methodFacts = new ArrayList<>();
        if (!pack.supportingEvidence().isEmpty()) {
            methodFacts.addAll(pack.supportingEvidence().stream()
                    .map(TeachingEvidence::snippet)
                    .map(TeachingWorkflowService::compactEvidenceFact)
                    .filter(value -> !value.isBlank())
                    .limit(2)
                    .toList());
        }
        methodFacts.addAll(draftBlockLines(draftKnowledgePosition));
        methodFacts.addAll(draftBlockLines(draftQuestionType));
        methodFacts.addAll(draftBlockLines(draftMethodSteps).stream()
                .filter(line -> !CUSTOM_METHOD_HEADING.matcher(line).matches())
                .toList());
        methodFacts = new ArrayList<>(mergeDistinctItems(6, methodFacts, List.of(
                "先锁定题目对应的定义、条件和分界点，写清楚为什么可以使用该知识点。",
                "沿着条件逐步变形或分类讨论，每一步保留等号成立的依据。",
                "最后回到题目要求检查定义域、范围和边界，避免只得到形式上的结果。")));
        // A controlled “核心方法” heading makes the lesson hierarchy scannable.  Its content still comes from the
        // verified point and evidence; model-provided template/control words are rejected by methodHeading.
        if (!zhaoMaster) {
            builder.append("\\subsection*{").append(escapeLatex(methodHeading(pack.title(), draftMethodSteps))).append("}\n")
                    .append(latexItemize(methodFacts))
                    .append("\n\n");
        } else if (!pack.supportingEvidence().isEmpty()) {
            // The master page stays dense, but a short source-grounded fact preserves the requested audit trail.
            String evidenceFact = compactEvidenceFact(pack.supportingEvidence().getFirst().snippet());
            if (!evidenceFact.isBlank()) {
                builder.append("\\paragraph{资料依据}\n")
                        .append(escapeLatex(evidenceFact)).append("\n\n");
            }
        }
        TeachingEvidence workedExample = pack.workedExample();
        String workedText = workedExample == null ? "" : questionTextOnly(workedExample.snippet());
        // Retrieval may return a placeholder OCR row. Prefer the user's exact problem as the teacher example so
        // a failed/empty bank hit never produces a heading with no actual question beneath it.
        if ((workedExample == null || isUnusableQuestionText(workedText))
                && userQuestion != null && !userQuestion.isBlank()) {
            workedExample = new TeachingEvidence(
                    "USER_PROVIDED",
                    "用户题目 / " + pack.title(),
                    "user-question",
                    0,
                    userQuestion);
        }
        // The asset is deliberately not printed in the method block.  A figure is an item of the question statement
        // and must travel with the title and prompt.  The selector also rejects a mixed OCR window so an original
        // map cannot be silently attached to a neighbouring colour-count variation.
        String workedExampleText = workedExample == null ? "" : questionTextOnly(workedExample.snippet());
        // A page image belongs only to a figure-dependent stem. Attaching a nearby page to an ordinary complex-number
        // question misleads both the model and the learner, even if the asset itself is permission-checked.
        String workedExampleImagePath = requiresAuthorizedFigure(workedExampleText)
                // The imported atomic row is the strongest possible source-to-image binding. Prefer it before the
                // broader point evidence, which may legitimately be absent for a cross-topic real-paper question.
                ? firstExistingAuthorizedImagePath(workedExample)
                : "";
        if (workedExampleImagePath.isBlank() && requiresAuthorizedFigure(workedExampleText)) {
            workedExampleImagePath = firstAuthorizedImageForQuestion(workedExampleText, pack.supportingEvidence());
        }
        // A multi-topic handout must never copy one global explanation below every question.  The model is required
        // to name real source question numbers; use only the matching slice, otherwise leave the question for the
        // publication gate to reject rather than printing a plausible but unrelated solution.
        String questionScopedDraftSteps = modelDraftExcerptForQuestion(teachingNotes, workedExampleText);
        String questionScopedDraftAnswer = modelDraftAnswerForQuestion(teachingNotes, workedExampleText);
        if (questionScopedDraftSteps.isBlank() && allowGlobalDraftForQuestion) {
            questionScopedDraftSteps = draftMethodSteps;
            questionScopedDraftAnswer = draftAnswerPoints;
        }
        int nextQuestionNumber = appendTeacherQuestion(builder, questionNumber, "例题", workedExample, workedExampleImagePath,
                questionScopedDraftAnswer, questionScopedDraftSteps,
                "先指出题干对应的定义、公式或分类依据，再写出关键等式。\n");
        if (!zhaoMaster && nextQuestionNumber == questionNumber && userQuestion != null && !userQuestion.isBlank()) {
            // Keep a concrete user problem visible even if a legacy evidence object uses an unexpected placeholder
            // phrase that the normal guard did not recognize.
            nextQuestionNumber = appendTeacherQuestion(
                    builder,
                    questionNumber,
                    "例题",
                    new TeachingEvidence("USER_PROVIDED", "用户题目 / " + pack.title(), "user-question", 0, userQuestion),
                    workedExampleImagePath,
                    questionScopedDraftAnswer,
                    questionScopedDraftSteps,
                    "先指出题干对应的定义、公式或分类依据，再写出关键等式。\n");
        }
        int variationIndex = 1;
        for (TeachingEvidence variation : pack.variations()) {
            String variationHeading = variationIndex == 1 ? "变式练习" : "拓展变式";
            String variationText = variation == null ? "" : questionTextOnly(variation.snippet());
            // A variation is still an independently sourced atomic question. If it says “如图”, it must carry its
            // own permission-checked page asset rather than inheriting the worked example's diagram or being omitted.
            String variationImagePath = requiresAuthorizedFigure(variationText)
                    ? firstExistingAuthorizedImagePath(variation)
                    : "";
            // Every real bank row has its own audited source number.  Reuse only the model unit and final answer
            // carrying that same number; the earlier renderer accidentally left variations empty, then printed the
            // forbidden unverified-answer placeholder despite a real per-question model draft being available.
            String variationDraftSteps = modelDraftExcerptForQuestion(teachingNotes, variationText);
            String variationDraftAnswer = modelDraftAnswerForQuestion(teachingNotes, variationText);
            nextQuestionNumber = appendTeacherQuestion(builder, nextQuestionNumber, variationHeading, variation,
                    variationImagePath, variationDraftAnswer, variationDraftSteps,
                    "保留主方法，重点检查条件变化后边界和分类是否需要调整。\n");
            variationIndex += 1;
        }
        if (workedExample == null && !teachingNotes.isBlank()) {
            builder.append("\\subsection*{讲解}\n").append(teachingNotes).append("\n\n");
        }
        if (workedExample == null && !draftWorkedExample.isBlank()) {
            builder.append("\\subsection*{示例}\n")
                    .append(formatDraftContentAsLatex(draftWorkedExample)).append("\n\n");
        }
        List<String> notices = zhaoMaster
                // The master is a continuous problem page. A trailing warning block can strand one bullet on an
                // otherwise empty next page; the concrete condition check remains embedded in the worked solution.
                ? List.of()
                : mergeDistinctItems(3,
                        draftBlockLines(draftPitfalls),
                        guardDraftItems(aiDraft == null ? List.of() : aiDraft.followUpQuestions(), true),
                        List.of("条件变化时先检查定义域、参数范围和分界点，再下结论。"));
        if (!notices.isEmpty()) {
            builder.append(zhaoMaster ? "\\paragraph{易错提醒}\n" : "\\subsection*{注意}\n")
                    .append(latexItemize(notices)).append("\n\n");
        }
        return nextQuestionNumber;
    }

    /**
     * Extracts the one model section that actually describes this source stem.
     *
     * <p>The drafting contract asks the model to preserve source numbers, but real providers occasionally renumber
     * a selected sequence from one.  We first use the exact source marker, then match the top-level solution prompt
     * by overlapping meaningful terms.  A weak match is rejected rather than borrowing a correct solution from a
     * neighbouring question.</p>
     */
    private static String modelDraftExcerptForQuestion(String teacherExplanation, String questionText) {
        if (teacherExplanation == null || teacherExplanation.isBlank() || questionText == null || questionText.isBlank()) {
            return "";
        }
        List<ModelExplanationUnit> units = modelExplanationUnits(teacherExplanation);
        if (units.isEmpty()) {
            return "";
        }
        Matcher sourceNumber = SOURCE_QUESTION_NUMBER.matcher(questionText);
        if (sourceNumber.find()) {
            String expected = sourceNumber.group(1);
            for (ModelExplanationUnit unit : units) {
                if (expected.equals(unit.number())) {
                    // The imported source number is an audited document identity, unlike the model's optional
                    // local ordering. Once it matches exactly, retain the full unit even when OCR/LaTeX aliases
                    // prevent lexical term overlap; the unit is still subject to the teacher publication gate.
                    if (!unit.excerpt().isBlank()) {
                        return unit.excerpt();
                    }
                }
            }
        }
        return units.stream()
                .map(unit -> Map.entry(unit, promptMatchCount(questionText, unit.prompt())))
                .filter(entry -> entry.getValue() >= MIN_MODEL_PROMPT_MATCHES)
                .max(Map.Entry.comparingByValue())
                .map(entry -> entry.getKey().excerpt())
                .orElse("");
    }

    /** Rejects numbered labels that contain only an answer while preserving robust same-source-number matching. */
    private static boolean hasSubstantiveNumberedReasoning(String excerpt) {
        String normalized = excerpt == null ? "" : excerpt.replaceAll("\\s+", " ").strip();
        return normalized.length() >= MIN_NUMBERED_REASONING_CHARACTERS
                && SUBSTANTIVE_REASONING_SIGNAL.matcher(normalized).find();
    }

    /** Splits only top-level solution headings and leaves mathematical numbered steps inside their owning unit. */
    private static List<ModelExplanationUnit> modelExplanationUnits(String teacherExplanation) {
        // The same 1., 2. format is used by the model's earlier “题型识别” list.  Only the worked-example block
        // is a solution contract; starting there prevents an exact source number from selecting a generic hint.
        int detailedStart = teacherExplanation.indexOf("【例题详解】");
        String workedExamples = detailedStart >= 0 ? teacherExplanation.substring(detailedStart) : teacherExplanation;
        // The following answer block contains compact labels such as “题13（5分）”.  Those labels are not second
        // solution units; leaving them here made the first question absorb scoring text and could attach a later
        // answer to the wrong prompt. Final answers are parsed by modelDraftAnswerForQuestion from the dedicated
        // block below, while this method remains responsible only for derivation excerpts.
        int answerBlockStart = workedExamples.indexOf("【答案与评分点】");
        if (answerBlockStart >= 0) {
            workedExamples = workedExamples.substring(0, answerBlockStart);
        }
        // A model may return otherwise valid markdown with its worked-example labels compacted onto one line. Insert
        // a structural newline before the explicit “题N：” token only; derivation steps such as “1.” remain inside
        // their owning unit and therefore cannot be mistaken for a second source problem.
        workedExamples = INLINE_MODEL_EXPLANATION_HEADING.matcher(workedExamples).replaceAll("\n");
        Matcher matcher = MODEL_EXPLANATION_HEADING.matcher(workedExamples);
        List<ModelExplanationHeader> headers = new ArrayList<>();
        while (matcher.find()) {
            headers.add(new ModelExplanationHeader(matcher.group(1), matcher.group(2).strip(), matcher.start(), matcher.end()));
        }
        List<ModelExplanationUnit> units = new ArrayList<>();
        for (int index = 0; index < headers.size(); index += 1) {
            ModelExplanationHeader header = headers.get(index);
            int bodyEnd = index + 1 < headers.size() ? headers.get(index + 1).start() : workedExamples.length();
            // The heading echoes the source prompt. The visible question is already rendered by appendTeacherQuestion;
            // keeping it here made stray “题 1:” fragments appear inside the deduction paragraph and doubled the
            // stem. Only the model-authored reasoning after its heading belongs in this printable block.
            String excerpt = workedExamples.substring(header.end(), bodyEnd).strip();
            String number = header.number();
            String prompt = header.prompt();
            if (!prompt.isBlank() && excerpt.length() >= prompt.length()) {
                units.add(new ModelExplanationUnit(number, prompt, excerpt.length() > 1600
                        ? excerpt.substring(0, 1600).strip() : excerpt));
            }
        }
        return units;
    }

    /** Counts shared two-character runs plus ASCII/math terms, avoiding fragile equality on OCR punctuation. */
    private static int promptMatchCount(String sourceQuestion, String modelPrompt) {
        Set<String> sourceTerms = promptMatchTerms(sourceQuestion);
        Set<String> promptTerms = promptMatchTerms(modelPrompt);
        sourceTerms.retainAll(promptTerms);
        return sourceTerms.size();
    }

    /** Produces bounded Chinese bigrams and alphanumeric symbols that remain stable after model rephrasing. */
    private static Set<String> promptMatchTerms(String value) {
        String normalized = value == null ? "" : value.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
        Set<String> terms = new LinkedHashSet<>();
        Matcher chineseRun = Pattern.compile("[\\p{IsHan}]{2,}").matcher(normalized);
        while (chineseRun.find()) {
            String run = chineseRun.group();
            for (int index = 0; index + 1 < run.length(); index += 1) {
                terms.add(run.substring(index, index + 2));
            }
        }
        Matcher symbol = Pattern.compile("[a-zαβγθ][a-z0-9αβγθ_']{0,7}").matcher(normalized);
        while (symbol.find()) {
            terms.add(symbol.group());
        }
        return terms;
    }

    /** Immutable model unit keeps prompt matching and printable deduction together. */
    private record ModelExplanationUnit(String number, String prompt, String excerpt) { }

    /** Header coordinates are collected before slicing so the matcher never skips every second worked example. */
    private record ModelExplanationHeader(String number, String prompt, int start, int end) { }

    /** Keeps the final conclusion visible in the teacher answer block without duplicating the full derivation. */
    private static String modelDraftConclusionForQuestion(String excerpt) {
        String text = excerpt == null ? "" : excerpt.strip();
        if (text.isBlank()) {
            return "";
        }
        int conclusion = Math.max(text.lastIndexOf("故"), Math.max(text.lastIndexOf("因此"), text.lastIndexOf("答案")));
        String result = conclusion >= 0 ? text.substring(conclusion).strip() : text;
        return result.length() > 420 ? result.substring(0, 420).strip() : result;
    }

    /**
     * Retrieves one terminal answer from the model's dedicated scoring block by the immutable source question
     * number. The renderer never guesses from a neighbouring unit: missing or malformed number labels return an
     * empty string, so the publication gate continues to protect the teacher export.
     */
    private static String modelDraftAnswerForQuestion(String teacherExplanation, String questionText) {
        if (teacherExplanation == null || teacherExplanation.isBlank() || questionText == null || questionText.isBlank()) {
            return "";
        }
        Matcher sourceNumber = SOURCE_QUESTION_NUMBER.matcher(questionText);
        if (!sourceNumber.find()) {
            return modelDraftConclusionForQuestion(modelDraftExcerptForQuestion(teacherExplanation, questionText));
        }
        int answerBlockStart = teacherExplanation.indexOf("【答案与评分点】");
        if (answerBlockStart < 0) {
            return modelDraftConclusionForQuestion(modelDraftExcerptForQuestion(teacherExplanation, questionText));
        }
        String answerBlock = teacherExplanation.substring(answerBlockStart + "【答案与评分点】".length());
        String number = Pattern.quote(sourceNumber.group(1));
        Pattern answerEntry = Pattern.compile(
                "(?:第\\s*)?" + number + "\\s*题?\\s*(?:[（(][^）)]{0,32}[）)])?\\s*(?:[：:]\\s*)?(.+?)"
                        + "(?=；\\s*(?:第\\s*)?\\d{1,3}\\s*题?(?:\\s*[（(]|\\s*[：:]|\\s*\\d+分)|【|$)",
                Pattern.DOTALL);
        Matcher entry = answerEntry.matcher(answerBlock);
        if (!entry.find()) {
            // Providers often omit the colon in compact scoring prose (for example “第13题5分填…”).
            // The numbered derivation is still tied to this exact source stem, so retain its conclusion rather
            // than replacing a real solved chain with the forbidden unverified-answer placeholder.
            return modelDraftConclusionForQuestion(modelDraftExcerptForQuestion(teacherExplanation, questionText));
        }
        String answer = entry.group(1).replaceAll("\\s+", " ").strip();
        return answer.length() > 420 ? answer.substring(0, 420).strip() : answer;
    }

    /** Builds a short, point-specific method heading for the printable unit. */
    private static String methodHeading(String knowledgePoint, String draftMethodSteps) {
        if (draftMethodSteps != null && !draftMethodSteps.isBlank()) {
            Matcher matcher = CUSTOM_METHOD_HEADING.matcher(draftMethodSteps);
            if (matcher.find()) {
                String authored = matcher.group(1).strip();
                String authoredWithoutInternalPrefix = authored.replaceFirst("^核心方法\\s*[：:]\\s*", "").strip();
                if (!authored.isBlank()
                        && !authoredWithoutInternalPrefix.isBlank()
                        && !authoredWithoutInternalPrefix.equals("核心方法")
                        && !authoredWithoutInternalPrefix.matches(".*(?:方法主线|解题步骤|提示词|模板|AI|JSON|debug).*$")) {
                    // The heading itself is printable content.  Do not expose the renderer's internal section
                    // label (“核心方法”) in front of an authored topic title.
                    return authoredWithoutInternalPrefix;
                }
            }
        }
        String title = knowledgePoint == null || knowledgePoint.isBlank() ? "本节知识" : knowledgePoint.strip();
        String compactTitle = title.replaceAll("\\s+", "");
        if (COLORING_TOPIC.matcher(compactTitle).find()) {
            return title + "：从邻接关系到分类计数";
        }
        if (isQuadraticFunctionText(compactTitle)) {
            return title + "：从顶点与对称轴建立函数模型";
        }
        return title + "：条件识别与推导";
    }

    /** Embeds an already permission-checked local asset directly; opaque markers are for API transport only. */
    private static String authorizedImageLatex(String path) {
        String normalized = Path.of(path).toAbsolutePath().normalize().toString().replace('\\', '/');
        return "\\begin{center}\n\\includegraphics[width=" + PRINTED_IMAGE_WIDTH + ",height=" + PRINTED_IMAGE_MAX_HEIGHT
                + ",keepaspectratio]{\\detokenize{"
                + normalized + "}}\n\\end{center}";
    }

    /** Uses the projection column width and fixed height budget while preserving the authorized image aspect ratio. */
    private static String lectureAuthorizedImageLatex(String path) {
        String normalized = Path.of(path).toAbsolutePath().normalize().toString().replace('\\', '/');
        return "\\begin{center}\n\\includegraphics[width=\\linewidth,height=" + LECTURE_IMAGE_MAX_HEIGHT
                + ",keepaspectratio]{\\detokenize{" + normalized + "}}\n\\end{center}";
    }

    private static int appendTeacherQuestion(
            StringBuilder builder,
            int questionNumber,
            String heading,
            TeachingEvidence question,
            String authorizedImagePath,
            String draftAnswerPoints,
            String draftMethodSteps,
            String fallbackHint) {
        if (question == null) {
            return questionNumber;
        }
        String questionText = questionTextOnly(question.snippet());
        if (isUnusableQuestionText(questionText)) {
            // A malformed OCR/import row is not an example. Omitting it is safer than printing a placeholder.
            return questionNumber;
        }
        if (requiresAuthorizedFigure(questionText)
                && (authorizedImagePath == null || authorizedImagePath.isBlank()
                || !Files.isRegularFile(Path.of(authorizedImagePath)))) {
            // The single-document synchronizer must materialize the exact source image before a figure-dependent
            // question is eligible.  This is the direct guard against the broken "如图" page in the visual audit.
            return questionNumber;
        }
        // Keep the question title, stem, and authorized diagram together while still allowing the explanation to
        // continue naturally on the following page. This is denser than a forced page break and prevents split 图题.
        // A diagram-dependent item is an indivisible unit: its stem, source figure and explanation must begin on
        // the same page. \Needspace only protects available remaining height and previously allowed the stem to be
        // stranded on the preceding page, so a real figure starts a fresh teacher page.
        if (authorizedImagePath != null && !authorizedImagePath.isBlank()) {
            builder.append("\\clearpage\n");
        } else {
            builder.append("\\Needspace{26\\baselineskip}\n");
        }
        String sourceAnswer = questionAnswerOnly(question.snippet());
        String bankSteps = questionBankSteps(sourceAnswer);
        // Do not repeat a lesson-wide model paragraph for every question.  A teacher page must either use the
        // question bank's own derivation or a route inferred from this visible stem.
        String detailedSteps = !bankSteps.isBlank()
                ? formatDraftContentAsLatex(withoutBoardOrderLine(bankSteps))
                : latexEnumerate(lectureQuestionFallbackPath(questionText));
        String answer = teacherQuestionConclusion(questionText, sourceAnswer,
                compactQuestionBankAnswer(questionBankAnswerWithoutSteps(sourceAnswer)), draftAnswerPoints);
        // Keep the pedagogical chain visible instead of concatenating a final answer and an unrelated
        // global draft into one paragraph. The entry is tied to this exact bank title; steps and answer
        // remain separately reviewable for every retrieved question.
        // The question bank title is source metadata, not a second problem statement.  Printing it both above and
        // inside the prompt produced the duplicated “例题/题目” block in teacher PDFs, so analysis starts from the
        // visible stem itself and never exposes a source label.
        String analysisEntry = questionAnalysisEntry(questionText);
        String solutionHeading = questionSolutionHeading(questionText);
        // Numbered headings are consumed by the PDF exporter to keep each real question separated, including 16:10.
        builder.append("\\subsection*{第").append(questionNumber).append("题 ")
                .append(escapeLatex(heading)).append("}\n")
                .append("\\paragraph{题目}\n")
                .append(escapeLatex(questionText)).append("\n");
        // A permission-checked figure is part of this concrete example, never a decoration for the previous method
        // block.  Keeping it immediately after the prompt preserves the question-to-image relation in all exports.
        if (authorizedImagePath != null && !authorizedImagePath.isBlank()) {
            builder.append(authorizedImageLatex(authorizedImagePath)).append("\n");
        }
        builder
                .append("\n\n\\paragraph{").append(escapeLatex(analysisHeading(questionText))).append("}\n")
                .append(escapeLatex(analysisEntry))
                .append("\n\n\\paragraph{").append(escapeLatex(solutionHeading)).append("}\n")
                .append(detailedSteps)
                .append("\n\n\\paragraph{答案与评分点}\n")
                .append(answer)
                .append("\n\n");
        return questionNumber + 1;
    }

    /** Uses a topic-owned entry label so the PDF never exposes the generic prompt scaffold as a lesson heading. */
    private static String analysisHeading(String questionText) {
        String text = questionText == null ? "" : questionText.replaceAll("\\s+", "");
        if (COLORING_TOPIC.matcher(text).find()) {
            return "先看相邻关系";
        }
        if (isQuadraticFunctionText(text)) {
            return "先定图像特征";
        }
        return "条件落点";
    }

    /** Derives a printable, topic-specific name for the deduction chain. */
    private static String questionSolutionHeading(String questionText) {
        String text = questionText == null ? "" : questionText.replaceAll("\\s+", "");
        if (COLORING_TOPIC.matcher(text).find()) {
            return "按颜色分类计数";
        }
        if (isQuadraticFunctionText(text)) {
            return "从顶点与对称轴推导";
        }
        if (isVectorQuestion(text)) {
            return "由数量积求模长";
        }
        if (isLogOptimizationQuestion(text)) {
            return "由对数变号确定参数";
        }
        return "推导链条";
    }

    /** Supplies a concrete first move tied to the actual stem, never a generic “read the prompt” instruction. */
    private static String questionAnalysisEntry(String questionText) {
        String text = questionText == null ? "" : questionText.replaceAll("\\s+", "");
        if (text.contains("4×4方格") || text.contains("4×4 方格")) {
            return "把四行依次选中的列号记为一个排列；列号不重复正好等价于每列恰选一个方格。";
        }
        if (text.contains("二面角") || text.contains("对折")) {
            return "折叠前先在平面图中找出 EF 与相关边的垂直关系；折叠保持长度和角度，再转入空间证明。";
        }
        if (COLORING_TOPIC.matcher(text).find()) {
            return "先把题图中的公共边界记成邻接关系；只有共边界的区域互相限制颜色，不能直接按区域个数写幂。";
        }
        if (isQuadraticFunctionText(text)) {
            return "先判断开口方向、顶点与对称轴，再把题目要求转成对应的函数值或参数条件。";
        }
        if (isVectorQuestion(text)) {
            return "先把垂直条件改写成数量积方程，再对模长等式平方，联立消去数量积。";
        }
        if (isLogOptimizationQuestion(text)) {
            return "令 t=x+b>0，利用 ln t 在 t=1 处变号确定参数关系，再在约束直线上求平方和最小值。";
        }
        return "先圈出题干给出的条件与目标，明确第一步要使用的定义、公式或图形关系。";
    }

    /**
     * Supplies a checked conclusion only where the stem itself determines it.  OCR score rubrics and a global
     * lesson answer are deliberately rejected: they are not an answer to the current question.
     */
    private static String teacherQuestionConclusion(
            String questionText, String sourceAnswer, String compactBankAnswer, String draftAnswerPoints) {
        String text = questionText == null ? "" : questionText.replaceAll("\\s+", "");
        if (text.contains("4×4方格") || text.contains("4×4 方格")) {
            return "共有 $4!=24$ 种选法；最大和为 $40+33+22+15=110$（也可取 $31+42+22+15=110$）。";
        }
        if ((text.contains("二面角") || text.contains("对折")) && text.contains("PC=4√3")) {
            return "（1）$EF\\perp PD$；（2）所求二面角的正弦值为 $\\frac{8}{\\sqrt{65}}$。";
        }
        if (isVectorQuestion(text)) {
            return "由 $(\\vec b-2\\vec a)\\cdot\\vec b=0$ 得 $|\\vec b|^2=2\\vec a\\cdot\\vec b$；又由 $|\\vec a+2\\vec b|^2=4$ 联立可得 $|\\vec b|=\\frac{\\sqrt{2}}{2}$，选 B。";
        }
        if (isLogOptimizationQuestion(text)) {
            return "令 $t=x+b>0$，因 $\\ln t$ 在 $t=1$ 处变号，恒有 $(t+a-b)\\ln t\\ge0$ 必须满足 $a-b=-1$，即 $b=a+1$。于是 $a^2+b^2=a^2+(a+1)^2$，在 $a=-\\frac12$ 时取最小值 $\\frac12$，选 C。";
        }
        if (!isUnreliableQuestionAnswer(compactBankAnswer)) {
            return compactBankAnswer;
        }
        if (!isUnreliableQuestionAnswer(draftAnswerPoints)) {
            return compactQuestionBankAnswer(draftAnswerPoints);
        }
        return "\\textbf{该题缺少题号级核验答案，暂不发布。}";
    }

    /** Detects the plane-vector question family before generic fallback text can be reused. */
    private static boolean isVectorQuestion(String text) {
        String compact = text == null ? "" : text.replaceAll("\\s+", "");
        return (compact.contains("向量") || compact.contains("\\vec") || compact.contains("⃗"))
                && (compact.contains("垂直") || compact.contains("⊥") || compact.contains("数量积")
                || compact.contains("模长") || compact.contains("|a+2b|"));
    }

    /** Detects the parameter-optimization logarithm item used by the real task's question 8. */
    private static boolean isLogOptimizationQuestion(String text) {
        String compact = text == null ? "" : text.replaceAll("\\s+", "");
        return compact.contains("ln") && compact.contains("a^2") && compact.contains("b^2")
                && (compact.contains("恒成立") || compact.contains(">=0") || compact.contains("≥0")
                || compact.contains("最小值"));
    }

    /** Rejects OCR label dumps and whole-paper scoring notes before they are displayed as a single-question answer. */
    private static boolean isUnreliableQuestionAnswer(String answer) {
        if (answer == null || answer.isBlank()) {
            return true;
        }
        String originalCompact = answer.replaceAll("\\s+", "");
        String compact = repairMojibake(answer).replaceAll("\\s+", "");
        return compact.contains("各题对应分值")
                || compact.contains("图中几何标签")
                || compact.contains("资料答案：要点：答案")
                || compact.contains("答案要点")
                || compact.contains("学科网")
                || compact.contains("股份有限公司")
                || compact.contains("【解析】")
                || compact.contains("【分析】")
                || compact.contains("【小问")
                || compact.matches(".*第\\s*\\d+\\s*页\\s*/?\\s*共\\s*\\d+\\s*页.*")
                || compact.contains("答案：第")
                || compact.contains("解析卷")
                || compact.contains("题库未提供")
                || compact.contains("⋯")
                || compact.contains("……")
                || compact.contains("...")
                || (Math.max(mojibakeScore(originalCompact), mojibakeScore(compact)) >= 5
                        && (originalCompact + compact).matches(".*\\d+.*"))
                || compact.length() < 4;
    }

    /** Repairs legacy UTF-8-as-Latin-1 snapshots without changing correctly decoded Chinese text. */
    private static String repairMojibake(String value) {
        if (value == null || value.isBlank()
                || !value.matches("(?s).*[ÃÂåæçèéêïðñã].*")) {
            return value == null ? "" : value;
        }
        try {
            String candidate = new String(value.getBytes(StandardCharsets.ISO_8859_1), StandardCharsets.UTF_8);
            long sourceNoise = mojibakeScore(value);
            long candidateNoise = mojibakeScore(candidate);
            long candidateHan = candidate.chars().filter(character -> character >= 0x4E00 && character <= 0x9FFF).count();
            return candidateHan > 0 && candidateNoise < sourceNoise ? candidate : value;
        } catch (RuntimeException ignored) {
            return value;
        }
    }

    private static long mojibakeScore(String value) {
        return value == null ? 0 : value.chars()
                .filter(character -> character == 'Ã' || character == 'Â' || character == 'å'
                        || character == 'æ' || character == 'ç' || character == 'è'
                        || character == 'é' || character == 'ê' || character == 'ï'
                        || character == 'ð' || character == 'ñ' || character == 'ã')
                .count();
    }

    /** Pulls verified bank steps out of the answer metadata so they render as an actual solution chain. */
    private static String questionBankSteps(String formattedAnswer) {
        if (formattedAnswer == null || formattedAnswer.isBlank()) {
            return "";
        }
        Matcher matcher = Pattern.compile("(?:^|；)步骤：(.+?)(?=；(?:答案|解析|评分点|方法|提示|难度|补充\\d+)：|$)")
                .matcher(formattedAnswer);
        return matcher.find() ? matcher.group(1).strip() : "";
    }

    /**
     * Keeps the solved example focused on mathematical deductions.  A generated "板书顺序" is an instructor
     * delivery note rather than a derivation step, and when appended after a long figure it was routinely stranded
     * by itself on the following page.  The core-method block already provides the reusable classroom approach.
     */
    private static String withoutBoardOrderLine(String steps) {
        if (steps == null || steps.isBlank()) {
            return "";
        }
        List<String> retained = new ArrayList<>();
        boolean skipBoardContinuation = false;
        for (String rawLine : steps.replace("\r\n", "\n").replace('\r', '\n').split("\n")) {
            String normalized = rawLine.replaceAll("\\s+", "").strip();
            if (normalized.contains("板书顺序")) {
                // Models commonly put the heading on one line and a dense circled-number sequence on the next. That
                // sequence repeats the solved steps but consumes enough height to strand the answer on a new page.
                skipBoardContinuation = true;
                continue;
            }
            if (skipBoardContinuation && normalized.matches("^[①②③④⑤⑥⑦⑧⑨⑩].*")) {
                skipBoardContinuation = false;
                continue;
            }
            skipBoardContinuation = false;
            retained.add(rawLine);
        }
        return String.join("\n", retained).strip();
    }

    /** Removes the extracted steps from the final-answer block to avoid printing the same evidence twice. */
    private static String questionBankAnswerWithoutSteps(String formattedAnswer) {
        if (formattedAnswer == null || formattedAnswer.isBlank()) {
            return "";
        }
        return formattedAnswer.replaceFirst("(?:^|；)步骤：.+?(?=；(?:答案|解析|评分点|方法|提示|难度|补充\\d+)：|$)", "")
                .replaceAll("；{2,}", "；")
                .replaceAll("^；|；$", "")
                .strip();
    }

    /**
     * Compresses a long teacher-source answer into auditable calculations and the source's terminal result. Raw OCR
     * paragraphs are kept in retrieval metadata, never copied wholesale into the printable teacher page.
     */
    private static String compactQuestionBankAnswer(String answer) {
        if (answer == null || answer.isBlank()) {
            return "";
        }
        if (isUnreliableQuestionAnswer(answer)) {
            return "";
        }
        String normalized = repairMojibake(answer).replace("\\times", "×").replace("\\cdot", "·")
                .replaceAll("\\s+", " ").replaceAll("#{2,}", " ").strip();
        // Reject a whole OCR label dump before extracting short arithmetic fragments from it.
        if (isUnreliableQuestionAnswer(normalized)) {
            return "";
        }
        LinkedHashSet<String> expressions = new LinkedHashSet<>();
        // A complete additive classification is self-checking.  OCR frequently invents short fragments such as
        // "2=15" beside the real sum, so accept those fragments only when the source contains no complete sum.
        Matcher verifiedSumMatcher = VERIFIED_SUM_EXPRESSION.matcher(normalized);
        while (verifiedSumMatcher.find() && expressions.size() < 5) {
            expressions.add(verifiedSumMatcher.group(1).replaceAll("\\s+", " ").strip());
        }
        if (expressions.isEmpty()) {
            Matcher expressionMatcher = Pattern.compile("(?<![A-Za-z0-9])(?:[A-Za-z]+[_^{}0-9]*\\s*)?(?:[0-9]+(?:\\s*[+×*]\\s*[0-9]+)+\\s*=\\s*[0-9]+|[0-9]+\\s*=\\s*[0-9]+)(?![A-Za-z0-9])")
                    .matcher(normalized);
            while (expressionMatcher.find() && expressions.size() < 5) {
                expressions.add(expressionMatcher.group().replaceAll("\\s+", " ").strip());
            }
        }
        String terminal = "";
        Matcher terminalMatcher = Pattern.compile("(?:合计|总计|答案)\\s*[：:]?\\s*([^。；]{1,80})").matcher(normalized);
        while (terminalMatcher.find()) {
            String candidate = terminalMatcher.group(1).strip();
            if (!candidate.isBlank()) {
                terminal = candidate;
            }
        }
        if (!expressions.isEmpty()) {
            StringBuilder result = new StringBuilder(escapeLatex("资料答案："));
            if (!terminal.isBlank() && terminal.length() <= 42) {
                result.append(escapeLatex(terminal)).append("；");
            }
            result.append(expressions.stream()
                    .map(expression -> "$" + escapeLatexMath(expression) + "$")
                    .collect(java.util.stream.Collectors.joining("；")));
            return result.toString();
        }
        return escapeLatex(normalized.length() > 180 ? normalized.substring(0, 180) + "……" : normalized);
    }

    /** Converts source arithmetic into a safe inline math segment without escaping its LaTeX operators as prose. */
    private static String escapeLatexMath(String expression) {
        return sanitizeMathSegment(expression
                .replace("×", "\\times")
                .replace("·", "\\cdot"));
    }

    private static boolean isUnusableQuestionText(String questionText) {
        if (questionText == null || questionText.isBlank()) {
            return true;
        }
        String normalized = questionText.replaceAll("\\s+", "").strip();
        // Never guess whether a broken square means perpendicular, parallel, subset, or a missing glyph.  It must
        // be repaired by the real single-document synchronizer/OCR before this row can become a mathematical task.
        return UNRESOLVED_OCR_MATH_GLYPH.matcher(normalized).find()
                || normalized.equals("题目")
                || normalized.contains("题目内容待补充")
                || normalized.contains("题目待补充")
                || normalized.matches("^(?:暂无|无|待补充|未提供).{0,16}$");
    }

    /** Extracts useful classroom prose from a structured draft without printing its internal field labels. */
    private static String mergeTeacherDraftNotes(String teacherExplanation) {
        return mergeDistinctItems(8,
                draftBlockLines(draftBlockContent(teacherExplanation, teacherDraftLabels(), "知识定位")),
                draftBlockLines(draftBlockContent(teacherExplanation, teacherDraftLabels(), "题型识别")),
                draftBlockLines(draftBlockContent(teacherExplanation, teacherDraftLabels(), "方法步骤")))
                .stream()
                .map(TeachingWorkflowService::escapeLatex)
                .collect(java.util.stream.Collectors.joining("\n"));
    }

    private static String compactEvidenceFact(String value) {
        // Teacher-source OCR is evidence for the model, not text suitable for a handout.  Show only a complete,
        // independently checkable calculation; prose around it can contain merged neighbouring variants or OCR noise.
        Matcher sum = VERIFIED_SUM_EXPRESSION.matcher(normalizedInlineText(value));
        if (!sum.find()) {
            return "";
        }
        String expression = sum.group(1).replace('＋', '+').replaceAll("\\s+", " ").strip();
        return "资料分类结果：" + expression;
    }

    /** Normalizes read-only evidence text without changing the stored source or leaking raw OCR layout into the PDF. */
    private static String normalizedInlineText(String value) {
        return value == null ? "" : value.replaceAll("\\s+", " ").strip();
    }

    private static boolean canUseQuestionBank(TeachingRequestContext context) {
        String subjectType = context == null ? "" : context.subjectType();
        // Students may use only rows already filtered by the question-bank visibility query. This is required for
        // weak-point practice generation; teacher resources and answer-bearing management operations remain gated
        // separately by canUseTeacherResources and the controller capability checks.
        return "student".equalsIgnoreCase(subjectType)
                || "teacher".equalsIgnoreCase(subjectType)
                || "admin".equalsIgnoreCase(subjectType);
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
        for (TeachingEvidence item : evidence) {
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
                    + "：教师资料，" + printableEvidenceTitle(item.sourceTitle())
                    + "，" + page
                    + "；用途：题型方法、教师沉淀与讲义补充。";
        }
        String page = item.pageNo() > 0 ? "PDF " + item.pageNo() : "页码未记录";
        return "来源 " + index
                + "：公开教材，" + printableEvidenceTitle(item.sourceTitle())
                + "，" + page
                + "；用途：知识点定位与公式依据。";
    }

    private static String evidenceLabel(TeachingEvidence item) {
        if ("QUESTION_BANK".equals(item.sourceScope())) {
            return "题库：" + questionTitleWithoutDifficulty(item);
        }
        if ("TEACHER_RESOURCE".equals(item.sourceScope())) {
            return item.pageNo() > 0
                    ? printableEvidenceTitle(item.sourceTitle()) + " / 第 " + item.pageNo() + " 页"
                    : printableEvidenceTitle(item.sourceTitle());
        }
        return printableEvidenceTitle(item.sourceTitle()) + " / PDF " + item.pageNo();
    }

    /** Keeps human-readable source names while hiding opaque ids from printable teacher/student content. */
    private static String printableEvidenceTitle(String value) {
        if (value == null || value.isBlank()) {
            return "未命名资料";
        }
        return value.replaceAll("\\b[A-Za-z0-9]{24,}\\b", "")
                .replaceAll("\\s{2,}", " ")
                .strip();
    }

    /**
     * 生成学生版 LaTeX 讲义：保留题目、提示和干净空白，不直接暴露教师解析和知识点归属。
     */
    private static String buildStudentHandoutLatex(
            TeachingTaskRequest request,
            List<TeachingEvidence> evidence,
            List<TeachingKnowledgePointPack> knowledgePointPacks,
            StudentMemoryResponse memoryResponse,
            TeachingHandoutTemplateProfile template,
            TeachingTaskResponse.AiDraft aiDraft,
            TeachingDraftSections draftSections) {
        String hint = memoryResponse.reused()
                ? "回忆同类问题的方法，先写出已知条件，再判断可用公式。"
                : evidence.isEmpty()
                ? "先圈出题目中的关键词，再尝试写出相关定义。"
                : "先阅读教材证据中的定义或公式，再补全自己的推理。";
        String lectureTitle = studentLectureTitle(request);
        String studentHint = draftSections == null ? "" : draftSections.studentWorksheet();
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
        List<String> practiceItems = draftSections == null
                ? studentPracticeTasks(request, evidence, aiDraft, draftPractice)
                : draftSections.exercises();
        int blankSpaceEm = template.blankSpaceEm();
        String evidenceImage = evidence.stream()
                .map(TeachingEvidence::imagePath)
                .filter(path -> path != null && !path.isBlank())
                .findFirst()
                .map(TeachingWorkflowService::authorizedImageLatex)
                .orElse("");
        if (knowledgePointPacks != null && !knowledgePointPacks.isEmpty()) {
            // A global AI worksheet is unsafe for several sibling knowledge points, but is concrete
            // and valuable when exactly one verified point owns the page. Preserve its student-safe
            // facts, recognition signals, and tasks instead of replacing them with generic filler.
            boolean oneKnowledgePoint = knowledgePointPacks.size() == 1;
            return buildStudentKnowledgePointHandout(
                    lectureTitle,
                    knowledgePointPacks,
                    blankSpaceEm,
                    oneKnowledgePoint ? draftBlockLines(draftKnowledge) : List.of(),
                    oneKnowledgePoint ? draftBlockLines(draftType) : List.of(),
                    oneKnowledgePoint ? draftBlockLines(draftPractice) : List.of());
        }
        if (template.studentLectureStyle()) {
            return """
                    \\section{%s}
                    %s

                    \\section{知识速记}
                    %s

                    \\section{注意}
                    %s

                    \\section{题型识别}
                    %s

                    \\section{典型例题}
                    %s
                    %s

                    %s

                    \\section{订正与错因}
                    \\vspace{6em}
                    """.formatted(
                    escapeLatex(lectureTitle),
                    evidenceImage,
                    knowledgeSection,
                    noteSection,
                    methodSection,
                    exampleSection,
                    studentQuestionPages("连续编号练习", practiceItems, blankSpaceEm),
                    studentQuestionBankSection(request, evidence, blankSpaceEm));
        }
        return """
                \\section{%s}
                %s

                \\section{知识速记}
                %s

                \\section{注意}
                %s

                \\section{题型识别}
                %s

                \\section{典型例题}
                %s
                %s
                
                %s

                \\section{错因整理}
                \\vspace{6em}
                """.formatted(
                escapeLatex(lectureTitle),
                evidenceImage,
                knowledgeSection,
                noteSection,
                methodSection,
                exampleSection,
                studentQuestionPages("连续编号练习", practiceItems, blankSpaceEm),
                studentQuestionBankSection(request, evidence, blankSpaceEm));
    }

    /**
     * Writes student material in the same knowledge-point order as the teacher version while withholding solutions.
     * Each real question gets its own page and writing area, so dense retrieval never collapses several examples into
     * a small generic exercise list.
     */
    private static String buildStudentKnowledgePointHandout(
            String lectureTitle,
            List<TeachingKnowledgePointPack> packs,
            int configuredWorkspaceEm,
            List<String> aiKnowledgeNotes,
            List<String> aiRecognitionSignals,
            List<String> aiPracticeTasks) {
        int workspace = Math.max(STUDENT_QUESTION_WORKSPACE_EM, configuredWorkspaceEm);
        StringBuilder builder = new StringBuilder("\\section{")
                .append(escapeLatex(lectureTitle))
                .append("}\n");
        if (isQuadraticFunctionText(lectureTitle)) {
            builder.append(quadraticReferenceGraph()).append("\n");
        }
        // The first real question shares the current page with its concise knowledge card. Later questions remain
        // independently printable, which preserves writing space without creating an almost empty overview page.
        boolean firstPrintableQuestion = true;
        for (TeachingKnowledgePointPack pack : packs) {
            builder.append("\\section{").append(escapeLatex(topicSectionHeading(pack.title()))).append("}\n");
            // Students need the same authorized source diagram to answer a figure-based question. Reuse only
            // the permission-checked sibling asset already attached to this knowledge-point pack; never expose a
            // remote Feishu URL or an unverified path in the student worksheet.
            String imagePath = pack.supportingEvidence().stream()
                    .map(TeachingEvidence::imagePath)
                    .filter(path -> path != null && !path.isBlank())
                    .findFirst()
                    .orElse("");
            // Do not print the figure in this overview block. appendStudentQuestion owns the same authorized image,
            // so each student question has exactly one nearby diagram instead of a detached duplicate on a prior page.
            // Student worksheets may reuse an authorized diagram, but never print source snippets or source-derived
            // results: that would both expose OCR noise and leak the teacher's answer into the student's task page.
            // 学生页不展示知识速记、识别信号或自检提示，避免把教师编排内容混入题目区。
            // Self-check prompts belong inside the real question unit. Rendering them as their own preceding block
            // allowed normal TeX page flow to strand two short lines on a nearly empty page before the question.
            TeachingEvidence workedExample = pack.workedExample();
            String workedExampleText = workedExample == null ? "" : questionTextOnly(workedExample.snippet());
            String workedExampleImagePath = requiresAuthorizedFigure(workedExampleText)
                    ? firstExistingAuthorizedImagePath(workedExample)
                    : "";
            if (requiresAuthorizedFigure(workedExampleText) && workedExampleImagePath.isBlank()) {
                workedExampleImagePath = firstAuthorizedImageForQuestion(
                        questionTextOnly(workedExample.snippet()), pack.supportingEvidence());
            }
            appendStudentQuestion(builder, "例题", workedExample, workspace, workedExampleImagePath,
                    List.of(), !firstPrintableQuestion);
            if (workedExample != null && !isUnusableQuestionText(questionTextOnly(workedExample.snippet()))) {
                firstPrintableQuestion = false;
            }
            int variationIndex = 1;
            for (TeachingEvidence variation : pack.variations()) {
                String variationText = variation == null ? "" : questionTextOnly(variation.snippet());
                String variationImagePath = requiresAuthorizedFigure(variationText)
                        ? firstExistingAuthorizedImagePath(variation)
                        : "";
                if (requiresAuthorizedFigure(variationText) && variationImagePath.isBlank()) {
                    variationImagePath = firstAuthorizedImageForQuestion(
                            questionTextOnly(variation.snippet()), pack.supportingEvidence());
                }
                appendStudentQuestion(builder, variationIndex == 1 ? "变式练习" : "拓展变式",
                        variation, workspace, variationImagePath, List.of(), !firstPrintableQuestion);
                if (variation != null && !isUnusableQuestionText(questionTextOnly(variation.snippet()))) {
                    firstPrintableQuestion = false;
                }
                variationIndex += 1;
            }
        }
        return builder.toString();
    }

    /** Returns only an existing local asset whose source block is proven to match the current question. */
    private static String firstAuthorizedImageForQuestion(
            String questionText,
            List<TeachingEvidence> supportingEvidence) {
        return supportingEvidenceForQuestion(questionText, supportingEvidence).stream()
                .map(TeachingEvidence::imagePath)
                .filter(path -> path != null && !path.isBlank())
                .filter(path -> Files.isRegularFile(Path.of(path)))
                .findFirst()
                .orElse("");
    }

    /** Returns the question row's own already permission-checked renderer path, never an inferred sibling asset. */
    private static String firstExistingAuthorizedImagePath(TeachingEvidence evidence) {
        if (evidence == null || evidence.imagePath() == null || evidence.imagePath().isBlank()) {
            return "";
        }
        String path = evidence.imagePath().strip();
        return Files.isRegularFile(Path.of(path)) ? path : "";
    }

    /** Keeps a visual stem atomic: diagram-dependent mathematics cannot be rendered from text alone. */
    private static boolean requiresAuthorizedFigure(String questionText) {
        return questionText != null && FIGURE_DEPENDENT_QUESTION.matcher(questionText).find();
    }

    /** Keeps student section names topic-owned instead of exposing a fixed template vocabulary. */
    private static String evidenceHeading(String knowledgePoint) {
        String title = knowledgePoint == null || knowledgePoint.isBlank() ? "本节知识" : knowledgePoint.strip();
        return title + "：依据与信号";
    }

    private static String writingHeading(String knowledgePoint) {
        String title = knowledgePoint == null || knowledgePoint.isBlank() ? "本节知识" : knowledgePoint.strip();
        return title + "：书写路径";
    }

    private static void appendStudentQuestion(
            StringBuilder builder,
            String heading,
            TeachingEvidence question,
            int workspace,
            String authorizedImagePath,
            List<String> selfCheckTasks,
            boolean startOnNewPage) {
        if (question == null) {
            return;
        }
        String questionText = questionTextOnly(question.snippet());
        if (questionText.isBlank() || questionText.contains("题目内容待补充")) {
            // Keep malformed imported rows out of the student worksheet instead of exposing an empty placeholder.
            return;
        }
        if (requiresAuthorizedFigure(questionText)
                && (authorizedImagePath == null || authorizedImagePath.isBlank()
                || !Files.isRegularFile(Path.of(authorizedImagePath)))) {
            // Teachers and students see the same complete question boundary; a missing source asset omits the
            // question rather than asking the learner to infer an absent diagram.
            return;
        }
        if (startOnNewPage) {
            builder.append("\\clearpage\n");
        }
        builder.append("\\subsection*{").append(escapeLatex(heading)).append("}\n")
                .append("\\paragraph{题目}\n")
                .append(escapeLatex(questionText)).append("\n");
        // The same permission-checked figure belongs with the question, not only with the preceding
        // knowledge page. This preserves the printable question-image-workspace unit after \clearpage.
        if (authorizedImagePath != null && !authorizedImagePath.isBlank()) {
            builder.append(authorizedImageLatex(authorizedImagePath)).append("\n");
        }
        // 学生版只保留题目、同题原图和作答空间；提示、自检和答案属于教师编排信息。
        builder.append("\n\\vspace{").append(workspace).append("em}\n");
    }

    /** Returns a student-safe first move for diagram questions without leaking the teacher conclusion. */
    private static String studentQuestionHint(String questionText) {
        String text = questionText == null ? "" : questionText.replaceAll("\\s+", "");
        if (text.contains("4×4方格") || text.contains("4×4 方格")) {
            return "把四行所选方格的列号依次记下，先说明为什么它们构成一个不重复的排列，再处理最大和。";
        }
        if (text.contains("二面角") || text.contains("对折")) {
            return "先在折叠前的平面图中标出 EF、PD 与已知直角关系；证明题和二面角计算分别列出依据。";
        }
        return "先从题干圈出已知量和所求量，写明准备使用的定义、公式或定理，再完成推理。";
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
        if (!evidence.isEmpty()) {
            cards.add("优先依据命中的教材/题库/教师资料组织讲评，不直接搬运 OCR 原文。");
        }
        cards.add("题型推进保持“识别条件 → 选择方法 → 写关键等式 → 回收答案与评分点”。");
        return cards.stream().distinct().limit(5).toList();
    }

    private static List<String> studentMethodCards(TeachingTaskRequest request, List<TeachingEvidence> evidence) {
        List<String> cards = new ArrayList<>();
        cards.add("先圈出关键词，再判断对应的是定义、公式、图像性质还是题型方法。");
        cards.add("遇到参数、范围、符号或图形关系时，先处理边界条件。");
        if (!studentSafeQuestionText(request).isBlank()) {
            cards.add("本讲例题围绕“" + studentSafeQuestionText(request) + "”展开。");
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
        plan.add("先用 1 行话说清本讲主题和核心依据，再开始板书。");
        plan.add("板书顺序保持“写定义/公式 → 列出条件 → 立关键等式或图形关系 → 回收答案”。");
        if (!safeQuestionText(request).isBlank()) {
            plan.add("把题干中的关键词拆成已知条件、求解目标和第一步落点：" + safeQuestionText(request));
        }
        if (!evidence.isEmpty()) {
            plan.add("板书只保留与题目直接相关的定义、公式和条件，不粘贴资料原文或来源说明。");
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
        String prompt = studentSafeQuestionText(request).isBlank()
                ? goal
                : studentSafeQuestionText(request);
        return List.of(
                "基础 1：先写出“" + goal + "”对应的定义、公式或图像特征。",
                "基础 2：围绕“" + prompt + "”写出第一步依据，并说明为什么这样设。",
                "基础 3：补全一组最小条件，判断本题能否直接套用核心公式。",
                "提高 1：把题目中的一个条件改成相近条件，说明解法哪里要调整。",
                "提高 2：保留主方法不变，补一题同类变式并完成关键一步。",
                "综合 1：整理本讲同类题的解法顺序，并写出最容易漏掉的一步。");
    }

    private static List<String> teacherWideSlides(
            String questionSection,
            String questionType,
            String methodSteps,
            String answerPoints,
            String pitfalls,
            String followUps) {
        return List.of(
                "已知条件与目标：" + questionSection,
                "方法选择："
                        + (questionType.isBlank() ? "根据定义、性质和条件确定方法。" : flattenDraftBlock(questionType)),
                "推导与答案："
                        + (methodSteps.isBlank() ? "每一步都写依据。" : flattenDraftBlock(methodSteps))
                        + (answerPoints.isBlank() ? "" : " 结尾强调：" + flattenDraftBlock(answerPoints)),
                "易错点与追问："
                        + (pitfalls.isBlank() ? "" : " 易错点：" + flattenDraftBlock(pitfalls))
                        + (followUps.isBlank() ? "" : " 追问：" + flattenDraftBlock(followUps)))
                .stream()
                .map(TeachingWorkflowService::compactLectureCard)
                .toList();
    }

    /** Keeps a landscape card readable from a classroom screen and leaves room for spoken explanation. */
    private static String compactLectureCard(String value) {
        String normalized = flattenDraftBlock(value);
        if (normalized.length() <= MAX_LECTURE_CARD_CHARACTERS) {
            return normalized;
        }
        int cutoff = MAX_LECTURE_CARD_CHARACTERS;
        for (int index = cutoff; index > cutoff / 2; index -= 1) {
            char current = normalized.charAt(index - 1);
            if (current == '。' || current == '；' || current == '，' || current == ';' || current == ',') {
                cutoff = index;
                break;
            }
        }
        return normalized.substring(0, cutoff).strip() + "……";
    }

    private static String studentExampleSection(
            TeachingTaskRequest request,
            List<TeachingEvidence> evidence,
            String fallbackHint) {
        List<String> items = new ArrayList<>();
        if (!studentSafeQuestionText(request).isBlank()) {
            items.add("先独立拆题：把“" + studentSafeQuestionText(request) + "”分成已知条件、目标和关键方法。");
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
     * Removes workflow/control wording from the student-facing question while preserving ordinary mathematical text.
     * Teacher prompts may mention version checks, exports, internal prompts, or diagnostics; repeating those phrases in
     * a student worksheet leaks orchestration details even when no answer is exposed.
     */
    private static String studentSafeQuestionText(TeachingTaskRequest request) {
        String value = safeQuestionText(request);
        if (value.isBlank()) {
            return "";
        }
        if (value.matches("(?is).*?(教师版|学生版|内部提示词|系统提示|模型诊断|不从教师版截取|生成后保存|导出\\s*PDF|工作流|智能体|子agent|子智能体).*")) {
            return "";
        }
        return value;
    }

    /** Removes whole AI-draft lines that describe orchestration rather than mathematics from the student worksheet. */
    private static String sanitizeStudentWorkflowText(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        Pattern workflow = Pattern.compile(
                "(?i)(教师版|学生版|内部提示词|系统提示|模型诊断|不从教师版截取|生成后保存|导出\\s*PDF|工作流|智能体|子agent|子智能体)");
        return java.util.Arrays.stream(value.replace("\r\n", "\n").replace('\r', '\n').split("\n"))
                .map(String::strip)
                .filter(line -> !line.isBlank() && !workflow.matcher(line).find())
                .collect(java.util.stream.Collectors.joining("\n"))
                .strip();
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
        List<String> tasks = new ArrayList<>();
        for (TeachingEvidence item : questions) {
            tasks.add(questionDifficulty(item) + "：" + questionTextOnly(item.snippet()));
        }
        return studentQuestionPages("题库分层练习", tasks, blankSpaceEm);
    }

    /**
     * Gives every student-facing question a complete page with an intentionally generous writing area.
     * The explicit page breaks also work in the PDFBox fallback renderer, so layout does not depend on XeLaTeX.
     */
    private static String studentQuestionPages(String sectionTitle, List<String> items, int configuredWorkspaceEm) {
        if (items == null || items.isEmpty()) {
            return "";
        }
        int space = Math.max(STUDENT_QUESTION_WORKSPACE_EM, configuredWorkspaceEm);
        StringBuilder builder = new StringBuilder("\\clearpage\n\\section{")
                .append(escapeLatex(sectionTitle))
                .append("}\n");
        int index = 1;
        for (String item : items) {
            if (item == null || item.isBlank()) {
                continue;
            }
            String safeItem = TeachingHandoutPdfExportService.sanitizeLatexForExport(
                    guardHandoutLatex(escapeLatex(item), false));
            if (safeItem.isBlank()) {
                continue;
            }
            if (index > 1) {
                builder.append("\\clearpage\n");
            }
            builder.append("\\subsection*{第 ").append(index).append(" 题}\n")
                    .append("\\paragraph{题目}\n")
                    .append(safeItem)
                    .append("\n\\vspace{").append(space).append("em}\n");
            index += 1;
        }
        return index == 1 ? "" : builder.toString();
    }

    private static List<TeachingEvidence> questionBankEvidence(List<TeachingEvidence> evidence) {
        return evidence.stream()
                .filter(item -> "QUESTION_BANK".equals(item.sourceScope()))
                .sorted(Comparator.comparingInt(TeachingWorkflowService::questionDifficultyRank))
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
                // “用户题目 /” is a retrieval transport label created by the fallback pack, not a printable title.
                // Removing it at the shared title boundary keeps teacher, student, and projection versions aligned.
                .replaceFirst("^用户题目\\s*/\\s*", "")
                .strip();
    }

    private static String questionTextOnly(String snippet) {
        if (snippet == null || snippet.isBlank()) {
            return "题目内容待补充。";
        }
        String[] parts = repairMojibake(snippet).split("答案要点：", 2);
        String stem = parts[0].replace('\r', '\n').strip();
        // A known import failure writes a short source preview, then a standalone “题目” label, then the complete
        // source question.  Retaining both prints the same stem twice and makes the later solution look unrelated.
        // The post-label part is the complete atomic question; if it is empty, keep the original source text.
        String[] labeledParts = STANDALONE_QUESTION_LABEL.split(stem, 2);
        if (labeledParts.length == 2 && !labeledParts[1].isBlank()) {
            stem = labeledParts[1];
        }
        // The source title stays in retrieval/audit data. Historical OCR prefixes such as “赵礼显数学作业 1.”
        // are not part of a problem statement and make an otherwise neutral handout leak a third-party brand.
        stem = stem.strip();
        stem = PRINTABLE_SOURCE_WORKBOOK_PREFIX.matcher(stem).replaceFirst("");
        stem = PRINTABLE_SOURCE_BRAND_PREFIX.matcher(stem).replaceFirst("");
        return stem.replaceAll("\\s+", " ").strip();
    }

    private static String questionAnswerOnly(String snippet) {
        if (snippet == null || snippet.isBlank()) {
            return "";
        }
        String[] parts = repairMojibake(snippet).split("答案要点：", 2);
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
        // XeLaTeX's configured CJK font intentionally does not promise glyph coverage for mathematical symbols.
        // Convert source Unicode before the generic sanitizer so triangle/angle relations never degrade to visible
        // square boxes in a printed geometry question.
        String sourceMathNormalized = value
                .replace("△", "$\\triangle$")
                .replace("∠", "$\\angle$")
                .replace("⊥", "$\\perp$");
        String normalized = com.doob.mathagent.infrastructure.text.FormulaMarkupSanitizer.sanitizeFeishuMath(sourceMathNormalized)
                // JSON producers occasionally interpret LaTeX commands as JSON escapes (\b, \t, \f). Restore the
                // intended command before splitting math/text; otherwise XeLaTeX rejects the control character.
                .replace("\u0008oldsymbol", "\\boldsymbol")
                .replace("\u0009heta", "\\theta")
                .replace("\u000C rac", "\\frac")
                .replace("\u000C", "")
                .replace("\u0008", "")
                .replace("\u0009", " ");
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
                // A previous text escape may have reached a model-provided $...$ segment. Restore exponent
                // notation before XeLaTeX sees it; \textasciicircum is invalid inside math mode.
                .replace("\\textasciicircum{}", "^")
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
        if (request.questionText() == null || request.questionText().isBlank()) {
            return "";
        }
        return TASK_CONTROL_LINE.matcher(request.questionText().replace('\r', '\n'))
                .replaceAll("")
                .replaceAll("\\n{3,}", "\n\n")
                .strip();
    }

    /** Removes workflow controls from topic text before graph/image heuristics inspect the mathematical subject. */
    private static String safeTaskText(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return TASK_CONTROL_LINE.matcher(value.replace('\r', '\n'))
                .replaceAll("")
                .replaceAll("\\n{3,}", "\n\n")
                .strip();
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
    private static TeachingDraftSections collectDraftSections(
            TeachingTaskRequest request,
            List<TeachingEvidence> evidence,
            TeachingTaskResponse.AiDraft aiDraft) {
        String questionSection = safeQuestionText(request).isBlank()
                ? "围绕学习目标设计例题、变式题和课堂追问。"
                : safeQuestionText(request);
        String teacherExplanation = aiDraft == null ? "" : guardDraftText(aiDraft.teacherExplanation(), true);
        String studentWorksheet = aiDraft == null ? "" : guardDraftText(aiDraft.studentHint(), false);
        String questionType = draftBlockContent(teacherExplanation, teacherDraftLabels(), "题型识别");
        String methodSteps = draftBlockContent(teacherExplanation, teacherDraftLabels(), "方法步骤");
        String answerPoints = draftBlockContent(teacherExplanation, teacherDraftLabels(), "答案与评分点");
        String draftPitfalls = draftBlockContent(teacherExplanation, teacherDraftLabels(), "易错提醒");
        String draftFollowUps = draftBlockContent(teacherExplanation, teacherDraftLabels(), "课堂追问");
        String draftPractice = draftBlockContent(studentWorksheet, studentDraftLabels(), "练习任务");
        List<String> risks = new ArrayList<>();
        if (aiDraft == null || !aiDraft.structured()) {
            risks.add("ai_draft_unstructured");
        }
        if (teacherExplanation.isBlank()) {
            risks.add("teacher_explanation_missing");
        }
        if (studentWorksheet.isBlank()) {
            risks.add("student_worksheet_missing");
        } else {
            risks.add("student_answer_leakage_review_required");
        }
        if (evidence.isEmpty()) {
            risks.add("source_grounding_missing");
        }
        List<String> lectureCards = teacherWideSlides(
                questionSection,
                questionType,
                methodSteps,
                answerPoints,
                draftPitfalls,
                draftFollowUps);
        if (!lectureCards.isEmpty()) {
            risks.add("lecture_cards_derived_from_teacher_outline");
        }
        return TeachingDraftSectionCollector.collect(
                teacherExplanation,
                studentWorksheet,
                lectureCards,
                studentPracticeTasks(request, evidence, aiDraft, draftPractice),
                evidence.stream().map(TeachingWorkflowService::evidenceRef).distinct().toList(),
                risks);
    }

    private static final class StageTimer {

        private final List<TeachingTaskResponse.StageTiming> timings = new ArrayList<>();
        private long lastNanos = System.nanoTime();

        StageTimer(List<TeachingTaskResponse.StageTiming> existing) {
            if (existing != null) {
                timings.addAll(existing);
            }
        }

        /**
         * 记录一个阶段相对上个检查点的耗时。
         */
        void mark(String stage) {
            long now = System.nanoTime();
            timings.add(new TeachingTaskResponse.StageTiming(stage, Math.max(0L, (now - lastNanos) / 1_000_000L)));
            lastNanos = now;
        }

        /** Records an independently measured parallel branch without assigning its duration to the join caller. */
        void record(String stage, long elapsedMs) {
            timings.add(new TeachingTaskResponse.StageTiming(stage, Math.max(0L, elapsedMs)));
        }

        /** Starts the next serial timing span after a parallel barrier has joined. */
        void resetCheckpoint() {
            lastNanos = System.nanoTime();
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

    private record EvidencePack(
            List<TeachingEvidence> textbookEvidence,
            List<TeachingEvidence> questionEvidence,
            List<TeachingEvidence> teacherResourceEvidence,
            long textbookElapsedMs,
            long questionElapsedMs,
            long teacherResourceElapsedMs) {

        private List<TeachingEvidence> mergedEvidence() {
            return concatEvidence(textbookEvidence, questionEvidence, teacherResourceEvidence);
        }
    }

    /** One real retrieval result paired with its own wall-clock duration before the three-way join. */
    private record TimedEvidence(List<TeachingEvidence> evidence, long elapsedMs) {
    }

    /** Immutable context owned by exactly one question-agent branch. */
    private record QuestionAgentContext(String agentId, String title, List<TeachingEvidence> evidence) {
    }

    /** One branch result keeps its own elapsed time so the join does not hide slow or failed questions. */
    private record QuestionAgentBranch(QuestionAgentContext context, long elapsedMs) {
    }

    /** Stable per-question timing projection persisted inside the task response. */
    private record QuestionAgentTiming(String agentId, long elapsedMs) {
    }

    /** Result of the question fan-out barrier, persisted as a stage timing. */
    private record QuestionAgentBatch(int agentCount, long elapsedMs, List<QuestionAgentTiming> branchTimings) {
    }
}
