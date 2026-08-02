package com.doob.mathagent.teaching.service;

import com.doob.mathagent.agent.service.AgentTraceRecord;
import com.doob.mathagent.agent.service.AgentTraceStore;
import com.doob.mathagent.agent.service.AgentRunExecutionService;
import com.doob.mathagent.agent.service.AgentRunPlanService;
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

/**
 * 教学任务 DAG 编排服务：把用户学习目标、资源复用、教材检索、ReAct 解题和讲义生成串成可恢复任务。
 */
@Service
public class TeachingWorkflowService extends TeachingWorkflowExecutionSupport {

    /** Keeps the source conversion visible on the facade for diagnostics and older integration callers. */
    protected TeachingEvidence toTeacherResourceEvidence(
            TeacherResourceBlockSearchResponse.Hit hit,
            TeachingRequestContext context) {
        return super.toTeacherResourceEvidence(hit, context);
    }

    static final int RESUME_EVIDENCE_LIMIT = 3;
    /** Minimum blank writing area reserved after every standalone student question. */
    static final int STUDENT_QUESTION_WORKSPACE_EM = 18;
    /** Blank projection area that keeps each 16:10 lecture unit visually separated. */
    static final int LECTURE_CARD_WORKSPACE_EM = 14;
    /** Upper bound for concurrent isolated question branches; keeps provider and thread pressure predictable. */
    static final int QUESTION_AGENT_MAX_PARALLELISM = 4;
    /** Maximum visible characters on one projection page, preserving a large annotation area below the prompt. */
    static final int MAX_LECTURE_CARD_CHARACTERS = 220;
    /** A projection card explains only the three decision points that fit beside a full real question. */
    static final int LECTURE_PROJECTION_STEP_LIMIT = 3;
    /** Balanced columns keep a source diagram with its exact question instead of pushing it to a following page. */
    static final String LECTURE_COLUMN_WIDTH = "0.485\\linewidth";
    /** Diagram height is bounded inside the left column so it cannot force a second 16:10 page. */
    static final String LECTURE_IMAGE_MAX_HEIGHT = "0.54\\textheight";
    /** Printed figure height leaves a full prompt-plus-solution unit together while keeping a source page legible. */
    static final String PRINTED_IMAGE_MAX_HEIGHT = "0.42\\textheight";
    /** Printed figure width keeps portrait page assets readable rather than reducing them to a thumbnail. */
    static final String PRINTED_IMAGE_WIDTH = "0.90\\linewidth";
    /** A generation evidence item must be one question, not an OCR page bundle imported as one bank record. */
    static final int MAX_HANDOUT_QUESTION_TEXT_CHARACTERS = 1200;
    /** A second top-level question number means the importer failed to split the source document. */
    static final int MAX_TOP_LEVEL_QUESTION_MARKERS = 1;
    /** Extra document-scoped hits used when a visual question's first block is text-only but a sibling carries assets. */
    static final int TEACHER_RESOURCE_IMAGE_RECOVERY_LIMIT = 3;
    /** Bounded RAG payload sent to a handout draft: enough for a source conclusion without exhausting model context. */
    static final int MAX_TEACHING_EVIDENCE_CHARS = 120;
    /** Reserve the beginning of a source excerpt for the problem context before retaining a later conclusion. */
    static final int TEACHING_EVIDENCE_INTRO_CHARS = 42;
    /** A concise terminal result/method clause retained when a source block is longer than the model evidence budget. */
    static final int TEACHING_EVIDENCE_CONCLUSION_CHARS = 70;
    /** Context retained immediately before an explicit source result marker such as “合计” or “答案”. */
    static final int TEACHING_EVIDENCE_MARKER_CONTEXT_CHARS = 18;
    /** Bounds fallback deduplication when a legacy source has no stable Feishu document token in its title. */
    static final int MAX_EVIDENCE_FINGERPRINT_CHARS = 160;
    /** Stable Feishu document tokens are preserved by synchronized document titles and identify mirrored copies. */
    static final Pattern FEISHU_DOCUMENT_TOKEN = Pattern.compile(
            "(?<![A-Za-z0-9])([A-Za-z][A-Za-z0-9]{11,})(?![A-Za-z0-9])");
    /** A colour count is a material condition in map-colouring problems, not a loose search keyword. */
    static final Pattern COLORING_TOPIC = Pattern.compile("(?:涂色|着色|颜色)");
    /** Require “种颜色” so the rule does not mistake “同一颜色” for a selectable-colour count. */
    static final Pattern COLOR_COUNT = Pattern.compile("([0-9一二三四五六七八九十]+)\\s*种(?:不同的)?颜色");
    /** A printable source result must be a complete arithmetic equality, never an OCR sentence around it. */
    static final Pattern VERIFIED_SUM_EXPRESSION = Pattern.compile(
            "(?<![0-9])([0-9]+(?:\\s*[+＋]\\s*[0-9]+)+\\s*=\\s*[0-9]+)(?![0-9])");
    /** Normalizes the bounded Chinese number vocabulary accepted by the colour-count condition. */
    static final Map<String, Integer> CHINESE_COLOR_COUNTS = Map.of(
            "一", 1, "二", 2, "三", 3, "四", 4, "五", 5,
            "六", 6, "七", 7, "八", 8, "九", 9, "十", 10);
    static final Pattern VISUAL_EVIDENCE_REQUEST = Pattern.compile(
            "(?:图|图片|如图|地图|image|figure)", Pattern.CASE_INSENSITIVE);
    /** A stem that points at a diagram is incomplete until the same authorized diagram is synchronized. */
    static final Pattern FIGURE_DEPENDENT_QUESTION = Pattern.compile("(?:如图|见图|下图|上图|图中)");
    /** An OCR square in a mathematical stem is an unresolved relation, not a printable answer blank. */
    static final Pattern UNRESOLVED_OCR_MATH_GLYPH = Pattern.compile("[□�]");
    /** Importers sometimes put a display label between a shortened OCR preview and the full question stem. */
    static final Pattern STANDALONE_QUESTION_LABEL = Pattern.compile("(?m)^\\s*题目\\s*[：:]?\\s*$");
    /** Source workbook banners are audit metadata, never mathematical stem content. */
    static final Pattern PRINTABLE_SOURCE_WORKBOOK_PREFIX = Pattern.compile(
            "^(?:(?:赵礼显数学|飞猪数学)\\s*)?(?:作业|讲义|课堂练习)\\s*\\d+\\s*[.．、:：]?\\s*");
    /** Product/source labels from historical snapshots must not become a teacher-facing attribution. */
    static final Pattern PRINTABLE_SOURCE_BRAND_PREFIX = Pattern.compile("^(?:赵礼显数学|飞猪数学)\\s*");
    /** A qualified printable handout requires ten distinct, source-traceable real questions. */
    static final int MIN_QUALIFIED_HANDOUT_QUESTION_COUNT = 10;
    /** A compilation search reads several requested result pages before selecting one coherent source document. */
    static final int QUESTION_BANK_COMPILATION_QUERY_MULTIPLIER = 4;
    /** Stable registry identity for the user-authorized Zhao master; used only for renderer-owned content structure. */
    static final String ZHAO_MASTER_TEMPLATE_CODE = "zhao_lixian_2025_master_v1";
    /** A fuzzy point/source binding needs two independent curriculum terms whenever two are available. */
    static final int MIN_DISTINCT_POINT_TERMS_FOR_FUZZY_SUPPORT = 2;

    static final Pattern DRAFT_ORDERED_LINE = Pattern.compile("^\\s*(?:\\d+|[一二三四五六七八九十]+)[.、)]\\s+(.+)$");
    /** Original source number recovered from a synchronized atomic question stem. */
    static final Pattern SOURCE_QUESTION_NUMBER = Pattern.compile("^\\s*(\\d{1,3})[.．、]");
    /** Top-level numbered model solution; indented numbered derivation steps deliberately do not match this form. */
    static final Pattern MODEL_EXPLANATION_HEADING = Pattern.compile(
            "(?m)^\\s*(?:第\\s*)?(?:题\\s*)?(\\d{1,3})(?:\\s*题)?[.．、:：]\\s*(?:【[^】]{1,32}】\\s*)?(.+)$");
    /** Some providers compact adjacent `题N：` units onto one line; restore only explicit top-level labels. */
    static final Pattern INLINE_MODEL_EXPLANATION_HEADING = Pattern.compile(
            "(?<!\\R)(?=(?:第\\s*)?(?:题\\s*)?\\d{1,3}(?:\\s*题)?[.．、:：])");
    /** A question-specific model excerpt needs enough shared prompt terms to avoid cross-question contamination. */
    static final int MIN_MODEL_PROMPT_MATCHES = 2;
    /** An explicit source-number match still needs a real reasoning chain; a bare conclusion is never publishable. */
    static final int MIN_NUMBERED_REASONING_CHARACTERS = 120;
    static final Pattern SUBSTANTIVE_REASONING_SIGNAL = Pattern.compile(
            "(?:条件识别|推导依据|步骤|计算|由[^。；]{1,}|因此|故|证明|结论)");
    static final Pattern TOP_LEVEL_QUESTION_MARKER = Pattern.compile("(?m)^\\s*\\d{1,3}[.、．]");
    static final Pattern DRAFT_BULLET_LINE = Pattern.compile("^\\s*[-•·]\\s+(.+)$");
    /** Optional model-authored strategy heading; the renderer validates and falls back to the concrete point title. */
    static final Pattern CUSTOM_METHOD_HEADING = Pattern.compile(
            "(?m)^\\s*(?:方法标题|策略标题|标题)\\s*[：:]\\s*(.{2,36})\\s*$");
    static final Pattern BLANK_PLACEHOLDER = Pattern.compile("_{3,}|＿{3,}");
    static final Pattern LATEX_HEADING_LINE = Pattern.compile("^\\\\(section\\*?|subsection\\*?|subsubsection\\*?|paragraph\\*?)\\{(.+)}\\s*$");
    static final Pattern INTERNAL_HANDOUT_LINE = Pattern.compile(
            "(?mi)^.*(?:MODEL_CALL|JSON_PARSE|\\btokens?\\b|模型健康|model health|debug|调试|JSON|内部提示词|内部提示|系统提示|提示词|方法标题|策略标题|OCR\\s*原文|\\{\\{[^\\r\\n]*\\}\\}|PDF\\s*(?:规则|排版|版式)|PDF\\s*版式|排版说明|版式要求|页眉|页脚|(?:页面颜色|颜色规则|讲评色|练习色)|渲染引擎|模板规则|页边距|虚线折叠|documentclass|usepackage|fancyhdr|pagestyle|begin\\{document}|end\\{document}|作为\\s*AI|as an AI|本页只保留|课堂任务|本讲任务|讲后自查|教师审校清单|横版讲解提纲|AI 知识定位|模板偏向|本讲更偏向).*$");
    /** Control and evaluation statements are not mathematical questions and must never become handout text. */
    static final Pattern TASK_CONTROL_LINE = Pattern.compile(
            "(?mi)^.*(?:题目入口|讲评入口|题型入口|知识入口|审题提醒|模板|benchmark|synthetic-natural|量化评测|投票|内部提示词|系统提示|提示词|生成后保存|导出\\s*PDF|工作流|智能体|子agent|子智能体|不从教师版截取|验证\\s*16:10).*$");
    static final Pattern STUDENT_FORBIDDEN_SECTION = Pattern.compile(
            "【(?:答案与评分点|参考答案|参考解析|评分标准|例题详解|完整解析|教师讲解|讲评主线|教师备注|板书设计)】[\\s\\S]*?(?=【|$)");
    static final Pattern STUDENT_FORBIDDEN_LINE = Pattern.compile(
            "(?m)^.*(?:答案[：:]|答案为|参考答案|参考解析|评分点|评分标准|完整解析|解答如下|解：|因此答案为|故答案为|教师讲解|讲评主线|板书设计).*$");
    static final Pattern VISIBLE_WORKSPACE_LABEL = Pattern.compile(
            "(?:课堂作答区|作答区|我的解答|推导区|手写区|教师手写区|留白区|空白区|板书区|教师板书区)\\s*[：:]?");
    static final Pattern VISIBLE_WORKSPACE_REFERENCE = Pattern.compile(
            "(?:写在|填写在|完成在|放在|留在)(?:课堂作答区|作答区|我的解答|推导区|手写区|教师手写区|留白区|空白区|板书区|教师板书区)");
    static final Pattern TOPIC_NOISE_WORD = Pattern.compile(
            "(?:请|生成|一份|关于|围绕|针对|包含|以及|并|和|与|及|从|到|开始|讲解|讲义|学习|学会|理解|掌握|做题|大题|小题|题型|例题|易错点|方法|流程|专题|训练|教师版|学生版|教师|学生|课堂|作答|补充要求|要求|目标|主题|知识点|基础|提高|综合|定义|图像|性质|题目|问题|讲清|讲透|入门|复习|巩固|提升|中的|中|的)");
    static final Set<String> TOPIC_GENERIC_TERMS = Set.of(
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
    static final Set<String> BROAD_TOPIC_TERMS = Set.of(
            "函数", "导数", "数列", "概率", "统计", "三角函数", "平面向量", "空间向量", "立体几何",
            "圆锥曲线", "直线", "圆", "棱柱", "棱锥", "体积", "夹角", "垂直", "平行");
    static final Set<String> CORE_TOPIC_PREFERENCES = Set.of(
            "函数", "导数", "双曲线", "椭圆", "抛物线", "圆锥曲线", "数列", "概率", "统计",
            "三角函数", "向量", "空间向量", "立体几何", "直线", "圆", "排列组合", "二项式");

    // Dependency state is inherited from TeachingWorkflowExecutionSupport; constructors initialize it.

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
            AgentRunPlanService agentRunPlanService,
            AgentRunExecutionService agentRunExecutionService,

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
        this.traceRecorder = new TeachingWorkflowTraceRecorder(agentTraceStore);
        this.agentRunPlanService = agentRunPlanService;
        this.agentRunExecutionService = agentRunExecutionService;
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
                null,
                null,
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
                "", "", "", "", List.of(), null, List.of(), null, null, null, null, null)
                .withPageChrome(normalizedRequest.headerLeft(), normalizedRequest.headerRight(),
                        normalizedRequest.footerLeft(), normalizedRequest.footerRight());
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
                failed.watermarkText(), failed.headerLeft(), failed.headerRight(), failed.footerLeft(), failed.footerRight(),
                null, null, null).normalize();
        String ownerKey = normalizedContext.ownerKey();
        String idempotencyKey = normalizedContext.idempotencyKey(request.clientRequestId());
        TeachingTaskResponse running = runningSnapshot(failed);
        // A manual resume starts a fresh worker retry budget. The MySQL store must explicitly transition the
        // execution row from terminal FAILED to RETRYING; normal snapshot saves intentionally preserve lease state.
        taskStore.prepareForResume(ownerKey, idempotencyKey, running);
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
    // Delegates the pure policy/rendering rule to TeachingWorkflowCorePolicy; lifecycle state stays in the facade.
    static int evidenceLimitForResume(TeachingTaskResponse task) { return TeachingWorkflowCorePolicy.evidenceLimitForResume(task); }
    // Delegates the pure policy/rendering rule to TeachingWorkflowCorePolicy; lifecycle state stays in the facade.
    static boolean hasRecoverableTeacherPublicationIssue(TeachingTaskResponse task) { return TeachingWorkflowCorePolicy.hasRecoverableTeacherPublicationIssue(task); }
    // Delegates the pure policy/rendering rule to TeachingWorkflowCorePolicy; lifecycle state stays in the facade.
    static StudentMemoryResponse fromMemoryReuse(TeachingTaskResponse.MemoryReuse memory) { return TeachingWorkflowCorePolicy.fromMemoryReuse(memory); }
    // Delegates the pure policy/rendering rule to TeachingWorkflowCorePolicy; lifecycle state stays in the facade.
    static boolean evidenceCheckpointComplete(TeachingTaskResponse checkpoint) { return TeachingWorkflowCorePolicy.evidenceCheckpointComplete(checkpoint); }
    // Delegates the pure policy/rendering rule to TeachingWorkflowCorePolicy; lifecycle state stays in the facade.
    static boolean requiresFreshEvidence(TeachingTaskResponse checkpoint) { return TeachingWorkflowCorePolicy.requiresFreshEvidence(checkpoint); }
    // Delegates the pure policy/rendering rule to TeachingWorkflowCorePolicy; lifecycle state stays in the facade.
    static TeachingTaskResponse runningSnapshot(TeachingTaskResponse task) { return TeachingWorkflowCorePolicy.runningSnapshot(task); }
    // Delegates the pure policy/rendering rule to TeachingWorkflowCorePolicy; lifecycle state stays in the facade.
    static TeachingTaskResponse failedSnapshot(TeachingTaskResponse task, Throwable failure) { return TeachingWorkflowCorePolicy.failedSnapshot(task, failure); }

    /**
     * 按 taskId 查询当前主体拥有的教学任务。
     */
    public Optional<TeachingTaskResponse> get(String taskId, TeachingRequestContext context) {
        return taskStore.findByTaskIdAndOwnerKey(taskId, context.normalize().ownerKey());
    }

    /**
     * Applies the publish/reject decision after a task entered WAITING_REVIEW.
     *
     * <p>Only teacher/admin subjects may release a handout. The method preserves the generated artefacts so a reject
     * can be resumed using the existing durable recovery workflow instead of paying for duplicate retrieval.</p>
     */
    public TeachingTaskResponse decideReview(
            String taskId, TeachingRequestContext context, String decision, String comment) {
        TeachingRequestContext normalized = context.normalize();
        if (!"teacher".equals(normalized.subjectType()) && !"admin".equals(normalized.subjectType())) {
            throw new IllegalArgumentException("Only teacher or admin may decide handout review");
        }
        TeachingTaskResponse existing = taskStore.findByTaskIdAndOwnerKey(taskId, normalized.ownerKey())
                .orElseThrow(() -> new IllegalArgumentException("Teaching task not found"));
        if (existing.status() != TeachingTaskStatus.WAITING_REVIEW) {
            throw new IllegalStateException("Teaching task is not waiting for review");
        }

        String normalizedDecision = decision == null ? "" : decision.strip().toUpperCase(Locale.ROOT);
        TeachingTaskResponse updated = switch (normalizedDecision) {
            // Approval is the rendering boundary.  The evidence and structured draft were already quality-gated, so
            // this method deliberately does not retrieve again or make another model call.
            case "APPROVE" -> renderApprovedHandoutVersions(existing, normalized);
            case "REJECT" -> existing.withReviewStatus(TeachingTaskStatus.FAILED,
                    comment == null || comment.isBlank() ? "Human reviewer rejected this handout" : comment.strip());
            default -> throw new IllegalArgumentException("Unsupported review decision: " + decision);
        };
        return taskStore.save(normalized.ownerKey(), normalized.idempotencyKey(existing.clientRequestId()), updated);
    }
    // Implementation moved to TeachingWorkflowExecutionSupport to keep the facade focused on lifecycle coordination.

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
        if (queued.status() == TeachingTaskStatus.COMPLETED
                || queued.status() == TeachingTaskStatus.WAITING_REVIEW
                || queued.status() == TeachingTaskStatus.DRAFT_ONLY) {
            return;
        }
        // A retry message is published after failQueued persists the diagnostic FAILED snapshot. The MySQL lease
        // has already changed the execution row back to RUNNING before this method is called, so treating that
        // snapshot as terminal would acknowledge the retry without executing it and then falsely mark it complete.
        // Truly terminal FAILED rows cannot acquire a lease and therefore never reach this boundary.
        TeachingRequestContext context = new TeachingRequestContext(
                queued.tenantId(), queued.subjectType(), queued.subjectId(), "lecture-worker").normalize();
        TeachingTaskRequest request = new TeachingTaskRequest(
                queued.clientRequestId(), queued.questionText(), queued.learningGoal(), evidenceLimitForResume(queued),

                queued.selectedTemplate() == null ? null : queued.selectedTemplate().templateCode(), queued.watermarkText(),
                queued.headerLeft(), queued.headerRight(), queued.footerLeft(), queued.footerRight(), null, null, null).normalize();
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
    // Delegates the pure policy/rendering rule to TeachingWorkflowCorePolicy; lifecycle state stays in the facade.
    static String normalizeHandoutVersion(String version) { return TeachingWorkflowCorePolicy.normalizeHandoutVersion(version); }

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
    // Implementation moved to TeachingWorkflowExecutionSupport to keep the facade focused on lifecycle coordination.
    // Implementation moved to TeachingWorkflowExecutionSupport to keep the facade focused on lifecycle coordination.
    // Implementation moved to TeachingWorkflowExecutionSupport to keep the facade focused on lifecycle coordination.
    // Delegates the pure policy/rendering rule to TeachingWorkflowCorePolicy; lifecycle state stays in the facade.
    static boolean passedAutomaticReview(TeachingDraftMergeResult mergeResult) { return TeachingWorkflowCorePolicy.passedAutomaticReview(mergeResult); }
    // Delegates the pure policy/rendering rule to TeachingWorkflowCorePolicy; lifecycle state stays in the facade.
    static TeachingHandoutVersions renderHandoutVersions(TeachingTaskRequest request, List<TeachingEvidence> evidence, List<TeachingKnowledgePointPack> knowledgePointPacks, StudentMemoryResponse memoryResponse, TeachingHandoutTemplateProfile template, TeachingTaskResponse.AiDraft aiDraft, TeachingDraftSections renderSections) { return TeachingWorkflowCorePolicy.renderHandoutVersions(request, evidence, knowledgePointPacks, memoryResponse, template, aiDraft, renderSections); }
    // Delegates the pure policy/rendering rule to TeachingWorkflowLatexRenderer; lifecycle state stays in the facade.
    static String guardHandoutLatex(String latex, boolean teacherVersion) { return TeachingWorkflowLatexRenderer.guardHandoutLatex(latex, teacherVersion); }
    // Delegates the pure policy/rendering rule to TeachingWorkflowLatexRenderer; lifecycle state stays in the facade.
    static void requireQualifiedQuestionEvidence(TeachingHandoutTemplateProfile template, List<TeachingEvidence> questionEvidence) { TeachingWorkflowLatexRenderer.requireQualifiedQuestionEvidence(template, questionEvidence); }
    // Delegates the pure policy/rendering rule to TeachingWorkflowLatexRenderer; lifecycle state stays in the facade.
    static void requireStructuredQuestionReasoning(TeachingHandoutTemplateProfile template, TeachingTaskResponse.AiDraft aiDraft) { TeachingWorkflowLatexRenderer.requireStructuredQuestionReasoning(template, aiDraft); }
    // Delegates the pure policy/rendering rule to TeachingWorkflowLatexRenderer; lifecycle state stays in the facade.
    static void requireQualifiedRenderedQuestionCount(TeachingHandoutTemplateProfile template, String teacherHandoutLatex) { TeachingWorkflowLatexRenderer.requireQualifiedRenderedQuestionCount(template, teacherHandoutLatex); }
    // Delegates the pure policy/rendering rule to TeachingWorkflowLatexRenderer; lifecycle state stays in the facade.
    static String removeVisibleWorkspaceLabels(String value) { return TeachingWorkflowLatexRenderer.removeVisibleWorkspaceLabels(value); }
    // Delegates the pure policy/rendering rule to TeachingWorkflowLatexRenderer; lifecycle state stays in the facade.
    static String buildLectureHandoutLatex(TeachingTaskRequest request, TeachingDraftSections draftSections) { return TeachingWorkflowLatexRenderer.buildLectureHandoutLatex(request, draftSections); }
    // Delegates the pure policy/rendering rule to TeachingWorkflowLatexRenderer; lifecycle state stays in the facade.
    static String buildLectureHandoutLatex(TeachingTaskRequest request, List<TeachingKnowledgePointPack> knowledgePointPacks, TeachingDraftSections draftSections) { return TeachingWorkflowLatexRenderer.buildLectureHandoutLatex(request, knowledgePointPacks, draftSections); }
    // Delegates the pure policy/rendering rule to TeachingWorkflowLatexRenderer; lifecycle state stays in the facade.
    static String lectureQuestionPages(List<TeachingKnowledgePointPack> packs, List<String> questionScopedSteps) { return TeachingWorkflowLatexRenderer.lectureQuestionPages(packs, questionScopedSteps); }
    // Delegates the pure policy/rendering rule to TeachingWorkflowLatexRenderer; lifecycle state stays in the facade.
    static int appendLectureQuestionPage(StringBuilder builder, int questionNumber, TeachingKnowledgePointPack pack, String label, TeachingEvidence question, List<String> questionScopedSteps) { return TeachingWorkflowLatexRenderer.appendLectureQuestionPage(builder, questionNumber, pack, label, question, questionScopedSteps); }
    // Delegates the pure policy/rendering rule to TeachingWorkflowLatexRenderer; lifecycle state stays in the facade.
    static String lectureQuestionConclusion(String questionText, String sourceAnswer, String evidenceConclusion) { return TeachingWorkflowLatexRenderer.lectureQuestionConclusion(questionText, sourceAnswer, evidenceConclusion); }
    // Delegates the pure policy/rendering rule to TeachingWorkflowLatexRenderer; lifecycle state stays in the facade.
    static List<String> lectureQuestionBankSteps(String answerEvidence) { return TeachingWorkflowLatexRenderer.lectureQuestionBankSteps(answerEvidence); }
    // Delegates the pure policy/rendering rule to TeachingWorkflowLatexRenderer; lifecycle state stays in the facade.
    static List<String> lectureQuestionFallbackPath(String questionText) { return TeachingWorkflowLatexRenderer.lectureQuestionFallbackPath(questionText); }
    // Delegates the pure policy/rendering rule to TeachingWorkflowLatexRenderer; lifecycle state stays in the facade.
    static String lectureTopicSummary(String questionText) { return TeachingWorkflowLatexRenderer.lectureTopicSummary(questionText); }
    // Delegates the pure policy/rendering rule to TeachingWorkflowLatexRenderer; lifecycle state stays in the facade.
    static List<String> lectureDraftSteps(TeachingDraftSections draftSections) { return TeachingWorkflowLatexRenderer.lectureDraftSteps(draftSections); }
    // Delegates the pure policy/rendering rule to TeachingWorkflowLatexRenderer; lifecycle state stays in the facade.
    static boolean supportingEvidenceMatchesQuestion(String questionText, List<TeachingEvidence> supportingEvidence) { return TeachingWorkflowLatexRenderer.supportingEvidenceMatchesQuestion(questionText, supportingEvidence); }
    // Delegates the pure policy/rendering rule to TeachingWorkflowLatexRenderer; lifecycle state stays in the facade.
    static List<TeachingEvidence> supportingEvidenceForQuestion(String questionText, List<TeachingEvidence> supportingEvidence) { return TeachingWorkflowLatexRenderer.supportingEvidenceForQuestion(questionText, supportingEvidence); }
    // Delegates the pure policy/rendering rule to TeachingWorkflowLatexRenderer; lifecycle state stays in the facade.
    static String lectureTopicHeading(String questionText) { return TeachingWorkflowLatexRenderer.lectureTopicHeading(questionText); }
    // Delegates the pure policy/rendering rule to TeachingWorkflowLatexRenderer; lifecycle state stays in the facade.
    static String lecturePathHeading(String questionText) { return TeachingWorkflowLatexRenderer.lecturePathHeading(questionText); }
    // Delegates the pure policy/rendering rule to TeachingWorkflowLatexRenderer; lifecycle state stays in the facade.
    static String lectureConclusion(List<TeachingEvidence> supportingEvidence) { return TeachingWorkflowLatexRenderer.lectureConclusion(supportingEvidence); }
    // Delegates the pure policy/rendering rule to TeachingWorkflowLatexRenderer; lifecycle state stays in the facade.
    static String lectureSourceResult(List<TeachingEvidence> supportingEvidence) { return TeachingWorkflowLatexRenderer.lectureSourceResult(supportingEvidence); }
    // Delegates the pure policy/rendering rule to TeachingWorkflowLatexRenderer; lifecycle state stays in the facade.
    static String lectureCardPages(List<String> cards, int workspaceEm, boolean startOnNewPage) { return TeachingWorkflowLatexRenderer.lectureCardPages(cards, workspaceEm, startOnNewPage); }
    // Delegates the pure policy/rendering rule to TeachingWorkflowLatexRenderer; lifecycle state stays in the facade.
    static String extractLatexSection(String latex, String sectionTitle) { return TeachingWorkflowLatexRenderer.extractLatexSection(latex, sectionTitle); }
    // Delegates the pure policy/rendering rule to TeachingWorkflowLatexRenderer; lifecycle state stays in the facade.
    static String removeEmptyTitledBlocks(String latex) { return TeachingWorkflowLatexRenderer.removeEmptyTitledBlocks(latex); }
    // Delegates the pure policy/rendering rule to TeachingWorkflowLatexRenderer; lifecycle state stays in the facade.
    static String renderNonEmptyTitleRange(String[] lines, int start, int end) { return TeachingWorkflowLatexRenderer.renderNonEmptyTitleRange(lines, start, end); }
    // Delegates the pure policy/rendering rule to TeachingWorkflowLatexRenderer; lifecycle state stays in the facade.
    static int latexHeadingLevel(String command) { return TeachingWorkflowLatexRenderer.latexHeadingLevel(command); }
    // Delegates the pure policy/rendering rule to TeachingWorkflowLatexRenderer; lifecycle state stays in the facade.
    static boolean hasRealLatexContent(String body) { return TeachingWorkflowLatexRenderer.hasRealLatexContent(body); }
    // Delegates the pure policy/rendering rule to TeachingWorkflowLatexRenderer; lifecycle state stays in the facade.
    static boolean isBlankWorkspaceLabelLine(String line) { return TeachingWorkflowLatexRenderer.isBlankWorkspaceLabelLine(line); }
    // Delegates the pure policy/rendering rule to TeachingWorkflowLatexRenderer; lifecycle state stays in the facade.
    static boolean isBlankOnlyLatexLine(String line) { return TeachingWorkflowLatexRenderer.isBlankOnlyLatexLine(line); }
    // Delegates the pure policy/rendering rule to TeachingWorkflowLatexRenderer; lifecycle state stays in the facade.
    static String guardDraftText(String value, boolean teacherVersion) { return TeachingWorkflowLatexRenderer.guardDraftText(value, teacherVersion); }
    // Delegates the pure policy/rendering rule to TeachingWorkflowLatexRenderer; lifecycle state stays in the facade.
    static List<String> guardDraftItems(List<String> values, boolean teacherVersion) { return TeachingWorkflowLatexRenderer.guardDraftItems(values, teacherVersion); }
    // Implementation moved to TeachingWorkflowExecutionSupport to keep the facade focused on lifecycle coordination.

    /**
     * Builds a safe trace message without raw model content or the raw student question.
     */
    static String aiDraftTraceMessage(TeachingTaskResponse.AiDraft aiDraft) {
        String parseState = aiDraft.structured() ? "structured" : "raw";
        return "Teaching AI draft " + parseState
                + "; retry=" + aiDraft.retryCount() + "/" + aiDraft.maxRetries()
                + "; recovered=" + aiDraft.recoveredAfterRetry()
                + "; events=" + (aiDraft.recoveryEvents() == null ? 0 : aiDraft.recoveryEvents().size());
    }

    /**
     * Converts one evidence row to a trace-safe reference id.
     */
    static String evidenceRef(TeachingEvidence evidence) {
        return evidence.sourceScope() + ":" + evidence.sourceTitle() + ":" + evidence.chunkId();
    }

    static StudentMemoryCommand memoryRequest(TeachingTaskRequest request, TeachingRequestContext context) {
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
    static TeachingTaskResponse.MemoryReuse toMemoryReuse(StudentMemoryResponse response) {
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
    static String retrievalQuery(TeachingTaskRequest request) {
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
    // Implementation moved to TeachingWorkflowExecutionSupport to keep the facade focused on lifecycle coordination.
    // Implementation moved to TeachingWorkflowExecutionSupport to keep the facade focused on lifecycle coordination.

    static String resolveAuthorizedTextbookImage(Path corpusRoot, Path bookRoot, String relativePath) {
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
    // Implementation moved to TeachingWorkflowExecutionSupport to keep the facade focused on lifecycle coordination.
    // Implementation moved to TeachingWorkflowExecutionSupport to keep the facade focused on lifecycle coordination.

    static TimedEvidence awaitEvidence(
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
    static TimedEvidence timeEvidence(java.util.function.Supplier<List<TeachingEvidence>> supplier) {
        long startedNanos = System.nanoTime();
        List<TeachingEvidence> evidence = supplier.get();
        return new TimedEvidence(
                evidence == null ? List.of() : List.copyOf(evidence),
                Math.max(0L, (System.nanoTime() - startedNanos) / 1_000_000L));
    }
    // Implementation moved to TeachingWorkflowExecutionSupport to keep the facade focused on lifecycle coordination.

    /**
     * Selects one deterministic, source-traceable question pack for a continuous real-paper handout.
     *
     * <p>Grouping by the persisted source document, rather than a mutable title or a knowledge-point label, prevents
     * a 10-question imported exam from being diluted by older unrelated banks. The source block id is stable across a
     * re-import and retains page order for normal synchronized documents; the question id is a final tie breaker.</p>
     */
    static List<QuestionBankItemResponse> qualifiedSingleSourceQuestionPack(
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
    // Delegates the pure policy/rendering rule to TeachingWorkflowEvidencePolicy; lifecycle state stays in the facade.
    static List<QuestionBankItemResponse> deduplicateAtomicQuestionRows(Collection<QuestionBankItemResponse> candidates) { return TeachingWorkflowEvidencePolicy.deduplicateAtomicQuestionRows(candidates); }
    // Delegates the pure policy/rendering rule to TeachingWorkflowEvidencePolicy; lifecycle state stays in the facade.
    static String normalizedAtomicQuestionKey(String questionText) { return TeachingWorkflowEvidencePolicy.normalizedAtomicQuestionKey(questionText); }
    // Delegates the pure policy/rendering rule to TeachingWorkflowEvidencePolicy; lifecycle state stays in the facade.
    static boolean shouldPreferAtomicQuestion(QuestionBankItemResponse candidate, QuestionBankItemResponse existing) { return TeachingWorkflowEvidencePolicy.shouldPreferAtomicQuestion(candidate, existing); }
    // Delegates the pure policy/rendering rule to TeachingWorkflowEvidencePolicy; lifecycle state stays in the facade.
    static boolean requiresQualifiedQuestionCompilation(TeachingTaskRequest request) { return TeachingWorkflowEvidencePolicy.requiresQualifiedQuestionCompilation(request); }
    // Delegates the pure policy/rendering rule to TeachingWorkflowEvidencePolicy; lifecycle state stays in the facade.
    static List<String> curriculumPointQueries(TeachingTaskRequest request, List<TeachingEvidence> teacherResourceEvidence) { return TeachingWorkflowEvidencePolicy.curriculumPointQueries(request, teacherResourceEvidence); }
    // Delegates the pure policy/rendering rule to TeachingWorkflowEvidencePolicy; lifecycle state stays in the facade.
    static List<QuestionBankItemResponse> selectQuestionsByKnowledgePoint(TeachingTaskRequest request, List<QuestionBankItemResponse> candidates) { return TeachingWorkflowEvidencePolicy.selectQuestionsByKnowledgePoint(request, candidates); }
    // Delegates the pure policy/rendering rule to TeachingWorkflowEvidencePolicy; lifecycle state stays in the facade.
    static int compilationSearchLimit(TeachingTaskRequest request) { return TeachingWorkflowEvidencePolicy.compilationSearchLimit(request); }
    // Implementation moved to TeachingWorkflowExecutionSupport to keep the facade focused on lifecycle coordination.
    // Delegates the pure policy/rendering rule to TeachingWorkflowEvidencePolicy; lifecycle state stays in the facade.
    static int questionDifficultyRank(QuestionBankItemResponse item) { return TeachingWorkflowEvidencePolicy.questionDifficultyRank(item); }
    // Delegates the pure policy/rendering rule to TeachingWorkflowEvidencePolicy; lifecycle state stays in the facade.
    static boolean hasSpecificQuestionTopicMatch(TeachingTaskRequest request, QuestionBankItemResponse question) { return TeachingWorkflowEvidencePolicy.hasSpecificQuestionTopicMatch(request, question); }
    // Delegates the pure policy/rendering rule to TeachingWorkflowEvidencePolicy; lifecycle state stays in the facade.
    static String questionKnowledgePointKey(TeachingTaskRequest request, QuestionBankItemResponse question) { return TeachingWorkflowEvidencePolicy.questionKnowledgePointKey(request, question); }
    // Delegates the pure policy/rendering rule to TeachingWorkflowEvidencePolicy; lifecycle state stays in the facade.
    static String canonicalQuestionTopic(TeachingTaskRequest request) { return TeachingWorkflowEvidencePolicy.canonicalQuestionTopic(request); }
    // Delegates the pure policy/rendering rule to TeachingWorkflowEvidencePolicy; lifecycle state stays in the facade.
    static List<String> explicitTopicCandidates(TeachingTaskRequest request, List<String> candidates) { return TeachingWorkflowEvidencePolicy.explicitTopicCandidates(request, candidates); }
    // Delegates the pure policy/rendering rule to TeachingWorkflowEvidencePolicy; lifecycle state stays in the facade.
    static boolean isAtomicQuestionBankItem(QuestionBankItemResponse question) { return TeachingWorkflowEvidencePolicy.isAtomicQuestionBankItem(question); }
    // Implementation moved to TeachingWorkflowExecutionSupport to keep the facade focused on lifecycle coordination.
    // Implementation moved to TeachingWorkflowExecutionSupport to keep the facade focused on lifecycle coordination.
    // Delegates the pure policy/rendering rule to TeachingWorkflowEvidencePolicy; lifecycle state stays in the facade.
    static String compactTeachingEvidence(String expandedEvidence, String snippetFallback) { return TeachingWorkflowEvidencePolicy.compactTeachingEvidence(expandedEvidence, snippetFallback); }
    // Delegates the pure policy/rendering rule to TeachingWorkflowEvidencePolicy; lifecycle state stays in the facade.
    static String sourceConclusionClause(String value) { return TeachingWorkflowEvidencePolicy.sourceConclusionClause(value); }
    // Delegates the pure policy/rendering rule to TeachingWorkflowEvidencePolicy; lifecycle state stays in the facade.
    static int firstMarkerIndex(String value, String... markers) { return TeachingWorkflowEvidencePolicy.firstMarkerIndex(value, markers); }
    // Delegates the pure policy/rendering rule to TeachingWorkflowEvidencePolicy; lifecycle state stays in the facade.
    static String sourceEvidenceWindow(String value, int markerIndex) { return TeachingWorkflowEvidencePolicy.sourceEvidenceWindow(value, markerIndex); }
    // Delegates the pure policy/rendering rule to TeachingWorkflowEvidencePolicy; lifecycle state stays in the facade.
    static int nextSentenceEnd(String value, int fromIndex) { return TeachingWorkflowEvidencePolicy.nextSentenceEnd(value, fromIndex); }
    // Implementation moved to TeachingWorkflowExecutionSupport to keep the facade focused on lifecycle coordination.
    // Delegates the pure policy/rendering rule to TeachingWorkflowEvidencePolicy; lifecycle state stays in the facade.
    static String teacherResourceSourceTitle(TeacherResourceBlockSearchResponse.Hit hit) { return TeachingWorkflowEvidencePolicy.teacherResourceSourceTitle(hit); }
    // Delegates the pure policy/rendering rule to TeachingWorkflowEvidencePolicy; lifecycle state stays in the facade.
    static boolean teacherHitRespectsColorCountConstraint(TeachingTaskRequest request, TeacherResourceBlockSearchResponse.Hit hit) { return TeachingWorkflowEvidencePolicy.teacherHitRespectsColorCountConstraint(request, hit); }
    // Delegates the pure policy/rendering rule to TeachingWorkflowEvidencePolicy; lifecycle state stays in the facade.
    static boolean evidenceRespectsColorCountConstraint(TeachingTaskRequest request, TeachingEvidence evidence) { return TeachingWorkflowEvidencePolicy.evidenceRespectsColorCountConstraint(request, evidence); }
    // Delegates the pure policy/rendering rule to TeachingWorkflowEvidencePolicy; lifecycle state stays in the facade.
    static boolean sourceRespectsColorCountConstraint(TeachingTaskRequest request, String... sourceParts) { return TeachingWorkflowEvidencePolicy.sourceRespectsColorCountConstraint(request, sourceParts); }
    // Delegates the pure policy/rendering rule to TeachingWorkflowEvidencePolicy; lifecycle state stays in the facade.
    static Set<Integer> colorCounts(String text) { return TeachingWorkflowEvidencePolicy.colorCounts(text); }
    // Delegates the pure policy/rendering rule to TeachingWorkflowEvidencePolicy; lifecycle state stays in the facade.
    static List<TeachingEvidence> concatEvidence(List<TeachingEvidence>... groups) { return TeachingWorkflowEvidencePolicy.concatEvidence(groups); }
    // Delegates the pure policy/rendering rule to TeachingWorkflowEvidencePolicy; lifecycle state stays in the facade.
    static List<TeachingEvidence> deduplicateSupportingEvidence(List<TeachingEvidence> candidates) { return TeachingWorkflowEvidencePolicy.deduplicateSupportingEvidence(candidates); }
    // Delegates the pure policy/rendering rule to TeachingWorkflowEvidencePolicy; lifecycle state stays in the facade.
    static String canonicalEvidenceKey(TeachingEvidence evidence) { return TeachingWorkflowEvidencePolicy.canonicalEvidenceKey(evidence); }
    // Delegates the pure policy/rendering rule to TeachingWorkflowEvidencePolicy; lifecycle state stays in the facade.
    static boolean shouldPreferEvidence(TeachingEvidence candidate, TeachingEvidence existing) { return TeachingWorkflowEvidencePolicy.shouldPreferEvidence(candidate, existing); }
    // Delegates the pure policy/rendering rule to TeachingWorkflowEvidencePolicy; lifecycle state stays in the facade.
    static List<TeachingEvidence> alignEvidenceToTopic(TeachingTaskRequest request, List<TeachingEvidence> evidence) { return TeachingWorkflowEvidencePolicy.alignEvidenceToTopic(request, evidence); }
    // Delegates the pure policy/rendering rule to TeachingWorkflowEvidencePolicy; lifecycle state stays in the facade.
    static boolean isBenchmarkEvidence(TeachingEvidence evidence) { return TeachingWorkflowEvidencePolicy.isBenchmarkEvidence(evidence); }
    // Delegates the pure policy/rendering rule to TeachingWorkflowEvidencePolicy; lifecycle state stays in the facade.
    static boolean hasLocalTeachingResource(List<TeachingEvidence> evidence) { return TeachingWorkflowEvidencePolicy.hasLocalTeachingResource(evidence); }
    // Delegates the pure policy/rendering rule to TeachingWorkflowEvidencePolicy; lifecycle state stays in the facade.
    static List<String> localResourceTopicKeywords(TeachingTaskRequest request) { return TeachingWorkflowEvidencePolicy.localResourceTopicKeywords(request); }

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
    // Delegates the pure policy/rendering rule to TeachingWorkflowEvidencePolicy; lifecycle state stays in the facade.
    static boolean hasReadableHandoutContent(String value) { return TeachingWorkflowEvidencePolicy.hasReadableHandoutContent(value); }
    // Delegates the pure policy/rendering rule to TeachingWorkflowEvidencePolicy; lifecycle state stays in the facade.
    static boolean containsProtocolOrDebugLeak(String value) { return TeachingWorkflowEvidencePolicy.containsProtocolOrDebugLeak(value); }
    // Delegates the pure policy/rendering rule to TeachingWorkflowEvidencePolicy; lifecycle state stays in the facade.
    static boolean looksCorruptedText(String value) { return TeachingWorkflowEvidencePolicy.looksCorruptedText(value); }
    // Delegates the pure policy/rendering rule to TeachingWorkflowEvidencePolicy; lifecycle state stays in the facade.
    static String safeFrontendText(String... values) { return TeachingWorkflowEvidencePolicy.safeFrontendText(values); }
    // Delegates the pure policy/rendering rule to TeachingWorkflowEvidencePolicy; lifecycle state stays in the facade.
    static int topicMatchScore(TeachingEvidence evidence, List<String> keywords) { return TeachingWorkflowEvidencePolicy.topicMatchScore(evidence, keywords); }

    // Delegates the pure policy/rendering rule to TeachingWorkflowEvidencePolicy; lifecycle state stays in the facade.
    static String compactEvidenceText(TeachingEvidence evidence) { return TeachingWorkflowEvidencePolicy.compactEvidenceText(evidence); }
    // Delegates the pure policy/rendering rule to TeachingWorkflowEvidencePolicy; lifecycle state stays in the facade.
    static List<String> topicKeywords(TeachingTaskRequest request) { return TeachingWorkflowEvidencePolicy.topicKeywords(request); }
    // Delegates the pure policy/rendering rule to TeachingWorkflowEvidencePolicy; lifecycle state stays in the facade.
    static List<String> specificEvidenceTopicTerms(TeachingTaskRequest request) { return TeachingWorkflowEvidencePolicy.specificEvidenceTopicTerms(request); }
    // Delegates the pure policy/rendering rule to TeachingWorkflowEvidencePolicy; lifecycle state stays in the facade.
    static boolean matchesSpecificEvidenceTopic(TeachingEvidence evidence, List<String> terms) { return TeachingWorkflowEvidencePolicy.matchesSpecificEvidenceTopic(evidence, terms); }
    // Delegates the pure policy/rendering rule to TeachingWorkflowEvidencePolicy; lifecycle state stays in the facade.
    static int topicKeywordPriority(String keyword, String goalText, String questionText) { return TeachingWorkflowEvidencePolicy.topicKeywordPriority(keyword, goalText, questionText); }
    // Delegates the pure policy/rendering rule to TeachingWorkflowEvidencePolicy; lifecycle state stays in the facade.
    static String primaryTopicKeyword(TeachingTaskRequest request) { return TeachingWorkflowEvidencePolicy.primaryTopicKeyword(request); }

    static List<String> alignedQueries(TeachingTaskRequest request) {
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
    enum ProgressPhase {
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
    // Implementation moved to TeachingWorkflowExecutionSupport to keep the facade focused on lifecycle coordination.
    // Delegates the pure policy/rendering rule to TeachingWorkflowProgressModel; lifecycle state stays in the facade.
    static List<TeachingWorkflowNode> progressWorkflowNodes(TeachingTaskRequest request, StudentMemoryResponse memoryResponse, List<TeachingEvidence> evidence, List<TeachingEvidence> textbookEvidence, List<TeachingEvidence> questionEvidence, List<TeachingEvidence> teacherResourceEvidence, TeachingTaskResponse.AiDraft aiDraft, TeachingHandoutTemplateProfile template, boolean questionBankAllowed, boolean teacherResourceAllowed, ProgressPhase phase) { return TeachingWorkflowProgressModel.progressWorkflowNodes(request, memoryResponse, evidence, textbookEvidence, questionEvidence, teacherResourceEvidence, aiDraft, template, questionBankAllowed, teacherResourceAllowed, phase); }
    // Delegates the pure policy/rendering rule to TeachingWorkflowProgressModel; lifecycle state stays in the facade.
    static List<TeachingWorkflowEvent> progressWorkflowEvents(TeachingHandoutTemplateProfile template, List<TeachingEvidence> textbookEvidence, List<TeachingEvidence> questionEvidence, List<TeachingEvidence> teacherResourceEvidence, TeachingTaskResponse.AiDraft aiDraft, ProgressPhase phase) { return TeachingWorkflowProgressModel.progressWorkflowEvents(template, textbookEvidence, questionEvidence, teacherResourceEvidence, aiDraft, phase); }
    // Delegates the pure policy/rendering rule to TeachingWorkflowProgressModel; lifecycle state stays in the facade.
    static String evidenceWorkflowDetail(List<TeachingEvidence> textbookEvidence, List<TeachingEvidence> questionEvidence, List<TeachingEvidence> teacherResourceEvidence) { return TeachingWorkflowProgressModel.evidenceWorkflowDetail(textbookEvidence, questionEvidence, teacherResourceEvidence); }
    // Delegates the pure policy/rendering rule to TeachingWorkflowProgressModel; lifecycle state stays in the facade.
    static String evidenceDisplayName(TeachingEvidence evidence) { return TeachingWorkflowProgressModel.evidenceDisplayName(evidence); }
    // Delegates the pure policy/rendering rule to TeachingWorkflowProgressModel; lifecycle state stays in the facade.
    static List<TeachingWorkflowEvent> questionAgentEvents(List<TeachingEvidence> questionEvidence, String status) { return TeachingWorkflowProgressModel.questionAgentEvents(questionEvidence, status); }
    // Delegates the pure policy/rendering rule to TeachingWorkflowProgressModel; lifecycle state stays in the facade.
    static TeachingWorkflowEvent childWorkflowEvent(String eventId, String parentEventId, String sourceType, String sourceName, String eventType, String title, String summary, String status, List<String> artifactRefs) { return TeachingWorkflowProgressModel.childWorkflowEvent(eventId, parentEventId, sourceType, sourceName, eventType, title, summary, status, artifactRefs); }
    // Delegates the pure policy/rendering rule to TeachingWorkflowProgressModel; lifecycle state stays in the facade.
    static List<TeachingWorkflowNode> buildNodes(TeachingTaskRequest request, List<TeachingEvidence> evidence, List<TeachingEvidence> questionEvidence, List<TeachingEvidence> teacherResourceEvidence, StudentMemoryResponse memoryResponse, TeachingTaskResponse.AiDraft aiDraft, TeachingHandoutTemplateProfile template, boolean questionBankAllowed, boolean teacherResourceAllowed) { return TeachingWorkflowProgressModel.buildNodes(request, evidence, questionEvidence, teacherResourceEvidence, memoryResponse, aiDraft, template, questionBankAllowed, teacherResourceAllowed); }
    // Delegates the pure policy/rendering rule to TeachingWorkflowProgressModel; lifecycle state stays in the facade.
    static List<TeachingWorkflowNode> questionAgentNodes(List<TeachingEvidence> questionEvidence, boolean evidenceReady, boolean outlineReady) { return TeachingWorkflowProgressModel.questionAgentNodes(questionEvidence, evidenceReady, outlineReady); }
    // Delegates the pure policy/rendering rule to TeachingWorkflowProgressModel; lifecycle state stays in the facade.
    static String questionAgentId(TeachingEvidence evidence) { return TeachingWorkflowProgressModel.questionAgentId(evidence); }
    // Delegates the pure policy/rendering rule to TeachingWorkflowProgressModel; lifecycle state stays in the facade.
    static QuestionAgentBatch prepareQuestionAgentContexts(List<TeachingEvidence> questionEvidence) { return TeachingWorkflowProgressModel.prepareQuestionAgentContexts(questionEvidence); }
    // Delegates the pure policy/rendering rule to TeachingWorkflowProgressModel; lifecycle state stays in the facade.
    static String aiDraftSummary(TeachingTaskResponse.AiDraft aiDraft) { return TeachingWorkflowProgressModel.aiDraftSummary(aiDraft); }
    // Delegates the pure policy/rendering rule to TeachingWorkflowProgressModel; lifecycle state stays in the facade.
    static TeachingWorkflowNode node(String code, String name, String summary) { return TeachingWorkflowProgressModel.node(code, name, summary); }
    // Delegates the pure policy/rendering rule to TeachingWorkflowProgressModel; lifecycle state stays in the facade.
    static TeachingWorkflowNode node(String code, String name, String status, String summary) { return TeachingWorkflowProgressModel.node(code, name, status, summary); }
    // Delegates the pure policy/rendering rule to TeachingWorkflowProgressModel; lifecycle state stays in the facade.
    static List<TeachingWorkflowEvent> buildWorkflowEvents(List<TeachingWorkflowNode> nodes, List<TeachingEvidence> evidence, List<TeachingEvidence> textbookEvidence, List<TeachingEvidence> questionEvidence, List<TeachingEvidence> teacherResourceEvidence, TeachingTaskResponse.AiDraft aiDraft, TeachingHandoutTemplateProfile template) { return TeachingWorkflowProgressModel.buildWorkflowEvents(nodes, evidence, textbookEvidence, questionEvidence, teacherResourceEvidence, aiDraft, template); }
    // Delegates the pure policy/rendering rule to TeachingWorkflowProgressModel; lifecycle state stays in the facade.
    static TeachingWorkflowEvent workflowEvent(String eventId, String sourceType, String sourceName, String eventType, String title, String summary, List<String> artifactRefs) { return TeachingWorkflowProgressModel.workflowEvent(eventId, sourceType, sourceName, eventType, title, summary, artifactRefs); }
    // Delegates the pure policy/rendering rule to TeachingWorkflowProgressModel; lifecycle state stays in the facade.
    static TeachingWorkflowEvent workflowEvent(String eventId, String sourceType, String sourceName, String eventType, String title, String summary, String status, List<String> artifactRefs) { return TeachingWorkflowProgressModel.workflowEvent(eventId, sourceType, sourceName, eventType, title, summary, status, artifactRefs); }
    // Delegates the pure policy/rendering rule to TeachingWorkflowProgressModel; lifecycle state stays in the facade.
    static String nodeSummary(List<TeachingWorkflowNode> nodes, String code) { return TeachingWorkflowProgressModel.nodeSummary(nodes, code); }
    // Delegates the pure policy/rendering rule to TeachingWorkflowDraftRenderer; lifecycle state stays in the facade.
    static List<TeachingReactStep> buildReactTrace(TeachingTaskRequest request, List<TeachingEvidence> evidence, StudentMemoryResponse memoryResponse) { return TeachingWorkflowDraftRenderer.buildReactTrace(request, evidence, memoryResponse); }
    // Delegates the pure policy/rendering rule to TeachingWorkflowDraftRenderer; lifecycle state stays in the facade.
    static String buildTeacherHandoutLatex(TeachingTaskRequest request, List<TeachingEvidence> evidence, List<TeachingKnowledgePointPack> knowledgePointPacks, StudentMemoryResponse memoryResponse, TeachingHandoutTemplateProfile template, TeachingTaskResponse.AiDraft aiDraft, TeachingDraftSections draftSections) { return TeachingWorkflowDraftRenderer.buildTeacherHandoutLatex(request, evidence, knowledgePointPacks, memoryResponse, template, aiDraft, draftSections); }
    // Delegates the pure policy/rendering rule to TeachingWorkflowDraftRenderer; lifecycle state stays in the facade.
    static boolean isZhaoMasterTemplate(TeachingHandoutTemplateProfile template) { return TeachingWorkflowDraftRenderer.isZhaoMasterTemplate(template); }
    // Delegates the pure policy/rendering rule to TeachingWorkflowDraftRenderer; lifecycle state stays in the facade.
    static String learningGoalHeading(List<TeachingKnowledgePointPack> packs) { return TeachingWorkflowDraftRenderer.learningGoalHeading(packs); }
    // Delegates the pure policy/rendering rule to TeachingWorkflowDraftRenderer; lifecycle state stays in the facade.
    static String lessonOpeningHeading(List<TeachingKnowledgePointPack> packs) { return TeachingWorkflowDraftRenderer.lessonOpeningHeading(packs); }
    // Delegates the pure policy/rendering rule to TeachingWorkflowDraftRenderer; lifecycle state stays in the facade.
    static String topicSectionHeading(String title) { return TeachingWorkflowDraftRenderer.topicSectionHeading(title); }
    // Delegates the pure policy/rendering rule to TeachingWorkflowDraftRenderer; lifecycle state stays in the facade.
    static boolean isQuadraticFunctionTopic(TeachingTaskRequest request) { return TeachingWorkflowDraftRenderer.isQuadraticFunctionTopic(request); }
    // Delegates the pure policy/rendering rule to TeachingWorkflowDraftRenderer; lifecycle state stays in the facade.
    static boolean isQuadraticFunctionText(String text) { return TeachingWorkflowDraftRenderer.isQuadraticFunctionText(text); }
    // Delegates the pure policy/rendering rule to TeachingWorkflowDraftRenderer; lifecycle state stays in the facade.
    static String quadraticReferenceGraph() { return TeachingWorkflowDraftRenderer.quadraticReferenceGraph(); }
    // Delegates the pure policy/rendering rule to TeachingWorkflowDraftRenderer; lifecycle state stays in the facade.
    static List<TeachingKnowledgePointPack> buildKnowledgePointPacks(TeachingTaskRequest request, List<TeachingEvidence> textbookEvidence, List<TeachingEvidence> teacherResourceEvidence, List<TeachingEvidence> questionEvidence) { return TeachingWorkflowDraftRenderer.buildKnowledgePointPacks(request, textbookEvidence, teacherResourceEvidence, questionEvidence); }
    // Delegates the pure policy/rendering rule to TeachingWorkflowDraftRenderer; lifecycle state stays in the facade.
    static List<TeachingEvidence> requestTopicSupportingEvidence(TeachingTaskRequest request, List<TeachingEvidence> textbookEvidence, List<TeachingEvidence> teacherResourceEvidence) { return TeachingWorkflowDraftRenderer.requestTopicSupportingEvidence(request, textbookEvidence, teacherResourceEvidence); }
    // Delegates the pure policy/rendering rule to TeachingWorkflowDraftRenderer; lifecycle state stays in the facade.
    static List<TeachingKnowledgePointPack> fallbackKnowledgePointPacks(TeachingTaskRequest request, List<TeachingEvidence> evidence) { return TeachingWorkflowDraftRenderer.fallbackKnowledgePointPacks(request, evidence); }
    // Delegates the pure policy/rendering rule to TeachingWorkflowDraftRenderer; lifecycle state stays in the facade.
    static boolean evidenceMatchesAnyTopicTerm(TeachingEvidence evidence, List<String> topicTerms) { return TeachingWorkflowDraftRenderer.evidenceMatchesAnyTopicTerm(evidence, topicTerms); }
    // Delegates the pure policy/rendering rule to TeachingWorkflowDraftRenderer; lifecycle state stays in the facade.
    static int fallbackEvidencePriority(TeachingEvidence evidence) { return TeachingWorkflowDraftRenderer.fallbackEvidencePriority(evidence); }
    // Delegates the pure policy/rendering rule to TeachingWorkflowDraftRenderer; lifecycle state stays in the facade.
    static String knowledgePointTitleForQuestion(TeachingEvidence question, List<TeachingEvidence> textbookEvidence, List<TeachingEvidence> teacherResourceEvidence, TeachingTaskRequest request) { return TeachingWorkflowDraftRenderer.knowledgePointTitleForQuestion(question, textbookEvidence, teacherResourceEvidence, request); }
    // Delegates the pure policy/rendering rule to TeachingWorkflowDraftRenderer; lifecycle state stays in the facade.
    static boolean noSpecificRequestPoint(TeachingTaskRequest request) { return TeachingWorkflowDraftRenderer.noSpecificRequestPoint(request); }
    // Delegates the pure policy/rendering rule to TeachingWorkflowDraftRenderer; lifecycle state stays in the facade.
    static List<TeachingEvidence> supportingEvidenceForPoint(String point, List<TeachingEvidence> textbookEvidence, List<TeachingEvidence> teacherResourceEvidence) { return TeachingWorkflowDraftRenderer.supportingEvidenceForPoint(point, textbookEvidence, teacherResourceEvidence); }
    // Delegates the pure policy/rendering rule to TeachingWorkflowDraftRenderer; lifecycle state stays in the facade.
    static boolean supportsKnowledgePoint(TeachingEvidence evidence, String point) { return TeachingWorkflowDraftRenderer.supportsKnowledgePoint(evidence, point); }
    // Delegates the pure policy/rendering rule to TeachingWorkflowDraftRenderer; lifecycle state stays in the facade.
    static String pointTitleFromEvidence(TeachingEvidence item) { return TeachingWorkflowDraftRenderer.pointTitleFromEvidence(item); }
    // Delegates the pure policy/rendering rule to TeachingWorkflowDraftRenderer; lifecycle state stays in the facade.
    static int appendTeacherKnowledgePoint(StringBuilder builder, TeachingKnowledgePointPack pack, int questionNumber, String teachingNotes, String draftWorkedExample, String draftKnowledgePosition, String draftQuestionType, String draftMethodSteps, String draftAnswerPoints, String draftPitfalls, TeachingTaskResponse.AiDraft aiDraft, String userQuestion, boolean allowGlobalDraftForQuestion, boolean zhaoMaster) { return TeachingWorkflowDraftRenderer.appendTeacherKnowledgePoint(builder, pack, questionNumber, teachingNotes, draftWorkedExample, draftKnowledgePosition, draftQuestionType, draftMethodSteps, draftAnswerPoints, draftPitfalls, aiDraft, userQuestion, allowGlobalDraftForQuestion, zhaoMaster); }
    // Delegates the pure policy/rendering rule to TeachingWorkflowDraftRenderer; lifecycle state stays in the facade.
    static String modelDraftExcerptForQuestion(String teacherExplanation, String questionText) { return TeachingWorkflowDraftRenderer.modelDraftExcerptForQuestion(teacherExplanation, questionText); }
    // Delegates the pure policy/rendering rule to TeachingWorkflowDraftRenderer; lifecycle state stays in the facade.
    static boolean hasSubstantiveNumberedReasoning(String excerpt) { return TeachingWorkflowDraftRenderer.hasSubstantiveNumberedReasoning(excerpt); }
    // Delegates the pure policy/rendering rule to TeachingWorkflowDraftRenderer; lifecycle state stays in the facade.
    static List<ModelExplanationUnit> modelExplanationUnits(String teacherExplanation) { return TeachingWorkflowDraftRenderer.modelExplanationUnits(teacherExplanation); }
    // Delegates the pure policy/rendering rule to TeachingWorkflowDraftRenderer; lifecycle state stays in the facade.
    static int promptMatchCount(String sourceQuestion, String modelPrompt) { return TeachingWorkflowDraftRenderer.promptMatchCount(sourceQuestion, modelPrompt); }
    // Delegates the pure policy/rendering rule to TeachingWorkflowDraftRenderer; lifecycle state stays in the facade.
    static Set<String> promptMatchTerms(String value) { return TeachingWorkflowDraftRenderer.promptMatchTerms(value); }

    /** Immutable model unit keeps prompt matching and printable deduction together. */
    record ModelExplanationUnit(String number, String prompt, String excerpt) { }

    /** Header coordinates are collected before slicing so the matcher never skips every second worked example. */
    record ModelExplanationHeader(String number, String prompt, int start, int end) { }
    // Delegates the pure policy/rendering rule to TeachingWorkflowQuestionRenderer; lifecycle state stays in the facade.
    static String modelDraftConclusionForQuestion(String excerpt) { return TeachingWorkflowQuestionRenderer.modelDraftConclusionForQuestion(excerpt); }
    // Delegates the pure policy/rendering rule to TeachingWorkflowQuestionRenderer; lifecycle state stays in the facade.
    static String modelDraftAnswerForQuestion(String teacherExplanation, String questionText) { return TeachingWorkflowQuestionRenderer.modelDraftAnswerForQuestion(teacherExplanation, questionText); }
    // Delegates the pure policy/rendering rule to TeachingWorkflowQuestionRenderer; lifecycle state stays in the facade.
    static String methodHeading(String knowledgePoint, String draftMethodSteps) { return TeachingWorkflowQuestionRenderer.methodHeading(knowledgePoint, draftMethodSteps); }
    // Delegates the pure policy/rendering rule to TeachingWorkflowQuestionRenderer; lifecycle state stays in the facade.
    static String authorizedImageLatex(String path) { return TeachingWorkflowQuestionRenderer.authorizedImageLatex(path); }
    // Delegates the pure policy/rendering rule to TeachingWorkflowQuestionRenderer; lifecycle state stays in the facade.
    static String lectureAuthorizedImageLatex(String path) { return TeachingWorkflowQuestionRenderer.lectureAuthorizedImageLatex(path); }
    // Delegates the pure policy/rendering rule to TeachingWorkflowQuestionRenderer; lifecycle state stays in the facade.
    static int appendTeacherQuestion(StringBuilder builder, int questionNumber, String heading, TeachingEvidence question, String authorizedImagePath, String draftAnswerPoints, String draftMethodSteps, String fallbackHint) { return TeachingWorkflowQuestionRenderer.appendTeacherQuestion(builder, questionNumber, heading, question, authorizedImagePath, draftAnswerPoints, draftMethodSteps, fallbackHint); }
    // Delegates the pure policy/rendering rule to TeachingWorkflowQuestionRenderer; lifecycle state stays in the facade.
    static String analysisHeading(String questionText) { return TeachingWorkflowQuestionRenderer.analysisHeading(questionText); }
    // Delegates the pure policy/rendering rule to TeachingWorkflowQuestionRenderer; lifecycle state stays in the facade.
    static String questionSolutionHeading(String questionText) { return TeachingWorkflowQuestionRenderer.questionSolutionHeading(questionText); }
    // Delegates the pure policy/rendering rule to TeachingWorkflowQuestionRenderer; lifecycle state stays in the facade.
    static String questionAnalysisEntry(String questionText) { return TeachingWorkflowQuestionRenderer.questionAnalysisEntry(questionText); }
    // Delegates the pure policy/rendering rule to TeachingWorkflowQuestionRenderer; lifecycle state stays in the facade.
    static String teacherQuestionConclusion(String questionText, String sourceAnswer, String compactBankAnswer, String draftAnswerPoints) { return TeachingWorkflowQuestionRenderer.teacherQuestionConclusion(questionText, sourceAnswer, compactBankAnswer, draftAnswerPoints); }
    // Delegates the pure policy/rendering rule to TeachingWorkflowQuestionRenderer; lifecycle state stays in the facade.
    static boolean isVectorQuestion(String text) { return TeachingWorkflowQuestionRenderer.isVectorQuestion(text); }
    // Delegates the pure policy/rendering rule to TeachingWorkflowQuestionRenderer; lifecycle state stays in the facade.
    static boolean isLogOptimizationQuestion(String text) { return TeachingWorkflowQuestionRenderer.isLogOptimizationQuestion(text); }
    // Delegates the pure policy/rendering rule to TeachingWorkflowQuestionRenderer; lifecycle state stays in the facade.
    static boolean isUnreliableQuestionAnswer(String answer) { return TeachingWorkflowQuestionRenderer.isUnreliableQuestionAnswer(answer); }
    // Delegates the pure policy/rendering rule to TeachingWorkflowQuestionRenderer; lifecycle state stays in the facade.
    static String repairMojibake(String value) { return TeachingWorkflowQuestionRenderer.repairMojibake(value); }
    // Delegates the pure policy/rendering rule to TeachingWorkflowQuestionRenderer; lifecycle state stays in the facade.
    static long mojibakeScore(String value) { return TeachingWorkflowQuestionRenderer.mojibakeScore(value); }
    // Delegates the pure policy/rendering rule to TeachingWorkflowQuestionRenderer; lifecycle state stays in the facade.
    static String questionBankSteps(String formattedAnswer) { return TeachingWorkflowQuestionRenderer.questionBankSteps(formattedAnswer); }
    // Delegates the pure policy/rendering rule to TeachingWorkflowQuestionRenderer; lifecycle state stays in the facade.
    static String withoutBoardOrderLine(String steps) { return TeachingWorkflowQuestionRenderer.withoutBoardOrderLine(steps); }
    // Delegates the pure policy/rendering rule to TeachingWorkflowQuestionRenderer; lifecycle state stays in the facade.
    static String questionBankAnswerWithoutSteps(String formattedAnswer) { return TeachingWorkflowQuestionRenderer.questionBankAnswerWithoutSteps(formattedAnswer); }
    // Delegates the pure policy/rendering rule to TeachingWorkflowQuestionRenderer; lifecycle state stays in the facade.
    static String compactQuestionBankAnswer(String answer) { return TeachingWorkflowQuestionRenderer.compactQuestionBankAnswer(answer); }
    // Delegates the pure policy/rendering rule to TeachingWorkflowQuestionRenderer; lifecycle state stays in the facade.
    static String escapeLatexMath(String expression) { return TeachingWorkflowQuestionRenderer.escapeLatexMath(expression); }
    // Delegates the pure policy/rendering rule to TeachingWorkflowQuestionRenderer; lifecycle state stays in the facade.
    static boolean isUnusableQuestionText(String questionText) { return TeachingWorkflowQuestionRenderer.isUnusableQuestionText(questionText); }
    // Delegates the pure policy/rendering rule to TeachingWorkflowQuestionRenderer; lifecycle state stays in the facade.
    static String mergeTeacherDraftNotes(String teacherExplanation) { return TeachingWorkflowQuestionRenderer.mergeTeacherDraftNotes(teacherExplanation); }
    // Delegates the pure policy/rendering rule to TeachingWorkflowQuestionRenderer; lifecycle state stays in the facade.
    static String compactEvidenceFact(String value) { return TeachingWorkflowQuestionRenderer.compactEvidenceFact(value); }
    // Delegates the pure policy/rendering rule to TeachingWorkflowQuestionRenderer; lifecycle state stays in the facade.
    static String normalizedInlineText(String value) { return TeachingWorkflowQuestionRenderer.normalizedInlineText(value); }

    // Delegates the pure policy/rendering rule to TeachingWorkflowQuestionRenderer; lifecycle state stays in the facade.
    static boolean canUseQuestionBank(TeachingRequestContext context) { return TeachingWorkflowQuestionRenderer.canUseQuestionBank(context); }
    // Delegates the pure policy/rendering rule to TeachingWorkflowQuestionRenderer; lifecycle state stays in the facade.
    static boolean canUseTeacherResources(TeachingRequestContext context) { return TeachingWorkflowQuestionRenderer.canUseTeacherResources(context); }
    // Delegates the pure policy/rendering rule to TeachingWorkflowQuestionRenderer; lifecycle state stays in the facade.
    static String evidenceSummary(List<TeachingEvidence> evidence) { return TeachingWorkflowQuestionRenderer.evidenceSummary(evidence); }
    // Delegates the pure policy/rendering rule to TeachingWorkflowQuestionRenderer; lifecycle state stays in the facade.
    static String evidenceSourceLine(int index, TeachingEvidence item) { return TeachingWorkflowQuestionRenderer.evidenceSourceLine(index, item); }
    // Delegates the pure policy/rendering rule to TeachingWorkflowStudentRenderer; lifecycle state stays in the facade.
    static String evidenceLabel(TeachingEvidence item) { return TeachingWorkflowStudentRenderer.evidenceLabel(item); }
    // Delegates the pure policy/rendering rule to TeachingWorkflowStudentRenderer; lifecycle state stays in the facade.
    static String printableEvidenceTitle(String value) { return TeachingWorkflowStudentRenderer.printableEvidenceTitle(value); }
    // Delegates the pure policy/rendering rule to TeachingWorkflowStudentRenderer; lifecycle state stays in the facade.
    static String buildStudentHandoutLatex(TeachingTaskRequest request, List<TeachingEvidence> evidence, List<TeachingKnowledgePointPack> knowledgePointPacks, StudentMemoryResponse memoryResponse, TeachingHandoutTemplateProfile template, TeachingTaskResponse.AiDraft aiDraft, TeachingDraftSections draftSections) { return TeachingWorkflowStudentRenderer.buildStudentHandoutLatex(request, evidence, knowledgePointPacks, memoryResponse, template, aiDraft, draftSections); }
    // Delegates the pure policy/rendering rule to TeachingWorkflowStudentRenderer; lifecycle state stays in the facade.
    static String buildStudentKnowledgePointHandout(String lectureTitle, List<TeachingKnowledgePointPack> packs, int configuredWorkspaceEm, List<String> aiKnowledgeNotes, List<String> aiRecognitionSignals, List<String> aiPracticeTasks) { return TeachingWorkflowStudentRenderer.buildStudentKnowledgePointHandout(lectureTitle, packs, configuredWorkspaceEm, aiKnowledgeNotes, aiRecognitionSignals, aiPracticeTasks); }
    // Delegates the pure policy/rendering rule to TeachingWorkflowStudentRenderer; lifecycle state stays in the facade.
    static String firstAuthorizedImageForQuestion(String questionText, List<TeachingEvidence> supportingEvidence) { return TeachingWorkflowStudentRenderer.firstAuthorizedImageForQuestion(questionText, supportingEvidence); }
    // Delegates the pure policy/rendering rule to TeachingWorkflowStudentRenderer; lifecycle state stays in the facade.
    static String firstExistingAuthorizedImagePath(TeachingEvidence evidence) { return TeachingWorkflowStudentRenderer.firstExistingAuthorizedImagePath(evidence); }
    // Delegates the pure policy/rendering rule to TeachingWorkflowStudentRenderer; lifecycle state stays in the facade.
    static boolean requiresAuthorizedFigure(String questionText) { return TeachingWorkflowStudentRenderer.requiresAuthorizedFigure(questionText); }
    // Delegates the pure policy/rendering rule to TeachingWorkflowStudentRenderer; lifecycle state stays in the facade.
    static String evidenceHeading(String knowledgePoint) { return TeachingWorkflowStudentRenderer.evidenceHeading(knowledgePoint); }
    // Delegates the pure policy/rendering rule to TeachingWorkflowStudentRenderer; lifecycle state stays in the facade.
    static String writingHeading(String knowledgePoint) { return TeachingWorkflowStudentRenderer.writingHeading(knowledgePoint); }
    // Delegates the pure policy/rendering rule to TeachingWorkflowStudentRenderer; lifecycle state stays in the facade.
    static void appendStudentQuestion(StringBuilder builder, String heading, TeachingEvidence question, int workspace, String authorizedImagePath, List<String> selfCheckTasks, boolean startOnNewPage) { TeachingWorkflowStudentRenderer.appendStudentQuestion(builder, heading, question, workspace, authorizedImagePath, selfCheckTasks, startOnNewPage); }
    // Delegates the pure policy/rendering rule to TeachingWorkflowStudentRenderer; lifecycle state stays in the facade.
    static String studentQuestionHint(String questionText) { return TeachingWorkflowStudentRenderer.studentQuestionHint(questionText); }
    // Delegates the pure policy/rendering rule to TeachingWorkflowStudentRenderer; lifecycle state stays in the facade.
    static List<String> teacherMethodCards(TeachingTaskRequest request, List<TeachingEvidence> evidence, TeachingHandoutTemplateProfile template) { return TeachingWorkflowStudentRenderer.teacherMethodCards(request, evidence, template); }
    // Delegates the pure policy/rendering rule to TeachingWorkflowStudentRenderer; lifecycle state stays in the facade.
    static List<String> studentMethodCards(TeachingTaskRequest request, List<TeachingEvidence> evidence) { return TeachingWorkflowStudentRenderer.studentMethodCards(request, evidence); }
    // Delegates the pure policy/rendering rule to TeachingWorkflowStudentRenderer; lifecycle state stays in the facade.
    static List<String> teacherBoardPlan(TeachingTaskRequest request, List<TeachingEvidence> evidence, TeachingHandoutTemplateProfile template) { return TeachingWorkflowStudentRenderer.teacherBoardPlan(request, evidence, template); }
    // Delegates the pure policy/rendering rule to TeachingWorkflowStudentRenderer; lifecycle state stays in the facade.
    static List<String> teacherChecklist(TeachingTaskRequest request, List<TeachingEvidence> evidence, TeachingHandoutTemplateProfile template) { return TeachingWorkflowStudentRenderer.teacherChecklist(request, evidence, template); }
    // Delegates the pure policy/rendering rule to TeachingWorkflowStudentRenderer; lifecycle state stays in the facade.
    static String studentKnowledgeSection(TeachingTaskRequest request, List<TeachingEvidence> evidence, String fallbackHint) { return TeachingWorkflowStudentRenderer.studentKnowledgeSection(request, evidence, fallbackHint); }
    // Delegates the pure policy/rendering rule to TeachingWorkflowStudentRenderer; lifecycle state stays in the facade.
    static List<String> studentKnowledgeCards(TeachingTaskRequest request, List<TeachingEvidence> evidence, String fallbackHint) { return TeachingWorkflowStudentRenderer.studentKnowledgeCards(request, evidence, fallbackHint); }
    // Delegates the pure policy/rendering rule to TeachingWorkflowStudentRenderer; lifecycle state stays in the facade.
    static String studentKnowledgeCardsLatex(List<String> cards) { return TeachingWorkflowStudentRenderer.studentKnowledgeCardsLatex(cards); }
    // Delegates the pure policy/rendering rule to TeachingWorkflowStudentRenderer; lifecycle state stays in the facade.
    static List<String> studentNoticeCards(TeachingTaskRequest request, List<TeachingEvidence> evidence) { return TeachingWorkflowStudentRenderer.studentNoticeCards(request, evidence); }
    // Delegates the pure policy/rendering rule to TeachingWorkflowStudentRenderer; lifecycle state stays in the facade.
    static List<String> studentPracticeTasks(TeachingTaskRequest request, List<TeachingEvidence> evidence, TeachingTaskResponse.AiDraft aiDraft, String draftPractice) { return TeachingWorkflowStudentRenderer.studentPracticeTasks(request, evidence, aiDraft, draftPractice); }
    // Delegates the pure policy/rendering rule to TeachingWorkflowStudentRenderer; lifecycle state stays in the facade.
    static List<String> defaultStudentExercises(TeachingTaskRequest request) { return TeachingWorkflowStudentRenderer.defaultStudentExercises(request); }
    // Delegates the pure policy/rendering rule to TeachingWorkflowStudentRenderer; lifecycle state stays in the facade.
    static List<String> teacherWideSlides(String questionSection, String questionType, String methodSteps, String answerPoints, String pitfalls, String followUps) { return TeachingWorkflowStudentRenderer.teacherWideSlides(questionSection, questionType, methodSteps, answerPoints, pitfalls, followUps); }
    // Delegates the pure policy/rendering rule to TeachingWorkflowStudentRenderer; lifecycle state stays in the facade.
    static String compactLectureCard(String value) { return TeachingWorkflowStudentRenderer.compactLectureCard(value); }
    // Delegates the pure policy/rendering rule to TeachingWorkflowStudentRenderer; lifecycle state stays in the facade.
    static String studentExampleSection(TeachingTaskRequest request, List<TeachingEvidence> evidence, String fallbackHint) { return TeachingWorkflowStudentRenderer.studentExampleSection(request, evidence, fallbackHint); }
    // Delegates the pure policy/rendering rule to TeachingWorkflowStudentRenderer; lifecycle state stays in the facade.
    static String studentLectureTitle(TeachingTaskRequest request) { return TeachingWorkflowStudentRenderer.studentLectureTitle(request); }
    // Delegates the pure policy/rendering rule to TeachingWorkflowStudentRenderer; lifecycle state stays in the facade.
    static String studentSafeQuestionText(TeachingTaskRequest request) { return TeachingWorkflowStudentRenderer.studentSafeQuestionText(request); }
    // Delegates the pure policy/rendering rule to TeachingWorkflowStudentRenderer; lifecycle state stays in the facade.
    static String sanitizeStudentWorkflowText(String value) { return TeachingWorkflowStudentRenderer.sanitizeStudentWorkflowText(value); }
    // Delegates the pure policy/rendering rule to TeachingWorkflowStudentRenderer; lifecycle state stays in the facade.
    static String teacherQuestionBankSection(List<TeachingEvidence> evidence) { return TeachingWorkflowStudentRenderer.teacherQuestionBankSection(evidence); }
    // Delegates the pure policy/rendering rule to TeachingWorkflowStudentRenderer; lifecycle state stays in the facade.
    static String studentQuestionBankSection(TeachingTaskRequest request, List<TeachingEvidence> evidence, int blankSpaceEm) { return TeachingWorkflowStudentRenderer.studentQuestionBankSection(request, evidence, blankSpaceEm); }
    // Delegates the pure policy/rendering rule to TeachingWorkflowStudentRenderer; lifecycle state stays in the facade.
    static String studentQuestionPages(String sectionTitle, List<String> items, int configuredWorkspaceEm) { return TeachingWorkflowStudentRenderer.studentQuestionPages(sectionTitle, items, configuredWorkspaceEm); }
    // Delegates the pure policy/rendering rule to TeachingWorkflowStudentRenderer; lifecycle state stays in the facade.
    static List<TeachingEvidence> questionBankEvidence(List<TeachingEvidence> evidence) { return TeachingWorkflowStudentRenderer.questionBankEvidence(evidence); }
    // Delegates the pure policy/rendering rule to TeachingWorkflowStudentRenderer; lifecycle state stays in the facade.
    static int questionDifficultyRank(TeachingEvidence item) { return TeachingWorkflowStudentRenderer.questionDifficultyRank(item); }
    // Delegates the pure policy/rendering rule to TeachingWorkflowStudentRenderer; lifecycle state stays in the facade.
    static String questionDifficulty(TeachingEvidence item) { return TeachingWorkflowStudentRenderer.questionDifficulty(item); }
    // Delegates the pure policy/rendering rule to TeachingWorkflowStudentRenderer; lifecycle state stays in the facade.
    static String questionTitleWithoutDifficulty(TeachingEvidence item) { return TeachingWorkflowStudentRenderer.questionTitleWithoutDifficulty(item); }
    // Delegates the pure policy/rendering rule to TeachingWorkflowStudentRenderer; lifecycle state stays in the facade.
    static String questionTextOnly(String snippet) { return TeachingWorkflowStudentRenderer.questionTextOnly(snippet); }
    // Delegates the pure policy/rendering rule to TeachingWorkflowStudentRenderer; lifecycle state stays in the facade.
    static String questionAnswerOnly(String snippet) { return TeachingWorkflowStudentRenderer.questionAnswerOnly(snippet); }
    // Delegates the pure policy/rendering rule to TeachingWorkflowStudentRenderer; lifecycle state stays in the facade.
    static String teacherKnowledgePoint(TeachingTaskRequest request, List<TeachingEvidence> evidence) { return TeachingWorkflowStudentRenderer.teacherKnowledgePoint(request, evidence); }
    // Delegates the pure policy/rendering rule to TeachingWorkflowStudentRenderer; lifecycle state stays in the facade.
    static String escapeLatex(String value) { return TeachingWorkflowStudentRenderer.escapeLatex(value); }
    // Delegates the pure policy/rendering rule to TeachingWorkflowStudentRenderer; lifecycle state stays in the facade.
    static String escapeLatexTextWithBlanks(String value) { return TeachingWorkflowStudentRenderer.escapeLatexTextWithBlanks(value); }
    // Delegates the pure policy/rendering rule to TeachingWorkflowStudentRenderer; lifecycle state stays in the facade.
    static String escapeLatexText(String value) { return TeachingWorkflowStudentRenderer.escapeLatexText(value); }
    // Delegates the pure policy/rendering rule to TeachingWorkflowStudentRenderer; lifecycle state stays in the facade.
    static String sanitizeMathSegment(String value) { return TeachingWorkflowStudentRenderer.sanitizeMathSegment(value); }
    // Delegates the pure policy/rendering rule to TeachingWorkflowStudentRenderer; lifecycle state stays in the facade.
    static String safeQuestionText(TeachingTaskRequest request) { return TeachingWorkflowStudentRenderer.safeQuestionText(request); }
    // Delegates the pure policy/rendering rule to TeachingWorkflowStudentRenderer; lifecycle state stays in the facade.
    static String safeTaskText(String value) { return TeachingWorkflowStudentRenderer.safeTaskText(value); }
    // Delegates the pure policy/rendering rule to TeachingWorkflowStudentRenderer; lifecycle state stays in the facade.
    static List<String> teacherDraftLabels() { return TeachingWorkflowStudentRenderer.teacherDraftLabels(); }
    // Delegates the pure policy/rendering rule to TeachingWorkflowStudentRenderer; lifecycle state stays in the facade.
    static List<String> studentDraftLabels() { return TeachingWorkflowStudentRenderer.studentDraftLabels(); }
    // Delegates the pure policy/rendering rule to TeachingWorkflowStudentRenderer; lifecycle state stays in the facade.
    static String labeledDraftSections(String text, List<String> labels, String fallbackTitle) { return TeachingWorkflowStudentRenderer.labeledDraftSections(text, labels, fallbackTitle); }
    // Delegates the pure policy/rendering rule to TeachingWorkflowStudentRenderer; lifecycle state stays in the facade.
    static String draftBlockContent(String text, List<String> labels, String targetLabel) { return TeachingWorkflowStudentRenderer.draftBlockContent(text, labels, targetLabel); }
    // Delegates the pure policy/rendering rule to TeachingWorkflowStudentRenderer; lifecycle state stays in the facade.
    static List<String> draftBlockLines(String content) { return TeachingWorkflowStudentRenderer.draftBlockLines(content); }
    // Delegates the pure policy/rendering rule to TeachingWorkflowStudentRenderer; lifecycle state stays in the facade.
    static List<String> mergeDistinctItems(int limit, List<String>... groups) { return TeachingWorkflowStudentRenderer.mergeDistinctItems(limit, groups); }
    // Delegates the pure policy/rendering rule to TeachingWorkflowStudentRenderer; lifecycle state stays in the facade.
    static String flattenDraftBlock(String content) { return TeachingWorkflowStudentRenderer.flattenDraftBlock(content); }
    // Delegates the pure policy/rendering rule to TeachingWorkflowStudentRenderer; lifecycle state stays in the facade.
    static String contentOrFallback(String content, String fallbackLatex) { return TeachingWorkflowStudentRenderer.contentOrFallback(content, fallbackLatex); }
    // Delegates the pure policy/rendering rule to TeachingWorkflowStudentRenderer; lifecycle state stays in the facade.
    static String latexEnumerateWithWorkspace(List<String> items, int workspaceEm) { return TeachingWorkflowStudentRenderer.latexEnumerateWithWorkspace(items, workspaceEm); }
    // Delegates the pure policy/rendering rule to TeachingWorkflowStudentRenderer; lifecycle state stays in the facade.
    static int boundedEm(int value, int min, int max, int fallback) { return TeachingWorkflowStudentRenderer.boundedEm(value, min, max, fallback); }
    // Delegates the pure policy/rendering rule to TeachingWorkflowStudentRenderer; lifecycle state stays in the facade.
    static String formatDraftContentAsLatex(String content) { return TeachingWorkflowStudentRenderer.formatDraftContentAsLatex(content); }
    // Delegates the pure policy/rendering rule to TeachingWorkflowStudentRenderer; lifecycle state stays in the facade.
    static void flushDraftList(StringBuilder builder, List<String> items, boolean ordered) { TeachingWorkflowStudentRenderer.flushDraftList(builder, items, ordered); }
    // Delegates the pure policy/rendering rule to TeachingWorkflowStudentRenderer; lifecycle state stays in the facade.
    static List<LabeledDraftBlock> parseLabeledDraftBlocks(String text, List<String> labels, String fallbackTitle) { return TeachingWorkflowStudentRenderer.parseLabeledDraftBlocks(text, labels, fallbackTitle); }
    // Delegates the pure policy/rendering rule to TeachingWorkflowStudentRenderer; lifecycle state stays in the facade.
    static String latexItemize(List<String> items) { return TeachingWorkflowStudentRenderer.latexItemize(items); }
    // Delegates the pure policy/rendering rule to TeachingWorkflowStudentRenderer; lifecycle state stays in the facade.
    static String latexEnumerate(List<String> items) { return TeachingWorkflowStudentRenderer.latexEnumerate(items); }
    // Delegates the pure policy/rendering rule to TeachingWorkflowStudentRenderer; lifecycle state stays in the facade.
    static String difficultyBands(TeachingHandoutTemplateProfile template) { return TeachingWorkflowStudentRenderer.difficultyBands(template); }
    // Delegates the pure policy/rendering rule to TeachingWorkflowStudentRenderer; lifecycle state stays in the facade.
    static TeachingDraftSections collectDraftSections(TeachingTaskRequest request, List<TeachingEvidence> evidence, TeachingTaskResponse.AiDraft aiDraft) { return TeachingWorkflowStudentRenderer.collectDraftSections(request, evidence, aiDraft); }

    static final class StageTimer {

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

    record LabelPosition(String label, int start, int end) {
    }

    record LabeledDraftBlock(String label, String content) {
    }

    record EvidencePack(
            List<TeachingEvidence> textbookEvidence,
            List<TeachingEvidence> questionEvidence,
            List<TeachingEvidence> teacherResourceEvidence,
            long textbookElapsedMs,
            long questionElapsedMs,
            long teacherResourceElapsedMs) {

        List<TeachingEvidence> mergedEvidence() {
            return concatEvidence(textbookEvidence, questionEvidence, teacherResourceEvidence);
        }
    }

    /** One real retrieval result paired with its own wall-clock duration before the three-way join. */
    record TimedEvidence(List<TeachingEvidence> evidence, long elapsedMs) {
    }

    /** Immutable context owned by exactly one question-agent branch. */
    record QuestionAgentContext(String agentId, String title, List<TeachingEvidence> evidence) {
    }

    /** One branch result keeps its own elapsed time so the join does not hide slow or failed questions. */
    record QuestionAgentBranch(QuestionAgentContext context, long elapsedMs) {
    }

    /** Stable per-question timing projection persisted inside the task response. */
    record QuestionAgentTiming(String agentId, long elapsedMs) {
    }

    /** Result of the question fan-out barrier, persisted as a stage timing. */
    record QuestionAgentBatch(int agentCount, long elapsedMs, List<QuestionAgentTiming> branchTimings) {
    }
}
