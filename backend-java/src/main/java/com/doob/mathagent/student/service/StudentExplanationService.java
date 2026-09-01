package com.doob.mathagent.student.service;

import com.doob.mathagent.infrastructure.security.RequestSubject;
import com.doob.mathagent.knowledge.service.KnowledgeGraphSpineService;
import com.doob.mathagent.knowledge.vo.KnowledgeGraphSpineResponse;
import com.doob.mathagent.resources.TextbookResourceProperties;
import com.doob.mathagent.retrieval.RetrievalRequestContext;
import com.doob.mathagent.retrieval.TextbookRetrievalService;
import com.doob.mathagent.retrieval.TextbookSearchHit;
import com.doob.mathagent.retrieval.TextbookSearchRequest;
import com.doob.mathagent.retrieval.TextbookSearchResponse;
import com.doob.mathagent.student.dto.StudentExplanationRequest;
import com.doob.mathagent.student.vo.StudentExplanationResponse;
import com.doob.mathagent.student.vo.StudentExplanationStreamProgress;
import com.doob.mathagent.teacher.service.TeacherResourceBlockSearchService;
import com.doob.mathagent.teacher.document.TeacherResourceStore;
import com.doob.mathagent.teacher.search.TeacherResourceBlockSearchResponse;
import com.doob.mathagent.teacher.document.TeacherResourceDocumentResponse;
import com.doob.mathagent.vector.service.VectorIndexService;
import com.doob.mathagent.agent.service.PythonMigratedWorkloadClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 学生端讲题编排服务：从题目输入到基于证据的讲解卡片，负责串联完整流程。
 *
 * <p>核心设计：</p>
 * <ol>
 *   <li>只推送真实的后端处理进度，不使用前端模拟计时器；</li>
 *   <li>只推送模型真实生成的内容，不预置固定讲解模板；</li>
 *   <li>后端负责身份与证据边界，模型只负责选择讲解结构。</li>
 * </ol>
 */
@Service
public class StudentExplanationService {

    private static final String ENDPOINT = "/api/students/explanations";
    /** The curated graph is a compact concept hint, not a long evidence list. */
    private static final int MAX_KNOWLEDGE_GRAPH_MATCHES = 5;
    /** A short follow-up window is cheaper and clearer when it fits without model-side preparation. */
    private static final int RAW_RECENT_CONTEXT_MAX_RECORDS = 4;
    /** Conservative character estimate; CJK-heavy conversation text consumes more tokens than ASCII prose. */
    private static final int RAW_RECENT_CONTEXT_MAX_ESTIMATED_TOKENS = 512;
    /** Cosine scores below this boundary are too weak to replace an explicit graph label match. */
    private static final double DEFAULT_KNOWLEDGE_GRAPH_SEMANTIC_MIN_SCORE = 0.45d;
    // 此版本号用于区分新版 Agent 结果与历史模板结果，无需重写既有会话记录。
    private static final String GENERATED_BY = "student_explanation_react_agent_v1";
    private static final Logger log = LoggerFactory.getLogger(StudentExplanationService.class);
    private static final ObjectMapper HISTORY_OBJECT_MAPPER = new ObjectMapper();

    private final TextbookResourceProperties textbookResourceProperties;
    private final TextbookRetrievalService textbookRetrievalService;
    private final KnowledgeGraphSpineService knowledgeGraphSpineService;
    private final TeacherResourceBlockSearchService teacherResourceBlockSearchService;
    private final TeacherResourceStore teacherResourceStore;
    private final StudentExplanationAiCardService aiCardService;
    private final StudentExplanationImageStoreService imageStoreService;
    private final StudentExplanationHistoryStore historyStore;
    private final StudentExplanationConversationContextCache conversationContextCache;
    private final VectorIndexService vectorIndexService;
    private final double knowledgeGraphSemanticMinScore;
    private final int conversationHistoryFetchLimit;
    private final int contextMaxInputTokens;
    private final int contextReservedOutputTokens;
    private final int contextSummaryTriggerTokens;
    private final Executor retrievalExecutor;

    public StudentExplanationService(
            TextbookResourceProperties textbookResourceProperties,
            TextbookRetrievalService textbookRetrievalService,
            KnowledgeGraphSpineService knowledgeGraphSpineService,
            TeacherResourceBlockSearchService teacherResourceBlockSearchService,
            TeacherResourceStore teacherResourceStore,
            StudentExplanationAiCardService aiCardService,
            StudentExplanationImageStoreService imageStoreService,
            StudentExplanationHistoryStore historyStore) {
        this(textbookResourceProperties, textbookRetrievalService, knowledgeGraphSpineService,
                teacherResourceBlockSearchService, teacherResourceStore, aiCardService, imageStoreService,
                historyStore, new NoOpStudentExplanationConversationContextCache(), null, null, Runnable::run,
                DEFAULT_KNOWLEDGE_GRAPH_SEMANTIC_MIN_SCORE, 200, 8_000, 1_500, 4_000);
    }

    /** Compatibility constructor for focused semantic-retrieval tests and controlled adapters. */
    public StudentExplanationService(
            TextbookResourceProperties textbookResourceProperties,
            TextbookRetrievalService textbookRetrievalService,
            KnowledgeGraphSpineService knowledgeGraphSpineService,
            TeacherResourceBlockSearchService teacherResourceBlockSearchService,
            TeacherResourceStore teacherResourceStore,
            StudentExplanationAiCardService aiCardService,
            StudentExplanationImageStoreService imageStoreService,
            StudentExplanationHistoryStore historyStore,
            VectorIndexService vectorIndexService) {
        this(textbookResourceProperties, textbookRetrievalService, knowledgeGraphSpineService,
                teacherResourceBlockSearchService, teacherResourceStore, aiCardService, imageStoreService,
                historyStore, new NoOpStudentExplanationConversationContextCache(), null, vectorIndexService,
                Runnable::run, DEFAULT_KNOWLEDGE_GRAPH_SEMANTIC_MIN_SCORE, 200, 8_000, 1_500, 4_000);
    }

    @Autowired
    public StudentExplanationService(
            TextbookResourceProperties textbookResourceProperties,
            TextbookRetrievalService textbookRetrievalService,
            KnowledgeGraphSpineService knowledgeGraphSpineService,
            TeacherResourceBlockSearchService teacherResourceBlockSearchService,
            TeacherResourceStore teacherResourceStore,
            StudentExplanationAiCardService aiCardService,
            StudentExplanationImageStoreService imageStoreService,
            StudentExplanationHistoryStore historyStore,
            StudentExplanationConversationContextCache conversationContextCache,
            StudentMemoryRagService studentMemoryRagService,
            VectorIndexService vectorIndexService,
            @Qualifier("studentExplanationTaskExecutor") TaskExecutor retrievalExecutor,
            @Value("${math-agent.student.explanation.knowledge-graph-semantic-min-score:0.45}")
                    double knowledgeGraphSemanticMinScore,
            @Value("${math-agent.student.explanation.conversation-history-fetch-limit:200}") int conversationHistoryFetchLimit,
            @Value("${math-agent.student.explanation.context-max-input-tokens:8000}") int contextMaxInputTokens,
            @Value("${math-agent.student.explanation.context-reserved-output-tokens:1500}") int contextReservedOutputTokens,
            @Value("${math-agent.student.explanation.context-summary-trigger-tokens:4000}") int contextSummaryTriggerTokens) {
        this.textbookResourceProperties = Objects.requireNonNull(
                textbookResourceProperties, "textbookResourceProperties is required");
        this.textbookRetrievalService = Objects.requireNonNull(
                textbookRetrievalService, "textbookRetrievalService is required");
        this.knowledgeGraphSpineService = Objects.requireNonNull(
                knowledgeGraphSpineService, "knowledgeGraphSpineService is required");
        this.teacherResourceBlockSearchService = Objects.requireNonNull(
                teacherResourceBlockSearchService, "teacherResourceBlockSearchService is required");
        this.teacherResourceStore = Objects.requireNonNull(
                teacherResourceStore, "teacherResourceStore is required");
        this.aiCardService = Objects.requireNonNull(aiCardService, "aiCardService is required");
        this.imageStoreService = Objects.requireNonNull(imageStoreService, "imageStoreService is required");
        this.historyStore = Objects.requireNonNull(historyStore, "historyStore is required");
        this.conversationContextCache = Objects.requireNonNull(
                conversationContextCache, "conversationContextCache is required");
        this.vectorIndexService = vectorIndexService;
        this.retrievalExecutor = Objects.requireNonNull(retrievalExecutor, "retrievalExecutor is required");
        this.knowledgeGraphSemanticMinScore = knowledgeGraphSemanticMinScore > 0.0d && knowledgeGraphSemanticMinScore <= 1.0d
                ? knowledgeGraphSemanticMinScore
                : DEFAULT_KNOWLEDGE_GRAPH_SEMANTIC_MIN_SCORE;
        this.conversationHistoryFetchLimit = Math.max(1, Math.min(conversationHistoryFetchLimit, 200));
        this.contextMaxInputTokens = Math.max(512, Math.min(contextMaxInputTokens, 120_000));
        this.contextReservedOutputTokens = Math.max(128, Math.min(contextReservedOutputTokens,
                this.contextMaxInputTokens - 1));
        this.contextSummaryTriggerTokens = Math.max(256, Math.min(contextSummaryTriggerTokens, 100_000));
    }

    /**
     * Runs one explanation request without streaming.
     */
    public StudentExplanationResponse explain(StudentExplanationRequest request, RequestSubject subject) {
        return explain(request, subject, StudentExplanationProgressListener.NOOP);
    }

    /**
     * Runs one explanation request while emitting real progress snapshots for the streaming endpoint.
     */
    public StudentExplanationResponse explain(
            StudentExplanationRequest request,
            RequestSubject subject,
            StudentExplanationProgressListener progressListener) {
        long startedNanos = System.nanoTime();
        StudentExplanationProgressListener listener =
                progressListener == null ? StudentExplanationProgressListener.NOOP : progressListener;
        // A caller-supplied conversation id is the authoritative context boundary.  The legacy
        // useConversationMemory flag is intentionally ignored for an existing conversation so a follow-up
        // cannot accidentally lose the original problem when an older client omits that flag.
        boolean existingConversation = request != null && !text(request.conversationId()).isBlank();
        StudentExplanationRequest normalizedRequest = request == null
                ? new StudentExplanationRequest(null, null, null, null, null, null, null, null, null, null, null, false).normalize()
                : request.normalize();
        if (text(normalizedRequest.clientRequestId()).isBlank()) {
            normalizedRequest = normalizedRequest.withClientRequestId(UUID.randomUUID().toString());
        }
        if (!normalizedRequest.hasProblemInput()) {
            throw new IllegalArgumentException("Student explanation requires questionText or image metadata");
        }
        normalizedRequest = normalizedRequest.withConversationId(conversationId(normalizedRequest));
        RequestSubject normalizedSubject = requireSubject(subject).normalize();
        StudentExplanationImageRecord imageRecord = resolveImageRecord(normalizedRequest, normalizedSubject);

        List<StudentExplanationResponse.WorkflowStage> stages = new ArrayList<>();
        List<StudentExplanationResponse.ExplanationCard> cards = new ArrayList<>();
        List<StudentExplanationResponse.ExplanationSource> sources = new ArrayList<>();
        StudentExplanationResponse.ImageUnderstanding imageUnderstanding = StudentExplanationResponse.ImageUnderstanding.none();
        StudentExplanationResponse.AiDraft aiDraft = StudentExplanationResponse.AiDraft.disabled("尚未开始生成讲解。");
        String conversationTitle = StudentExplanationConversationTitleSupport.resolve("", normalizedRequest.questionText(), null);
        String visibleQuestion = text(normalizedRequest.questionText());

        if (imageRecord != null) {
            // Upload completion is enough: the original bytes join the first model request and create no OCR stage.
            emitProgress(listener, normalizedRequest, visibleQuestion, stages, cards, sources, imageRecord,
                    imageUnderstanding, aiDraft, conversationTitle, startedNanos, "题图已加入模型上下文。");
        }

        // The original upload is the only visual context. Image-only requests let the same multimodal ReAct turn
        // understand the problem and decide its retrieval keywords, so there is no separate visual-provider call.
        StudentExplanationModelImageService.PreparedImage preparedImage =
                StudentExplanationModelImageService.prepare(imageRecord);
        if (preparedImage.available()) {
            imageUnderstanding = StudentExplanationResponse.ImageUnderstanding.directContext(
                    preparedImage.originalWidth(), preparedImage.originalHeight(), preparedImage.sentWidth(),
                    preparedImage.sentHeight(), preparedImage.originalBytes(), preparedImage.sentBytes(),
                    preparedImage.estimatedImageTokens());
        }
        String imageDataUrl = preparedImage.dataUrl();
        String query = text(normalizedRequest.questionText());
        if (query.isBlank() && imageRecord != null) {
            query = "请识别上传图片中的数学内容，并生成适合资料检索的具体关键词";
        }
        visibleQuestion = visibleQuestion(normalizedRequest, imageRecord);

        List<StudentExplanationHistorySummary> recentHistory = List.of();
        String packedConversationContext = "";
        if (existingConversation) {
            // Only an existing conversation loads history. A newly-created id is not loaded on its first turn.
            upsertStage(stages, runningStage("load_conversation_context", "读取对话上下文"));
            emitProgress(listener, normalizedRequest, visibleQuestion, stages, cards, sources, imageRecord,
                    imageUnderstanding, aiDraft, conversationTitle, startedNanos, "正在读取最近对话。" );
            ConversationContextState context = loadConversationContext(normalizedRequest, normalizedSubject, stages);
            recentHistory = context.history();
            packedConversationContext = prepareConversationContext(
                    normalizedRequest, normalizedSubject, visibleQuestion, context, stages);
            emitProgress(listener, normalizedRequest, visibleQuestion, stages, cards, sources, imageRecord,
                    imageUnderstanding, aiDraft, conversationTitle, startedNanos, "最近对话上下文已准备完成。" );
        }

        // Student explanations have one stable orchestration contract: the model decides whether a permitted
        // retrieval tool is useful. Deployment configuration cannot replace it with a different fixed pipeline.
        ReactEvidence reactEvidence = executeReactTools(
                normalizedRequest, normalizedSubject, query, stages, sources, listener, visibleQuestion, cards,
                imageRecord, imageDataUrl, imageUnderstanding, aiDraft, conversationTitle, startedNanos,
                packedConversationContext);
        List<KnowledgeGraphSpineResponse.Node> knowledgeNodes = reactEvidence.knowledgeNodes();
        List<TeacherResourceBlockSearchResponse.Hit> teacherHits = reactEvidence.teacherHits();

        StudentExplanationAiCardService.AiCardDraft aiCardDraft = reactEvidence.finalDraft();
        if (aiCardDraft == null) {
            // A retrieval plan needs one post-observation composition call. A self-contained question already carries
            // its validated cards in the first ReAct turn and must not pay for a redundant second provider request.
            upsertStage(stages, runningStage("ai_compose_cards", "生成讲解"));
            emitProgress(listener, normalizedRequest, visibleQuestion, stages, cards, sources, imageRecord,
                    imageUnderstanding, aiDraft, conversationTitle, startedNanos, "模型正在根据检索结果生成讲解。");
            long composeStarted = System.nanoTime();
            try {
                aiCardDraft = aiCardService.generate(
                        normalizedRequest,
                        aiContextQuery(query, knowledgeNodes, teacherHits),
                        packedConversationContext,
                        imageStatus(imageRecord),
                        sources,
                        recentHistory,
                        List.of(),
                        stages,
                        imageDataUrl,
                        listener::onAiDelta);
                upsertStage(stages, stageFrom(composeStarted, "ai_compose_cards", "生成讲解", "completed",
                        "模型已返回并通过结构化卡片校验。"));
            } catch (RuntimeException exception) {
                upsertStage(stages, stageFrom(composeStarted, "ai_compose_cards", "生成讲解", "failed",
                        "模型生成未完成：" + exception.getClass().getSimpleName()));
                throw exception;
            }
        }
        aiDraft = aiCardDraft.aiDraft();
        conversationTitle = StudentExplanationConversationTitleSupport.resolve(
                aiCardDraft.conversationTitle(),
                visibleQuestion,
                null);
        // Preserve the live model's section count, labels, and order. Cards are a transport format, not a lesson template.
        cards.addAll(aiCardDraft.cards());
        emitProgress(listener, normalizedRequest, visibleQuestion, stages, cards, sources, imageRecord,
                imageUnderstanding, aiDraft, conversationTitle, startedNanos, "讲解卡片已生成，正在整理展示。");

        upsertStage(stages, runningStage("assemble_cards", "整理卡片"));
        emitProgress(listener, normalizedRequest, visibleQuestion, stages, cards, sources, imageRecord,
                imageUnderstanding, aiDraft, conversationTitle, startedNanos, "正在整理讲解卡片。");
        upsertStage(stages, stage("assemble_cards", "整理卡片", "completed",
                "已使用真实模型输出并解析为讲解卡片，同时保留原题、知识点、方法和资源链接。", startedNanos));
        emitProgress(listener, normalizedRequest, visibleQuestion, stages, cards, sources, imageRecord,
                imageUnderstanding, aiDraft, conversationTitle, startedNanos, "讲解卡片已整理完成。");

        String explanationId = UUID.randomUUID().toString();
        upsertStage(stages, runningStage("persist_history", "保存记录"));
        emitProgress(listener, normalizedRequest, visibleQuestion, stages, cards, sources, imageRecord,
                imageUnderstanding, aiDraft, conversationTitle, startedNanos, "正在保存讲解记录。");
        if (historyStore.durable()) {
            upsertStage(stages, stage("persist_history", "保存记录", "completed",
                    "已写入 MySQL 历史记录，后续可恢复会话。", startedNanos));
        } else {
            stages.removeIf(stage -> "persist_history".equals(stage.stageKey()));
        }

        StudentExplanationResponse response = new StudentExplanationResponse(
                explanationId,
                normalizedRequest.conversationId(),
                conversationTitle,
                normalizedSubject.tenantId(),
                studentId(normalizedSubject),
                normalizedSubject.subjectType(),
                visibleQuestion,
                imageStatus(imageRecord),
                imageUnderstanding,
                GENERATED_BY,
                aiDraft,
                List.copyOf(stages),
                List.copyOf(cards),
                List.copyOf(sources),
                elapsedMs(startedNanos));
        historyStore.save(normalizedRequest, normalizedSubject, imageRecord, response);
        refreshConversationContextCache(normalizedRequest, normalizedSubject, response);
        emitProgress(listener, normalizedRequest, visibleQuestion, stages, cards, sources, imageRecord,
                imageUnderstanding, aiDraft, conversationTitle, startedNanos, "本轮讲解已完成。");
        listener.onCompleted(response);
        return response;
    }

    /**
     * Runs one bounded ReAct decision and executes only the tools selected by that decision.
     *
     * <p>Request flags form a permission allow-list, not an instruction to run every tool. The model supplies the
     * concrete retrieval query; this service validates tool names and enforces tenant visibility.</p>
     */
    private ReactEvidence executeReactTools(
            StudentExplanationRequest request, RequestSubject subject, String query,
            List<StudentExplanationResponse.WorkflowStage> stages,
            List<StudentExplanationResponse.ExplanationSource> sources,
            StudentExplanationProgressListener listener, String visibleQuestion,
            List<StudentExplanationResponse.ExplanationCard> cards, StudentExplanationImageRecord imageRecord,
            String imageDataUrl,
            StudentExplanationResponse.ImageUnderstanding imageUnderstanding,
            StudentExplanationResponse.AiDraft aiDraft, String conversationTitle, long startedNanos,
            String packedConversationContext) {
        listener.throwIfCancelled();
        Set<String> available = availableReactTools(request, subject);
        long decisionStarted = System.nanoTime();
        if (available.isEmpty()) {
            // 是否检索交给 AI 决策（react 规划轮），Java 不再用关键词门槛替模型判断——老板实测"教材哪一页"
            // 被门槛跳过检索后模型凭记忆泛答。只有完全无授权工具时才直接生成讲解。
            upsertStage(stages, stageFrom(decisionStarted, "react_decision", "AI判断", "completed",
                    "当前请求未启用可授权检索工具，直接生成讲解。"));
            emitProgress(listener, request, visibleQuestion, stages, cards, sources, imageRecord,
                    imageUnderstanding, aiDraft, conversationTitle, startedNanos,
                    "当前请求未启用可授权检索工具，直接生成讲解。");
            return new ReactEvidence(List.of(), List.of(), null);
        }
        List<KnowledgeGraphSpineResponse.Node> knowledgeNodes = new ArrayList<>();
        List<TeacherResourceBlockSearchResponse.Hit> teacherHits = new ArrayList<>();
        List<String> observations = new ArrayList<>();
        upsertStage(stages, runningStage("react_decision", "AI判断"));
        emitProgress(listener, request, visibleQuestion, stages, cards, sources, imageRecord,
                imageUnderstanding, aiDraft, conversationTitle, startedNanos,
                "AI正在判断这道题是否需要检索资料。" + (available.isEmpty() ? "当前没有可用检索工具。" : "可用工具：" + String.join("、", available)));
        final boolean[] decisionTokenSeen = {false};
        StudentExplanationAiStreamListener decisionStream = (delta, ignoredCards) -> {
            listener.throwIfCancelled();
            listener.onAiDelta(delta, List.of());
            if (!decisionTokenSeen[0] && delta != null && !text(delta.contentDelta()).isBlank()) {
                decisionTokenSeen[0] = true;
                emitProgress(listener, request, visibleQuestion, stages, cards, sources, imageRecord,
                        imageUnderstanding, aiDraft, conversationTitle, startedNanos,
                        "AI已开始判断，正在生成实际工具和检索参数。");
            }
        };
        // 决策轮固定走默认快速路由，不吃用户的讲解模型偏好（老板 2026-09-01"太慢了"）：
        // 偏好模型（如 GLM 强制思考）连输出一个决策 JSON 都要先想近 30 秒，纯系统开销；
        // "模型切换"语义只作用于讲解生成（compose），决策与检索属于基础设施仍用默认 fast_text 路由。
        StudentExplanationAiCardService.ReactDecision decision = aiCardService.nextReactDecision(
                reactProblemContext(visibleQuestion, packedConversationContext),
                sources, observations, available, imageDataUrl, decisionStream,
                request.clientRequestId() + ":react");
        String decisionDetail;
        if (decision.isFinal()) {
            decisionDetail = "AI判断题目自洽，本轮不执行检索。";
        } else if (decision.isAction()) {
            decisionDetail = "AI选择工具：" + String.join("、", decision.tools())
                    + (decision.searchQueries().isEmpty() ? "；未生成有效检索词，本轮跳过检索。"
                    : "；准备分别检索 " + decision.searchQueries().size() + " 个知识点。");
        } else {
            decisionDetail = "AI未选择可执行检索工具，本轮直接生成讲解。";
        }
        upsertStage(stages, stageFrom(decisionStarted, "react_decision", "AI判断", "completed", decisionDetail));
        emitProgress(listener, request, visibleQuestion, stages, cards, sources, imageRecord,
                imageUnderstanding, aiDraft, conversationTitle, startedNanos, decisionDetail);
        if (decision.isFinal()) {
            return new ReactEvidence(List.of(), List.of(), decision.finalDraft());
        }
        List<String> retrievalQueries = decision.searchQueries().stream()
                .map(queryValue -> text(queryValue).strip())
                .filter(queryValue -> !queryValue.isBlank())
                .distinct()
                .toList();
        if (retrievalQueries.isEmpty()) {
            return new ReactEvidence(List.of(), List.of(), null);
        }
        for (String tool : decision.tools()) {
            listener.throwIfCancelled();
            if ("search_textbook".equals(tool)) {
                upsertStage(stages, runningToolStage("search_textbook", "检索教材", String.join("、", retrievalQueries), request.maxTextbookHits()));
                emitProgress(listener, request, visibleQuestion, stages, cards, sources, imageRecord,
                        imageUnderstanding, aiDraft, conversationTitle, startedNanos,
                        "正在分别检索 " + retrievalQueries.size() + " 个教材知识点，并按相关度合并排序。");
                List<TextbookSearchHit> hits = searchTextbooksInParallel(request, subject, retrievalQueries, stages);
                hits.stream().map(StudentExplanationService::textbookSource).forEach(sources::add);
                emitProgress(listener, request, visibleQuestion, stages, cards, sources, imageRecord,
                        imageUnderstanding, aiDraft, conversationTitle, startedNanos,
                        "教材资料已找到 " + hits.size() + " 条，正在交给 AI。");
                observations.add("教材检索命中 " + hits.size() + " 条证据。");
            } else if ("match_knowledge_graph".equals(tool)) {
                upsertStage(stages, runningToolStage(
                        "match_knowledge_graph", "匹配知识点", String.join("、", retrievalQueries), MAX_KNOWLEDGE_GRAPH_MATCHES));
                emitProgress(listener, request, visibleQuestion, stages, cards, sources, imageRecord,
                        imageUnderstanding, aiDraft, conversationTitle, startedNanos,
                        "正在分别匹配 " + retrievalQueries.size() + " 个知识点，并按相关度合并。");
                knowledgeNodes.addAll(matchKnowledgeGraphInParallel(request, subject, retrievalQueries, stages));
                knowledgeNodes.stream().map(StudentExplanationService::knowledgeSource).forEach(sources::add);
                emitProgress(listener, request, visibleQuestion, stages, cards, sources, imageRecord,
                        imageUnderstanding, aiDraft, conversationTitle, startedNanos,
                        "知识点资料已找到 " + knowledgeNodes.size() + " 条，正在交给 AI。");
                observations.add("知识图谱匹配 " + knowledgeNodes.size() + " 个知识点。");
            } else if ("search_teacher_resources".equals(tool)) {
                upsertStage(stages, runningToolStage(
                        "search_teacher_resources", "检索教师资料", String.join("、", retrievalQueries), request.maxTeacherResourceHits()));
                emitProgress(listener, request, visibleQuestion, stages, cards, sources, imageRecord,
                        imageUnderstanding, aiDraft, conversationTitle, startedNanos,
                        "正在分别检索 " + retrievalQueries.size() + " 个教师资料知识点，并按相关度合并排序。");
                teacherHits.addAll(searchTeacherResourcesInParallel(request, subject, retrievalQueries, stages));
                Map<String, TeacherResourceDocumentResponse> documentsById = teacherDocumentsById(subject.tenantId(), teacherHits);
                teacherHits.stream().map(hit -> teacherSource(hit, documentsById.get(hit.documentId()))).forEach(sources::add);
                emitProgress(listener, request, visibleQuestion, stages, cards, sources, imageRecord,
                        imageUnderstanding, aiDraft, conversationTitle, startedNanos,
                        "教师资料已找到 " + teacherHits.size() + " 条，正在交给 AI。");
                observations.add("教师资料检索命中 " + teacherHits.size() + " 条证据。");
            }
        }
        return new ReactEvidence(List.copyOf(knowledgeNodes), List.copyOf(teacherHits), null);
    }

    /** Runs independent query retrieval concurrently, then keeps the highest-scoring hit per textbook chunk. */
    private List<TextbookSearchHit> searchTextbooksInParallel(
            StudentExplanationRequest request, RequestSubject subject, List<String> queries,
            List<StudentExplanationResponse.WorkflowStage> stages) {
        List<CompletableFuture<List<TextbookSearchHit>>> futures = queries.stream()
                .map(queryValue -> CompletableFuture.supplyAsync(
                        () -> searchTextbooks(request, subject, queryValue, stages), retrievalExecutor))
                .toList();
        Map<String, TextbookSearchHit> bestByChunk = new LinkedHashMap<>();
        futures.stream().flatMap(future -> future.join().stream()).forEach(hit ->
                bestByChunk.merge(text(hit.docId()) + ":" + text(hit.chunkId()), hit,
                        (left, right) -> right.score() > left.score() ? right : left));
        List<TextbookSearchHit> ranked = bestByChunk.values().stream()
                .sorted(Comparator.comparingDouble(TextbookSearchHit::score).reversed()
                        .thenComparing(TextbookSearchHit::sectionTitle, Comparator.nullsLast(String::compareTo)))
                .limit(request.maxTextbookHits())
                .toList();
        if (!hasFailedStage(stages, "search_textbook")) {
            upsertStage(stages, stageFrom(System.nanoTime(), "search_textbook", "检索教材", "completed",
                    "调用参数：" + toolParameters(String.join("、", queries), request.maxTextbookHits())
                            + "；分别检索 " + queries.size() + " 个知识点，去重重排后纳入 " + ranked.size() + " 条教材证据。"));
        }
        return ranked;
    }

    /** Runs independent query retrieval concurrently, then keeps the highest-scoring hit per visible block. */
    private List<TeacherResourceBlockSearchResponse.Hit> searchTeacherResourcesInParallel(
            StudentExplanationRequest request, RequestSubject subject, List<String> queries,
            List<StudentExplanationResponse.WorkflowStage> stages) {
        List<CompletableFuture<List<TeacherResourceBlockSearchResponse.Hit>>> futures = queries.stream()
                .map(queryValue -> CompletableFuture.supplyAsync(
                        () -> searchTeacherResources(request, subject, queryValue, stages), retrievalExecutor))
                .toList();
        Map<String, TeacherResourceBlockSearchResponse.Hit> bestByBlock = new LinkedHashMap<>();
        futures.stream().flatMap(future -> future.join().stream()).forEach(hit ->
                bestByBlock.merge(text(hit.documentId()) + ":" + text(hit.blockId()), hit,
                        (left, right) -> right.score() > left.score() ? right : left));
        List<TeacherResourceBlockSearchResponse.Hit> ranked = bestByBlock.values().stream()
                .sorted(Comparator.comparingDouble(TeacherResourceBlockSearchResponse.Hit::score).reversed()
                        .thenComparing(TeacherResourceBlockSearchResponse.Hit::documentTitle, Comparator.nullsLast(String::compareTo)))
                .limit(request.maxTeacherResourceHits())
                .toList();
        if (!hasFailedStage(stages, "search_teacher_resources")) {
            upsertStage(stages, stageFrom(System.nanoTime(), "search_teacher_resources", "检索教师资料", "completed",
                    "调用参数：" + toolParameters(String.join("、", queries), request.maxTeacherResourceHits())
                            + "；分别检索 " + queries.size() + " 个知识点，去重重排后纳入 " + ranked.size() + " 条教师资料。"));
        }
        return ranked;
    }

    /** Matches graph nodes for every query concurrently and removes duplicate concepts before ranking. */
    private List<KnowledgeGraphSpineResponse.Node> matchKnowledgeGraphInParallel(
            StudentExplanationRequest request, RequestSubject subject, List<String> queries,
            List<StudentExplanationResponse.WorkflowStage> stages) {
        List<CompletableFuture<List<KnowledgeGraphSpineResponse.Node>>> futures = queries.stream()
                .map(queryValue -> CompletableFuture.supplyAsync(
                        () -> matchKnowledgeGraph(request, subject, queryValue, stages), retrievalExecutor))
                .toList();
        Map<String, KnowledgeGraphSpineResponse.Node> uniqueNodes = new LinkedHashMap<>();
        futures.stream().flatMap(future -> future.join().stream()).forEach(node -> uniqueNodes.putIfAbsent(node.id(), node));
        List<KnowledgeGraphSpineResponse.Node> ranked = uniqueNodes.values().stream()
                .limit(MAX_KNOWLEDGE_GRAPH_MATCHES)
                .toList();
        if (!hasFailedStage(stages, "match_knowledge_graph")) {
            upsertStage(stages, stageFrom(System.nanoTime(), "match_knowledge_graph", "匹配知识点", "completed",
                    "调用参数：" + toolParameters(String.join("、", queries), MAX_KNOWLEDGE_GRAPH_MATCHES)
                            + "；分别匹配 " + queries.size() + " 个知识点，去重后纳入 " + ranked.size() + " 个主干知识点。"));
        }
        return ranked;
    }

    /** Builds the model-visible tool allow-list from backend policy, never from client-supplied tool names. */
    private static Set<String> availableReactTools(StudentExplanationRequest request, RequestSubject subject) {
        Set<String> tools = new LinkedHashSet<>();
        if (Boolean.TRUE.equals(request.searchTextbook())) tools.add("search_textbook");
        if (Boolean.TRUE.equals(request.searchKnowledgeGraph())) tools.add("match_knowledge_graph");
        // Visibility is resolved by TeacherResourceVisibilityPolicy at the Java tool boundary.  Students may search
        // TENANT_PUBLIC/PUBLIC_TEXTBOOK material, but never receive a private document simply because a UI flag says
        // "teacher resources".  Keeping the tool available lets the model actively retrieve public follow-up facts.
        if (Boolean.TRUE.equals(request.searchTeacherResources())) tools.add("search_teacher_resources");
        return tools;
    }

    /** Evidence accumulated by ReAct actions and supplied to the final answer composer. */
    private record ReactEvidence(List<KnowledgeGraphSpineResponse.Node> knowledgeNodes,
                                 List<TeacherResourceBlockSearchResponse.Hit> teacherHits,
                                 StudentExplanationAiCardService.AiCardDraft finalDraft) { }

    /**
     * Requires backend-resolved identity; callers that need a test subject must pass it explicitly.
     */
    private static RequestSubject requireSubject(RequestSubject subject) {
        if (subject == null) {
            throw new IllegalArgumentException("Request subject is required");
        }
        return subject;
    }

    /**
     * Loads the model-safe context from Redis first and falls back to the complete MySQL history projection.
     */
    private ConversationContextState loadConversationContext(
            StudentExplanationRequest request,
            RequestSubject subject,
            List<StudentExplanationResponse.WorkflowStage> stages) {
        long stageStarted = System.nanoTime();
        try {
            StudentExplanationConversationContext cached = conversationContextCache.find(
                    subject.tenantId(), subject.subjectType(), subject.subjectId(), request.conversationId())
                    .orElse(null);
            if (cached != null) {
                List<StudentExplanationHistorySummary> history = cached.messages().stream()
                        .map(StudentExplanationService::historySummary)
                        .toList();
                upsertStage(stages, stageFrom(stageStarted, "load_conversation_context", "读取对话上下文", "completed",
                        "已从会话缓存读取 " + history.size() + " 条最近记录。"));
                return new ConversationContextState(history, cached.messages(), cached.summary());
            }
            List<StudentExplanationHistorySummary> history = historyStore.findRecent(
                    subject.tenantId(),
                    subject.subjectType(),
                    subject.subjectId(),
                    request.conversationId(),
                    conversationHistoryFetchLimit);
            List<StudentExplanationConversationContextMessage> messages = history.stream()
                    .sorted(Comparator.comparing(StudentExplanationHistorySummary::createdAt,
                            Comparator.nullsLast(Comparator.naturalOrder()))
                            .thenComparing(StudentExplanationHistorySummary::explanationId,
                                    Comparator.nullsLast(String::compareTo)))
                    .map(StudentExplanationService::contextMessage)
                    .toList();
            StudentExplanationContextSummary summary = historyStore.findContextSummary(
                    subject.tenantId(), subject.subjectType(), subject.subjectId(), request.conversationId());
            StudentExplanationConversationContext context = new StudentExplanationConversationContext(messages, summary);
            conversationContextCache.put(subject.tenantId(), subject.subjectType(), subject.subjectId(),
                    request.conversationId(), context);
            upsertStage(stages, stageFrom(stageStarted, "load_conversation_context", "读取对话上下文", "completed",
                    "已从 MySQL 恢复 " + messages.size() + " 条最近记录。"));
            return new ConversationContextState(history, messages, summary);
        } catch (RuntimeException e) {
            upsertStage(stages, stageFrom(stageStarted, "load_conversation_context", "读取对话上下文", "failed",
                    e.getClass().getSimpleName()));
            throw e;
        }
    }

    /**
     * Runs the deterministic V2 LangGraph context packer without changing the existing evidence-backed generator.
     */
    private String prepareConversationContext(
            StudentExplanationRequest request,
            RequestSubject subject,
            String visibleQuestion,
            ConversationContextState context,
            List<StudentExplanationResponse.WorkflowStage> stages) {
        if (context.messages().isEmpty() && context.summary() == null) {
            return "";
        }
        if (canUseRawRecentContext(context.messages(), context.summary())) {
            long stageStarted = System.nanoTime();
            String rawContext = packRawRecentContext(visibleQuestion, context.messages());
            upsertStage(stages, stageFrom(stageStarted, "prepare_conversation_context", "使用最近对话上下文", "completed",
                    "最近 " + context.messages().size() + " 条记录约 " + estimateContextTokens(context.messages())
                            + " token，低于 " + RAW_RECENT_CONTEXT_MAX_RECORDS + " 条/"
                            + RAW_RECENT_CONTEXT_MAX_ESTIMATED_TOKENS + " token 安全阈值；未调用上下文压缩服务。"));
            return rawContext;
        }
        long stageStarted = System.nanoTime();
        try {
            PythonMigratedWorkloadClient.ConversationContextPreparation prepared = aiCardService.prepareConversationContext(
                    request.clientRequestId() + ":context",
                    visibleQuestion,
                    context.messages(),
                    context.summary(),
                    contextMaxInputTokens,
                    contextReservedOutputTokens,
                    contextSummaryTriggerTokens);
            PythonMigratedWorkloadClient.ConversationContextSummary update = prepared.memoryUpdate();
            if (update != null) {
                StudentExplanationContextSummary summary = new StudentExplanationContextSummary(
                        update.fromMessageId(), update.toMessageId(), update.version(), update.contentHash(),
                        update.content(), null);
                if (historyStore.updateContextSummary(
                        subject.tenantId(), subject.subjectType(), subject.subjectId(), request.conversationId(), summary)) {
                    conversationContextCache.put(subject.tenantId(), subject.subjectType(), subject.subjectId(),
                            request.conversationId(), new StudentExplanationConversationContext(context.messages(), summary));
                }
            }
            upsertStage(stages, stageFrom(stageStarted, "prepare_conversation_context", "压缩对话上下文", "completed",
                    "已按 " + prepared.inputTokens() + " token 预算选择 "
                            + prepared.selectedMessageIds().size() + " 条最近记录。"));
            return prepared.packedContext();
        } catch (RuntimeException exception) {
            upsertStage(stages, stageFrom(stageStarted, "prepare_conversation_context", "压缩对话上下文", "degraded",
                    "上下文压缩暂不可用，已继续使用当前题目。"));
            log.warn("student_explanation_context_prepare_failed conversationId={}", request.conversationId(), exception);
            return "";
        }
    }

    /** Uses a bounded verbatim window only while no persisted summary or budget pressure exists. */
    static boolean canUseRawRecentContext(
            List<StudentExplanationConversationContextMessage> messages,
            StudentExplanationContextSummary summary) {
        return summary == null && messages.size() <= RAW_RECENT_CONTEXT_MAX_RECORDS
                && estimateContextTokens(messages) <= RAW_RECENT_CONTEXT_MAX_ESTIMATED_TOKENS;
    }

    /** Mirrors the worker's message shape without a remote context-preparation call. */
    static String packRawRecentContext(
            String visibleQuestion,
            List<StudentExplanationConversationContextMessage> messages) {
        List<String> turns = messages.stream().map(message -> {
            String question = text(message.questionText()).strip();
            String answer = text(message.answerText()).strip();
            return (question.isBlank() ? "" : "用户：" + question)
                    + (question.isBlank() || answer.isBlank() ? "" : "\n")
                    + (answer.isBlank() ? "" : "助手：" + answer);
        }).filter(turn -> !turn.isBlank()).toList();
        String recent = turns.isEmpty() ? "" : "最近会话：\n" + String.join("\n\n", turns) + "\n\n";
        return recent + "当前题目：\n" + text(visibleQuestion).strip();
    }

    private static int estimateContextTokens(List<StudentExplanationConversationContextMessage> messages) {
        int characters = messages.stream().mapToInt(message -> text(message.questionText()).length()
                + text(message.answerText()).length() + 8).sum();
        return (characters + 1) / 2;
    }

    /**
     * Updates the Redis projection after the MySQL transaction has stored the complete response.
     */
    private void refreshConversationContextCache(
            StudentExplanationRequest request,
            RequestSubject subject,
            StudentExplanationResponse response) {
        try {
            List<StudentExplanationHistorySummary> history = historyStore.findRecent(
                    subject.tenantId(), subject.subjectType(), subject.subjectId(), request.conversationId(),
                    conversationHistoryFetchLimit);
            List<StudentExplanationConversationContextMessage> messages = history.stream()
                    .sorted(Comparator.comparing(StudentExplanationHistorySummary::createdAt,
                            Comparator.nullsLast(Comparator.naturalOrder()))
                            .thenComparing(StudentExplanationHistorySummary::explanationId,
                                    Comparator.nullsLast(String::compareTo)))
                    .map(StudentExplanationService::contextMessage)
                    .toList();
            StudentExplanationContextSummary summary = historyStore.findContextSummary(
                    subject.tenantId(), subject.subjectType(), subject.subjectId(), request.conversationId());
            conversationContextCache.put(subject.tenantId(), subject.subjectType(), subject.subjectId(),
                    request.conversationId(), new StudentExplanationConversationContext(messages, summary));
        } catch (RuntimeException exception) {
            log.warn("student_explanation_context_cache_refresh_failed conversationId={} explanationId={}",
                    request.conversationId(), response.explanationId(), exception);
        }
    }

    private record ConversationContextState(
            List<StudentExplanationHistorySummary> history,
            List<StudentExplanationConversationContextMessage> messages,
            StudentExplanationContextSummary summary) {
    }

    /**
     * Returns a backend conversation id, preserving a valid caller-supplied one.
     */
    private static String conversationId(StudentExplanationRequest request) {
        String value = text(request.conversationId()).strip();
        return value.isBlank() ? UUID.randomUUID().toString() : value;
    }

    /**
     * Resolves a temporary uploaded image and enforces owner and expiration checks.
     */
    private StudentExplanationImageRecord resolveImageRecord(
            StudentExplanationRequest request,
            RequestSubject subject) {
        if (request.imageUploadId() == null) {
            return null;
        }
        return imageStoreService.findUsable(request.imageUploadId(), subject)
                .orElseThrow(() -> new IllegalArgumentException("Image upload is missing or expired"));
    }

    /**
     * Searches configured textbook resources when the caller enables textbook retrieval.
     */
    private List<TextbookSearchHit> searchTextbooks(
            StudentExplanationRequest request,
            RequestSubject subject,
            String query,
            List<StudentExplanationResponse.WorkflowStage> stages) {
        long stageStarted = System.nanoTime();
        if (!Boolean.TRUE.equals(request.searchTextbook())) {
            return List.of();
        }
        try {
            TextbookSearchResponse response = textbookRetrievalService.search(
                    textbookResourceProperties.processedBooksRoot(),
                    new TextbookSearchRequest(query, request.maxTextbookHits()),
                    new RetrievalRequestContext(
                            subject.tenantId(),
                            subject.subjectType(),
                            subject.subjectId(),
                            null,
                            subject.deviceId(),
                            null,
                            ENDPOINT));
            List<TextbookSearchHit> acceptedHits = response.hits().stream()
                    .filter(hit -> matchesConcreteTopic(query, hit.sectionTitle(), hit.textSnippet()))
                    .toList();
            // The trace must describe the exact evidence list later exposed to the learner, not an upstream candidate
            // total that may be capped or filtered before source cards are assembled.
            upsertStage(stages, stageFrom(stageStarted, "search_textbook", "检索教材", "completed",
                    "调用参数：" + toolParameters(query, request.maxTextbookHits())
                            + "；本轮纳入 " + acceptedHits.size() + " 条教材证据。"));
            return acceptedHits;
        } catch (RuntimeException e) {
            log.warn("student_explanation_textbook_search_failed tenantId={} subjectType={} subjectId={} query={}",
                    subject.tenantId(), subject.subjectType(), subject.subjectId(), compact(query), e);
            upsertStage(stages, stageFrom(stageStarted, "search_textbook", "检索教材", "degraded",
                    "教材暂时未取到，先按知识点和可用教师资料继续讲。" + compact(e.getMessage())));
            return List.of();
        }
    }

    /**
     * Matches the curated display spine instead of the noisy OCR-level raw graph.
     */
    private List<KnowledgeGraphSpineResponse.Node> matchKnowledgeGraph(
            StudentExplanationRequest request,
            RequestSubject subject,
            String query,
            List<StudentExplanationResponse.WorkflowStage> stages) {
        long stageStarted = System.nanoTime();
        if (!Boolean.TRUE.equals(request.searchKnowledgeGraph())) {
            return List.of();
        }
        try {
            KnowledgeGraphSpineResponse spine = knowledgeGraphSpineService.displaySpine(
                    subject.tenantId(),
                    subject.subjectType(),
                    subject.subjectId());
            if (spine.nodes().isEmpty()) {
                log.warn("student_explanation_knowledge_graph_empty tenantId={} subjectType={} subjectId={}",
                        subject.tenantId(), subject.subjectType(), subject.subjectId());
            }
            List<KnowledgeGraphSpineResponse.Node> candidates = spine.nodes().stream()
                    .filter(node -> matchesKnowledgeTopic(query, node))
                    .toList();
            List<Double> semanticScores = semanticKnowledgeScores(query, candidates, subject);
            List<NodeMatch> matches = new ArrayList<>();
            for (int index = 0; index < candidates.size(); index += 1) {
                KnowledgeGraphSpineResponse.Node candidate = candidates.get(index);
                double semanticScore = index < semanticScores.size() ? semanticScores.get(index) : 0.0d;
                NodeMatch match = new NodeMatch(
                        candidate,
                        knowledgeScore(query, candidate, semanticScore),
                        knowledgeMatchReason(query, candidate, semanticScore));
                if (qualifiesNodeMatch(match.node(), match.score(), match.reason())) {
                    matches.add(match);
                }
            }
            List<KnowledgeGraphSpineResponse.Node> nodes = matches.stream()
                    .sorted(Comparator.comparingInt(NodeMatch::score).reversed()
                            .thenComparing(match -> match.node().label()))
                    .limit(MAX_KNOWLEDGE_GRAPH_MATCHES)
                    .map(NodeMatch::node)
                    .toList();
            String detail = nodes.isEmpty()
                    ? "调用参数：" + toolParameters(query, MAX_KNOWLEDGE_GRAPH_MATCHES) + "；本轮未找到可确认匹配的主干知识点。"
                    : "调用参数：" + toolParameters(query, MAX_KNOWLEDGE_GRAPH_MATCHES) + "；本轮纳入 " + nodes.size() + " 个主干知识点：" + String.join("、",
                            nodes.stream().map(KnowledgeGraphSpineResponse.Node::label).toList()) + "。";
            upsertStage(stages, stageFrom(stageStarted, "match_knowledge_graph", "匹配知识点", "completed", detail));
            return nodes;
        } catch (RuntimeException e) {
            upsertStage(stages, stageFrom(stageStarted, "match_knowledge_graph", "匹配知识点", "failed", e.getMessage()));
            throw e;
        }
    }

    /**
     * Runs one real embedding comparison for the visible graph candidates.
     *
     * <p>The graph remains usable when Milvus or the embedding worker is unavailable, but that path is explicit in
     * the warning log and returns zero semantic points. This prevents a silent return to a different invented score.
     * Candidate text combines the label, curriculum location, and source summary so a synonym can match a concept
     * even when the exact display label is absent from the student's wording.</p>
     */
    private List<Double> semanticKnowledgeScores(
            String query,
            List<KnowledgeGraphSpineResponse.Node> candidates,
            RequestSubject subject) {
        if (vectorIndexService == null || candidates.isEmpty() || compactForMatch(query).isBlank()) {
            return java.util.Collections.nCopies(candidates.size(), 0.0d);
        }
        List<String> candidateTexts = candidates.stream()
                .map(node -> text(node.label()) + "\n" + text(node.chapterPath()) + "\n" + text(node.sourceSummary()))
                .toList();
        try {
            List<Double> scores = vectorIndexService.semanticSimilarity(query, candidateTexts);
            if (scores.size() != candidates.size()) {
                throw new IllegalStateException("semantic graph score count mismatch");
            }
            return scores;
        } catch (RuntimeException exception) {
            log.warn("student_explanation_knowledge_graph_semantic_fallback tenantId={} subjectType={} subjectId={} query={}",
                    subject.tenantId(), subject.subjectType(), subject.subjectId(), compact(query), exception);
            return java.util.Collections.nCopies(candidates.size(), 0.0d);
        }
    }

    /**
     * Searches teacher resources only for teacher/admin subjects.
     */
    private List<TeacherResourceBlockSearchResponse.Hit> searchTeacherResources(
            StudentExplanationRequest request,
            RequestSubject subject,
            String query,
            List<StudentExplanationResponse.WorkflowStage> stages) {
        long stageStarted = System.nanoTime();
        if (!Boolean.TRUE.equals(request.searchTeacherResources())) {
            return List.of();
        }
        try {
            TeacherResourceBlockSearchResponse response = teacherResourceBlockSearchService.search(
                    subject.tenantId(),
                    subject.subjectType(),
                    subject.subjectId(),
                    query,
                    request.maxTeacherResourceHits(),
                    ENDPOINT);
            List<TeacherResourceBlockSearchResponse.Hit> acceptedHits = response.hits().stream()
                    .filter(hit -> matchesConcreteTopic(query, hit.documentTitle(), hit.snippet()))
                    .toList();
            // Keep the trace count aligned with the teacher_resource sources actually sent to the explanation model.
            upsertStage(stages, stageFrom(stageStarted, "search_teacher_resources", "检索教师资料", "completed",
                    "调用参数：" + toolParameters(query, request.maxTeacherResourceHits())
                            + "；本轮纳入 " + acceptedHits.size() + " 条教师资料。"));
            return acceptedHits;
        } catch (RuntimeException e) {
            log.warn("student_explanation_teacher_resource_search_failed tenantId={} subjectType={} subjectId={} query={}",
                    subject.tenantId(),
                    subject.subjectType(),
                    subject.subjectId(),
                    compact(query),
                    e);
            upsertStage(stages, stageFrom(stageStarted, "search_teacher_resources", "检索教师资料", "degraded",
                    "教师资料暂时未取到，先按教材和知识点继续讲。" + compact(e.getMessage())));
            return List.of();
        }
    }

    /**
     * Resolves teacher document metadata so cards can expose real Feishu or local links.
     */
    private Map<String, TeacherResourceDocumentResponse> teacherDocumentsById(
            String tenantId,
            List<TeacherResourceBlockSearchResponse.Hit> hits) {
        Map<String, TeacherResourceDocumentResponse> documentsById = new LinkedHashMap<>();
        for (TeacherResourceBlockSearchResponse.Hit hit : hits) {
            if (documentsById.containsKey(hit.documentId())) {
                continue;
            }
            TeacherResourceDocumentResponse document = teacherResourceStore.find(tenantId, hit.documentId());
            if (document != null) {
                documentsById.put(hit.documentId(), document);
            }
        }
        return documentsById;
    }

    /**
     * Converts a textbook hit to a stable source entry.
     */
    private static StudentExplanationResponse.ExplanationSource textbookSource(TextbookSearchHit hit) {
        return new StudentExplanationResponse.ExplanationSource(
                "textbook",
                hit.bookName() + " 第 " + hit.pageNo() + " 页",
                textbookUri(hit),
                "PUBLIC_TEXTBOOK",
                compact(hit.textSnippet()),
                hit.score(),
                chapterPath(hit),
                text(hit.pageImageUri()));
    }

    /**
     * Converts a knowledge graph node to a stable source entry.
     */
    private static StudentExplanationResponse.ExplanationSource knowledgeSource(KnowledgeGraphSpineResponse.Node node) {
        return new StudentExplanationResponse.ExplanationSource(
                "knowledge_graph",
                node.label(),
                knowledgeUri(node),
                node.permissionScope(),
                text(node.sourceSummary()),
                1.0,
                text(node.chapterPath()),
                "");
    }

    /**
     * Converts a teacher resource hit to a stable source entry with a real local or Feishu URL when available.
     */
    private static StudentExplanationResponse.ExplanationSource teacherSource(
            TeacherResourceBlockSearchResponse.Hit hit,
            TeacherResourceDocumentResponse document) {
        String title = document != null && !text(document.title()).isBlank()
                ? document.title()
                : text(hit.documentTitle());
        if (title.isBlank()) {
            title = text(hit.sourcePath()).isBlank() ? "教师资料" : hit.sourcePath();
        }
        return new StudentExplanationResponse.ExplanationSource(
                "teacher_resource",
                title,
                teacherSourceUri(hit),
                hit.permissionScope(),
                compact(hit.snippet()),
                hit.score(),
                teacherSourcePath(hit, document),
                teacherOpenUrl(document));
    }

    private static String teacherSourceUri(TeacherResourceBlockSearchResponse.Hit hit) {
        return "teacher-resource://" + hit.documentId() + "/block/" + hit.blockId();
    }

    private static String textbookUri(TextbookSearchHit hit) {
        return "textbook://" + hit.docId() + "/page/" + hit.pageNo() + "#chunk=" + hit.chunkId();
    }

    private static String knowledgeUri(KnowledgeGraphSpineResponse.Node node) {
        return "math-agent://knowledge/graph-spine/v0.1#node=" + node.id();
    }

    private static String chapterPath(TextbookSearchHit hit) {
        if (hit.chapterPath() == null || hit.chapterPath().isEmpty()) {
            return text(hit.sectionTitle());
        }
        return String.join(" / ", hit.chapterPath());
    }

    private static String teacherSourcePath(
            TeacherResourceBlockSearchResponse.Hit hit,
            TeacherResourceDocumentResponse document) {
        if (!text(hit.sourcePath()).isBlank()) {
            return hit.sourcePath();
        }
        if (document != null && !text(document.localPath()).isBlank()) {
            return document.localPath();
        }
        return text(hit.section()).isBlank() ? text(hit.chapter()) : text(hit.chapter()) + " / " + text(hit.section());
    }

    private static String teacherOpenUrl(TeacherResourceDocumentResponse document) {
        if (document == null) {
            return "";
        }
        if (!text(document.originalUrl()).isBlank()) {
            return document.originalUrl();
        }
        return text(document.localPath());
    }

    /**
     * Scores one graph node against the query using exact and character-overlap signals.
     */
    private static int knowledgeScore(String query, KnowledgeGraphSpineResponse.Node node) {
        String compactQuery = compactForMatch(query);
        String label = compactForMatch(node.label());
        String chapter = compactForMatch(node.chapterPath());
        if (compactQuery.isBlank() || label.isBlank()) {
            return 0;
        }
        int score = compactQuery.contains(label) ? 20 : 0;
        if (!chapter.isBlank() && compactQuery.contains(chapter)) {
            score += 8;
        }
        Set<Integer> labelChars = label.codePoints()
                .filter(Character::isLetterOrDigit)
                .collect(LinkedHashSet::new, LinkedHashSet::add, LinkedHashSet::addAll);
        long overlap = compactQuery.codePoints().filter(labelChars::contains).count();
        return score + (int) Math.min(overlap, 10);
    }

    /** Adds semantic evidence only after the configured cosine boundary is met. */
    private int knowledgeScore(String query, KnowledgeGraphSpineResponse.Node node, double semanticScore) {
        int lexicalScore = knowledgeScore(query, node);
        if (semanticScore < knowledgeGraphSemanticMinScore) {
            return lexicalScore;
        }
        return lexicalScore + (int) Math.round(semanticScore * 100.0d);
    }

    /** Keeps ordinary AI retrieval on the concrete requested branch before score-based ranking. */
    private static boolean matchesConcreteTopic(String query, String title, String snippet) {
        String normalizedQuery = compactForMatch(query);
        String text = compactForMatch(text(title) + " " + text(snippet));
        if (normalizedQuery.contains("二次函数")) {
            boolean quadratic = text.contains("二次函数") || text.contains("x^2") || text.contains("x²")
                    || text.contains("x2");
            return quadratic && !text.contains("x^3") && !text.contains("x³") && !text.contains("x3")
                    && !text.contains("双曲线") && !text.contains("椭圆") && !text.contains("圆锥曲线");
        }
        if (normalizedQuery.contains("隐零点")) {
            return text.contains("隐零点") || text.contains("零点");
        }
        return true;
    }

    /** Prevents graph character-overlap from adding sibling modules to a quadratic query. */
    private static boolean matchesKnowledgeTopic(String query, KnowledgeGraphSpineResponse.Node node) {
        String normalizedQuery = compactForMatch(query);
        String label = compactForMatch(node.label());
        String chapter = compactForMatch(node.chapterPath());
        if (!normalizedQuery.contains("二次函数")) {
            return true;
        }
        if (label.equals("函数") || label.contains("二次") || label.contains("一元二次")) {
            return true;
        }
        return (chapter.contains("二次") || chapter.contains("方程不等式"))
                && !label.contains("三角") && !label.contains("统计") && !label.contains("数列");
    }

    /**
     * Determines the visible problem text shown to the user and stored in history.
     */
    private static String visibleQuestion(
            StudentExplanationRequest request,
            StudentExplanationImageRecord imageRecord) {
        String value = text(request.questionText()).strip();
        if (!value.isBlank()) {
            return value;
        }
        return imageRecord == null ? "" : "图片讲题";
    }

    private static StudentExplanationConversationContextMessage contextMessage(StudentExplanationHistorySummary summary) {
        String answer = text(summary.cardsJson());
        try {
            JsonNode cards = HISTORY_OBJECT_MAPPER.readTree(answer);
            List<String> fragments = new ArrayList<>();
            if (cards != null && cards.isArray()) {
                for (JsonNode card : cards) {
                    String cardSummary = text(card.path("summary").asText(""));
                    if (!cardSummary.isBlank()) fragments.add(cardSummary);
                    if (fragments.size() >= 4) break;
                }
            }
            answer = String.join(" ", fragments);
        } catch (Exception ignored) {
            answer = "";
        }
        return new StudentExplanationConversationContextMessage(
                text(summary.explanationId()), compact(summary.questionText()), compact(answer), summary.createdAt());
    }

    private static StudentExplanationHistorySummary historySummary(StudentExplanationConversationContextMessage message) {
        return new StudentExplanationHistorySummary(
                text(message.explanationId()), "", "", "", "", "", "", "", text(message.questionText()),
                "", "", text(message.answerText()), "", "", 0, 0L, message.createdAt());
    }

    /**
     * Carries the durable conversation context into the first ReAct call as well as the final composition call.
     * Without this boundary a follow-up could retrieve against only its short new message and lose the original
     * problem, even though the final answer composer had already loaded the history.
     */
    private static String reactProblemContext(String visibleQuestion, String packedConversationContext) {
        String context = text(packedConversationContext).strip();
        if (context.isBlank()) {
            return text(visibleQuestion);
        }
        return context;
    }

    /**
     * Returns the image handling status without over-claiming OCR.
     */
    private static String imageStatus(StudentExplanationImageRecord imageRecord) {
        return imageRecord == null ? "none" : "image_direct_context";
    }

    /**
     * Returns the student id visible in response from backend identity.
     */
    private static String studentId(RequestSubject subject) {
        return "student".equals(subject.subjectType()) ? subject.subjectId() : null;
    }

    private static boolean isTeacherOrAdmin(String role) {
        return "teacher".equals(role) || "admin".equals(role);
    }

    /**
     * Emits a dynamic plan instead of a hard-coded fixed flow line.
     */
    private static String planDetail(
            StudentExplanationRequest request,
            RequestSubject subject,
            boolean hasImage) {
        List<String> steps = new ArrayList<>();
        if (hasImage) {
            // There is deliberately no standalone OCR/vision hop: the same multimodal model sees the original image.
            steps.add("将原题图直接加入 AI 上下文");
        }
        steps.add("理清原题");
        if (Boolean.TRUE.equals(request.searchTextbook())) {
            steps.add("检索教材");
        }
        if (Boolean.TRUE.equals(request.searchKnowledgeGraph())) {
            steps.add("匹配知识点");
        }
        if (Boolean.TRUE.equals(request.searchTeacherResources()) && isTeacherOrAdmin(subject.subjectType())) {
            steps.add("查看教师资料");
        }
        steps.add("生成讲解");
        steps.add("整理卡片");
        return "本轮计划：" + String.join(" → ", steps) + "。";
    }

    /**
     * Adds matched nodes and teacher tags into the AI prompt context so the model can speak more like a teacher.
     */
    private static String aiContextQuery(
            String query,
            List<KnowledgeGraphSpineResponse.Node> knowledgeNodes,
            List<TeacherResourceBlockSearchResponse.Hit> teacherHits) {
        List<String> lines = new ArrayList<>();
        lines.add(query);
        if (knowledgeNodes != null && !knowledgeNodes.isEmpty()) {
            lines.add("Matched knowledge nodes: " + String.join(", ",
                    knowledgeNodes.stream().map(KnowledgeGraphSpineResponse.Node::label).toList()));
        }
        LinkedHashSet<String> tags = new LinkedHashSet<>();
        if (teacherHits != null) {
            for (TeacherResourceBlockSearchResponse.Hit hit : teacherHits) {
                if (hit.graphTags() == null) {
                    continue;
                }
                for (String tag : hit.graphTags()) {
                    if (!text(tag).isBlank()) {
                        tags.add(tag.strip());
                    }
                }
            }
        }
        if (!tags.isEmpty()) {
            lines.add("Matched method tags: " + String.join(", ", tags));
        }
        return String.join("\n", lines);
    }

    private static String friendlyNodeType(String nodeType) {
        return switch (text(nodeType).toUpperCase(Locale.ROOT)) {
            case "MODULE" -> "模块";
            case "TOPIC" -> "知识点";
            case "METHOD" -> "方法";
            default -> "条目";
        };
    }

    /**
     * 说明知识点为什么会命中，避免前端只看到裸标签。
     */
    private static String knowledgeMatchReason(String query, KnowledgeGraphSpineResponse.Node node) {
        String compactQuery = compactForMatch(query);
        String label = compactForMatch(node.label());
        String chapter = compactForMatch(node.chapterPath());
        if (!label.isBlank() && compactQuery.contains(label)) {
            return "题目里直接出现了这个概念或它的同类表述";
        }
        if (!chapter.isBlank() && compactQuery.contains(chapter)) {
            return "题目条件与这个模块的典型场景一致";
        }
        if ("METHOD".equalsIgnoreCase(text(node.nodeType()))) {
            return "题目更像是在调用这种思想方法，而不只是单个公式";
        }
        return "根据题型、条件和所求的组合关系匹配到这里";
    }

    /** Explains when a concept was admitted by the configured semantic model rather than an exact label. */
    private String knowledgeMatchReason(
            String query,
            KnowledgeGraphSpineResponse.Node node,
            double semanticScore) {
        String lexicalReason = knowledgeMatchReason(query, node);
        if (semanticScore >= knowledgeGraphSemanticMinScore
                && !lexicalReason.contains("直接出现")
                && !lexicalReason.contains("模块的典型场景")) {
            return "根据知识点名称、章节语境和题意的语义相似度匹配到这里";
        }
        return lexicalReason;
    }

    /**
     * 过滤掉只有一两个公共字就误命中的节点，尤其避免把无关方法节点塞进前五。
     */
    private static boolean qualifiesNodeMatch(
            KnowledgeGraphSpineResponse.Node node,
            int score,
            String reason) {
        String nodeType = text(node.nodeType()).toUpperCase(Locale.ROOT);
        if (score <= 0) {
            return false;
        }
        if (reason.contains("直接出现")) {
            return true;
        }
        return switch (nodeType) {
            case "METHOD" -> score >= 8;
            case "TOPIC" -> score >= 3;
            case "MODULE" -> score >= 2;
            default -> score >= 4;
        };
    }

    /**
     * 提炼方法判断里可展示的触发线索，让“为什么用这个方法”说得更像老师。
     */
    private static List<String> methodClues(
            String query,
            List<KnowledgeGraphSpineResponse.Node> knowledgeNodes,
            List<TeacherResourceBlockSearchResponse.Hit> teacherHits) {
        LinkedHashSet<String> clues = new LinkedHashSet<>();
        String normalizedQuery = compact(query);
        if (!normalizedQuery.isBlank()) {
            String clipped = normalizedQuery.length() > 26 ? normalizedQuery.substring(0, 26).strip() + "…" : normalizedQuery;
            clues.add("题干关键词“" + clipped + "”");
        }
        if (knowledgeNodes != null) {
            knowledgeNodes.stream()
                    .map(KnowledgeGraphSpineResponse.Node::label)
                    .filter(label -> !text(label).isBlank())
                    .limit(3)
                    .forEach(label -> clues.add("命中知识点“" + label + "”"));
        }
        if (teacherHits != null) {
            for (TeacherResourceBlockSearchResponse.Hit hit : teacherHits) {
                if (hit.graphTags() == null) {
                    continue;
                }
                for (String tag : hit.graphTags()) {
                    if (!text(tag).isBlank()) {
                        clues.add("教师资料标签“" + tag.strip() + "”");
                    }
                    if (clues.size() >= 4) {
                        return List.copyOf(clues);
                    }
                }
            }
        }
        return List.copyOf(clues);
    }

    /**
     * 当知识图谱和教师标签还没提供明确方法时，用题型和已知条件生成更像老师的话。
     */
    private static List<String> derivedMethodItems(
            String query,
            List<KnowledgeGraphSpineResponse.Node> knowledgeNodes,
            String sourceText) {
        boolean hyperbola = knowledgeNodes.stream().anyMatch(node -> "双曲线".equals(text(node.label())));
        String compactQuery = compact(query);
        if (hyperbola && compactQuery.contains("焦距") && compactQuery.contains("2a")) {
            return List.of(
                    "基本量关系法｜题目直接给了焦距和 $2a$，先把 $c$ 和 $a$ 定出来，再用 $c^2=a^2+b^2$ 回收 $b^2$｜" + sourceText,
                    "参数回代法｜这类题的关键不是上来列大方程，而是先认清双曲线的基本量之间有什么固定关系｜" + sourceText);
        }
        return List.of("先判断路径｜先从题型、条件和所求出发，缩小解法范围｜" + sourceText);
    }

    private static String knownConditions(String question) {
        String value = text(question).strip();
        int start = value.indexOf("已知");
        int solve = firstPositiveIndex(value.indexOf("求"), value.indexOf("证明"), value.indexOf("说明"));
        if (start >= 0 && solve > start) {
            return value.substring(start + 2, solve).replaceAll("^[：:，,\\s]+", "").strip();
        }
        if (solve > 0) {
            return value.substring(0, solve).replaceAll("^[：:，,\\s]+", "").strip();
        }
        return "";
    }

    private static String targetTask(String question) {
        String value = text(question).strip();
        int solve = firstPositiveIndex(value.indexOf("求"), value.indexOf("证明"), value.indexOf("说明"));
        if (solve < 0 || solve >= value.length()) {
            return "";
        }
        return value.substring(solve).replaceAll("^[：:，,\\s]+", "").strip();
    }

    private static int firstPositiveIndex(int... values) {
        int result = Integer.MAX_VALUE;
        for (int value : values) {
            if (value >= 0 && value < result) {
                result = value;
            }
        }
        return result == Integer.MAX_VALUE ? -1 : result;
    }

    /**
     * Builds a stage elapsed from the request start timestamp.
     */
    private static StudentExplanationResponse.WorkflowStage stage(
            String key,
            String title,
            String status,
            String detail,
            long requestStartedNanos) {
        return new StudentExplanationResponse.WorkflowStage(key, title, status, detail, elapsedMs(requestStartedNanos));
    }

    /**
     * Builds a stage elapsed from the stage start timestamp.
     */
    private static StudentExplanationResponse.WorkflowStage stageFrom(
            long stageStartedNanos,
            String key,
            String title,
            String status,
            String detail) {
        return new StudentExplanationResponse.WorkflowStage(key, title, status, text(detail), elapsedMs(stageStartedNanos));
    }

    /**
     * Creates a visible running stage so the streaming UI shows only real active work.
     */
    private static StudentExplanationResponse.WorkflowStage runningStage(String key, String title) {
        return new StudentExplanationResponse.WorkflowStage(key, title, "running", "处理中。", 0);
    }

    /**
     * Makes the server-derived input of one read-only tool observable in the live trace.
     * Identity, tenant IDs, and raw provider prompts intentionally never cross this boundary.
     */
    private static StudentExplanationResponse.WorkflowStage runningToolStage(
            String key, String title, String query, int limit) {
        return new StudentExplanationResponse.WorkflowStage(
                key, title, "running", "调用参数：" + toolParameters(query, limit) + "。", 0);
    }

    /** Formats only learner-visible retrieval arguments after the request's server-side bounds have been applied. */
    private static String toolParameters(String query, int limit) {
        return "query=" + compact(query) + "；limit=" + Math.max(0, limit);
    }

    /**
     * Replaces earlier stage snapshots with the newest state for the same stage key.
     */
    private static void upsertStage(
            List<StudentExplanationResponse.WorkflowStage> stages,
            StudentExplanationResponse.WorkflowStage nextStage) {
        synchronized (stages) {
            for (int index = 0; index < stages.size(); index++) {
                if (text(stages.get(index).stageKey()).equals(nextStage.stageKey())) {
                    stages.set(index, nextStage);
                    return;
                }
            }
            stages.add(nextStage);
        }
    }

    /** Keeps a failed query visible when another parallel query finishes later and reports success. */
    private static boolean hasFailedStage(List<StudentExplanationResponse.WorkflowStage> stages, String stageKey) {
        synchronized (stages) {
            return stages.stream().anyMatch(stage -> stageKey.equals(stage.stageKey())
                    && ("failed".equals(stage.status()) || "degraded".equals(stage.status())));
        }
    }

    /**
     * Emits a compact progress snapshot for the streaming frontend.
     */
    private static void emitProgress(
            StudentExplanationProgressListener listener,
            StudentExplanationRequest request,
            String visibleQuestion,
            List<StudentExplanationResponse.WorkflowStage> stages,
            List<StudentExplanationResponse.ExplanationCard> cards,
            List<StudentExplanationResponse.ExplanationSource> sources,
            StudentExplanationImageRecord imageRecord,
            StudentExplanationResponse.ImageUnderstanding imageUnderstanding,
            StudentExplanationResponse.AiDraft aiDraft,
            String conversationTitle,
            long startedNanos,
            String message) {
        listener.throwIfCancelled();
        listener.onProgress(new StudentExplanationStreamProgress(
                request.conversationId(),
                conversationTitle,
                visibleQuestion,
                imageStatus(imageRecord),
                imageUnderstanding,
                aiDraft,
                List.copyOf(stages),
                List.copyOf(cards),
                List.copyOf(sources),
                elapsedMs(startedNanos)), text(message));
    }

    private static long elapsedMs(long startedNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos);
    }

    private static String compact(String value) {
        String stripped = text(value).replaceAll("\\s+", " ").strip();
        return stripped.length() <= 180 ? stripped : stripped.substring(0, 180).strip() + "…";
    }

    private static String compactForMatch(String value) {
        return text(value).replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
    }

    private static String text(String value) {
        return value == null ? "" : value;
    }

    /**
     * Internal graph node match score.
     */
    private record NodeMatch(KnowledgeGraphSpineResponse.Node node, int score, String reason) {
    }
}
