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
import java.io.IOException;
import java.nio.file.Files;
import java.util.Base64;
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
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Autowired;
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
    // 此版本号用于区分新版 Agent 结果与历史模板结果，无需重写既有会话记录。
    private static final String GENERATED_BY = "student_explanation_react_agent_v1";
    private static final Logger log = LoggerFactory.getLogger(StudentExplanationService.class);

    private final TextbookResourceProperties textbookResourceProperties;
    private final TextbookRetrievalService textbookRetrievalService;
    private final KnowledgeGraphSpineService knowledgeGraphSpineService;
    private final TeacherResourceBlockSearchService teacherResourceBlockSearchService;
    private final TeacherResourceStore teacherResourceStore;
    private final StudentExplanationAiCardService aiCardService;
    private final StudentExplanationImageStoreService imageStoreService;
    private final StudentExplanationHistoryStore historyStore;
    private final StudentMemoryRagService studentMemoryRagService;
    private final int conversationHistoryFetchLimit;

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
                historyStore, null, 200);
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
            StudentMemoryRagService studentMemoryRagService,
            @org.springframework.beans.factory.annotation.Value("${math-agent.student.explanation.conversation-history-fetch-limit:200}") int conversationHistoryFetchLimit) {
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
        this.studentMemoryRagService = studentMemoryRagService;
        this.conversationHistoryFetchLimit = Math.max(1, Math.min(conversationHistoryFetchLimit, 500));
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
        StudentExplanationRequest normalizedRequest = request == null
                ? new StudentExplanationRequest(null, null, null, null, null, null, null, null, null, null, null, false).normalize()
                : request.normalize();
        if (!normalizedRequest.hasProblemInput()) {
            throw new IllegalArgumentException("Student explanation requires questionText or image metadata");
        }
        normalizedRequest = normalizedRequest.withConversationId(conversationId(normalizedRequest));
        RequestSubject normalizedSubject = requireSubject(subject).normalize();
        StudentExplanationImageRecord imageRecord = resolveImageRecord(normalizedRequest, normalizedSubject);

        List<StudentExplanationResponse.WorkflowStage> stages = new ArrayList<>();
        List<StudentExplanationResponse.ExplanationCard> cards = new ArrayList<>();
        List<StudentExplanationResponse.ExplanationSource> sources = new ArrayList<>();
        StudentExplanationVisionService.VisionAnalysis visionAnalysis = StudentExplanationVisionService.VisionAnalysis.skipped("pending");
        StudentExplanationResponse.ImageUnderstanding imageUnderstanding = StudentExplanationResponse.ImageUnderstanding.none();
        StudentExplanationResponse.AiDraft aiDraft = StudentExplanationResponse.AiDraft.disabled("尚未开始生成讲解。");
        String conversationTitle = StudentExplanationConversationTitleSupport.resolve("", normalizedRequest.questionText(), null);
        String visibleQuestion = text(normalizedRequest.questionText());

        if (imageRecord != null) {
            /* The original image is the interactive visual context; do not block a user request on separate OCR. */
            visionAnalysis = StudentExplanationVisionService.VisionAnalysis.skipped("direct-image-context");
            upsertStage(stages, stageFrom(System.nanoTime(), "analyze_image", "题图上下文", "completed",
                    "原图已直接传入多模态讲解模型。"));
            emitProgress(listener, normalizedRequest, visibleQuestion, stages, cards, sources, imageRecord, visionAnalysis,
                    imageUnderstanding, aiDraft, conversationTitle, startedNanos, "题图已加入模型上下文。");
        }

        // The original upload is the authoritative visual context. OCR is useful for a text retrieval query, but a
        // failed transcription must not prevent the multimodal ReAct model from seeing and answering the image.
        String imageDataUrl = imageDataUrl(imageRecord);
        String query = queryText(normalizedRequest, visionAnalysis);
        if (query.isBlank() && imageRecord != null) {
            query = "请识别上传图片中的数学内容，并生成适合资料检索的具体关键词";
        }
        visibleQuestion = visibleQuestion(normalizedRequest, visionAnalysis);

        List<StudentExplanationHistorySummary> recentHistory = List.of();
        List<String> longTermMemories = List.of();
        if (Boolean.TRUE.equals(normalizedRequest.useConversationMemory())) {
            // Conversation and long-term memory are one user-controlled context action. Keeping both behind the same
            // switch avoids an invisible vector lookup on every fresh question and removes its avoidable latency.
            upsertStage(stages, runningStage("load_conversation_context", "读取学习记忆"));
            emitProgress(listener, normalizedRequest, visibleQuestion, stages, cards, sources, imageRecord, visionAnalysis,
                    imageUnderstanding, aiDraft, conversationTitle, startedNanos, "正在读取已关联的学习记忆。");
            recentHistory = loadRecentHistory(normalizedRequest, normalizedSubject, stages);
            longTermMemories = loadLongTermMemories(query, normalizedSubject, stages);
            emitProgress(listener, normalizedRequest, visibleQuestion, stages, cards, sources, imageRecord, visionAnalysis,
                    imageUnderstanding, aiDraft, conversationTitle, startedNanos, "学习记忆已读取。");
        }

        // Student explanations have one stable orchestration contract: the model decides whether a permitted
        // retrieval tool is useful. Deployment configuration cannot replace it with a different fixed pipeline.
        ReactEvidence reactEvidence = executeReactTools(
                normalizedRequest, normalizedSubject, query, stages, sources, listener, visibleQuestion, cards,
                imageRecord, imageDataUrl, visionAnalysis, imageUnderstanding, aiDraft, conversationTitle, startedNanos);
        List<KnowledgeGraphSpineResponse.Node> knowledgeNodes = reactEvidence.knowledgeNodes();
        List<TeacherResourceBlockSearchResponse.Hit> teacherHits = reactEvidence.teacherHits();

        StudentExplanationAiCardService.AiCardDraft aiCardDraft = reactEvidence.finalDraft();
        if (aiCardDraft == null) {
            // A retrieval plan needs one post-observation composition call. A self-contained question already carries
            // its validated cards in the first ReAct turn and must not pay for a redundant second provider request.
            upsertStage(stages, runningStage("ai_compose_cards", "生成讲解"));
            emitProgress(listener, normalizedRequest, visibleQuestion, stages, cards, sources, imageRecord, visionAnalysis,
                    imageUnderstanding, aiDraft, conversationTitle, startedNanos, "模型正在根据检索结果生成讲解。");
            aiCardDraft = aiCardService.generate(
                    normalizedRequest,
                    aiContextQuery(query, knowledgeNodes, teacherHits),
                    imageStatus(normalizedRequest, imageRecord, visionAnalysis),
                    sources,
                    recentHistory,
                    longTermMemories,
                    stages,
                    imageDataUrl,
                    listener::onAiDelta);
        }
        aiDraft = aiCardDraft.aiDraft();
        conversationTitle = StudentExplanationConversationTitleSupport.resolve(
                aiCardDraft.conversationTitle(),
                visibleQuestion,
                null);
        // Preserve the live model's section count, labels, and order. Cards are a transport format, not a lesson template.
        cards.addAll(aiCardDraft.cards());
        emitProgress(listener, normalizedRequest, visibleQuestion, stages, cards, sources, imageRecord, visionAnalysis,
                imageUnderstanding, aiDraft, conversationTitle, startedNanos, "讲解卡片已生成，正在整理展示。");

        upsertStage(stages, runningStage("assemble_cards", "整理卡片"));
        emitProgress(listener, normalizedRequest, visibleQuestion, stages, cards, sources, imageRecord, visionAnalysis,
                imageUnderstanding, aiDraft, conversationTitle, startedNanos, "正在整理讲解卡片。");
        upsertStage(stages, stage("assemble_cards", "整理卡片", "completed",
                "已使用真实模型输出并解析为讲解卡片，同时保留原题、知识点、方法和资源链接。", startedNanos));
        emitProgress(listener, normalizedRequest, visibleQuestion, stages, cards, sources, imageRecord, visionAnalysis,
                imageUnderstanding, aiDraft, conversationTitle, startedNanos, "讲解卡片已整理完成。");

        String explanationId = UUID.randomUUID().toString();
        upsertStage(stages, runningStage("persist_history", "保存记录"));
        emitProgress(listener, normalizedRequest, visibleQuestion, stages, cards, sources, imageRecord, visionAnalysis,
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
                imageStatus(normalizedRequest, imageRecord, visionAnalysis),
                imageUnderstanding,
                GENERATED_BY,
                aiDraft,
                List.copyOf(stages),
                List.copyOf(cards),
                List.copyOf(sources),
                elapsedMs(startedNanos));
        historyStore.save(normalizedRequest, normalizedSubject, imageRecord, response);
        indexLongTermMemory(normalizedSubject, response, stages);
        emitProgress(listener, normalizedRequest, visibleQuestion, stages, cards, sources, imageRecord, visionAnalysis,
                imageUnderstanding, aiDraft, conversationTitle, startedNanos, "本轮讲解已完成。");
        listener.onCompleted(response);
        return response;
    }

    /**
     * 有界 ReAct 回合：模型一次规划本题所需的全部只读工具；后端实际执行工具，
     * 从而减少逐工具模型握手，同时始终在服务端保留身份、租户、请求开关和教师私有资料权限控制。
     *
     * <p>工具计划会按服务端白名单校验并去重，因此每项只读工具在单次讲解中最多调用一次。</p>
     */
    private ReactEvidence executeReactTools(
            StudentExplanationRequest request, RequestSubject subject, String query,
            List<StudentExplanationResponse.WorkflowStage> stages,
            List<StudentExplanationResponse.ExplanationSource> sources,
            StudentExplanationProgressListener listener, String visibleQuestion,
            List<StudentExplanationResponse.ExplanationCard> cards, StudentExplanationImageRecord imageRecord,
            String imageDataUrl,
            StudentExplanationVisionService.VisionAnalysis visionAnalysis,
            StudentExplanationResponse.ImageUnderstanding imageUnderstanding,
            StudentExplanationResponse.AiDraft aiDraft, String conversationTitle, long startedNanos) {
        Set<String> available = availableReactTools(request, subject);
        List<KnowledgeGraphSpineResponse.Node> knowledgeNodes = new ArrayList<>();
        List<TeacherResourceBlockSearchResponse.Hit> teacherHits = new ArrayList<>();
        long planStarted = System.nanoTime();
        upsertStage(stages, runningStage("react_plan", "规划讲解"));
        emitProgress(listener, request, visibleQuestion, stages, cards, sources, imageRecord, visionAnalysis,
                imageUnderstanding, aiDraft, conversationTitle, startedNanos, "正在规划本题所需的资料与讲解。");
        // Decision：模型只看到后端按请求和身份生成的工具白名单，无法扩大自己的资料权限。
        StudentExplanationAiCardService.ReactDecision decision = aiCardService.nextReactDecision(
                query, sources, List.of(), available, imageDataUrl, listener::onAiDelta);
        if ("final".equals(decision.kind())) {
            upsertStage(stages, stageFrom(planStarted, "react_plan", "规划并生成讲解", "completed",
                    "题目信息完整，已在一次模型调用中完成规划和讲解。"));
            emitProgress(listener, request, visibleQuestion, stages, cards, sources, imageRecord, visionAnalysis,
                    imageUnderstanding, decision.finalDraft().aiDraft(), conversationTitle, startedNanos,
                    "讲解已生成，正在整理展示。");
            return new ReactEvidence(List.of(), List.of(), decision.finalDraft());
        }
        if (!"action".equals(decision.kind())) {
            throw new IllegalStateException("ReAct 决策失败：" + decision.message());
        }
        upsertStage(stages, stageFrom(planStarted, "react_plan", "规划资料检索", "completed",
                "已一次规划 " + decision.tools().size() + " 项只读资料检索：" + String.join("、", decision.tools()) + "。"));
        String retrievalQuery = text(decision.searchQuery()).isBlank() ? query : decision.searchQuery().strip();
        // Action：工具计划已在决策解析时按白名单校验和去重，下面只负责逐项执行真实检索。
        for (String tool : decision.tools()) {
            if ("search_textbook".equals(tool)) {
                upsertStage(stages, runningToolStage("search_textbook", "检索教材", retrievalQuery, request.maxTextbookHits()));
                emitProgress(listener, request, visibleQuestion, stages, cards, sources, imageRecord, visionAnalysis,
                        imageUnderstanding, aiDraft, conversationTitle, startedNanos, "正在执行模型选择的教材检索。");
                List<TextbookSearchHit> hits = searchTextbooks(request, subject, retrievalQuery, stages);
                hits.stream().map(StudentExplanationService::textbookSource).forEach(sources::add);
            } else if ("match_knowledge_graph".equals(tool)) {
                upsertStage(stages, runningToolStage(
                        "match_knowledge_graph", "匹配知识点", retrievalQuery, MAX_KNOWLEDGE_GRAPH_MATCHES));
                emitProgress(listener, request, visibleQuestion, stages, cards, sources, imageRecord, visionAnalysis,
                        imageUnderstanding, aiDraft, conversationTitle, startedNanos, "正在执行模型选择的知识点匹配。");
                knowledgeNodes.addAll(matchKnowledgeGraph(request, subject, retrievalQuery, stages));
                knowledgeNodes.stream().map(StudentExplanationService::knowledgeSource).forEach(sources::add);
            } else if ("search_teacher_resources".equals(tool)) {
                upsertStage(stages, runningToolStage(
                        "search_teacher_resources", "检索教师资料", retrievalQuery, request.maxTeacherResourceHits()));
                emitProgress(listener, request, visibleQuestion, stages, cards, sources, imageRecord, visionAnalysis,
                        imageUnderstanding, aiDraft, conversationTitle, startedNanos, "正在执行模型选择的教师资料检索。");
                teacherHits.addAll(searchTeacherResources(request, subject, retrievalQuery, stages));
                Map<String, TeacherResourceDocumentResponse> documentsById = teacherDocumentsById(subject.tenantId(), teacherHits);
                teacherHits.stream().map(hit -> teacherSource(hit, documentsById.get(hit.documentId()))).forEach(sources::add);
            }
        }
        return new ReactEvidence(List.copyOf(knowledgeNodes), List.copyOf(teacherHits), null);
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
     * Loads recent durable conversation context scoped by backend identity.
     */
    private List<StudentExplanationHistorySummary> loadRecentHistory(
            StudentExplanationRequest request,
            RequestSubject subject,
            List<StudentExplanationResponse.WorkflowStage> stages) {
        long stageStarted = System.nanoTime();
        if (!Boolean.TRUE.equals(request.useConversationMemory())) {
            // Fresh explanations must not silently inherit previous turns, even inside the currently open conversation.
            upsertStage(stages, stageFrom(stageStarted, "load_conversation_context", "读取上下文", "skipped",
                    "当前会话未启用上下文关联。"));
            return List.of();
        }
        try {
            List<StudentExplanationHistorySummary> history = historyStore.findRecent(
                    subject.tenantId(),
                    subject.subjectType(),
                    subject.subjectId(),
                    request.conversationId(),
                    conversationHistoryFetchLimit);
            upsertStage(stages, stageFrom(stageStarted, "load_conversation_context", "读取上下文", "completed",
                    "已读取 " + history.size() + " 条最近会话。"));
            return history;
        } catch (RuntimeException e) {
            upsertStage(stages, stageFrom(stageStarted, "load_conversation_context", "读取上下文", "failed",
                    e.getClass().getSimpleName()));
            throw e;
        }
    }

    private List<String> loadLongTermMemories(
            String query,
            RequestSubject subject,
            List<StudentExplanationResponse.WorkflowStage> stages) {
        long stageStarted = System.nanoTime();
        if (studentMemoryRagService == null || studentId(subject) == null) {
            upsertStage(stages, stageFrom(stageStarted, "retrieve_long_term_memory", "检索长期记忆", "skipped",
                    "当前身份不使用学生长期记忆。"));
            return List.of();
        }
        List<String> memories = studentMemoryRagService.retrieve(subject, query);
        upsertStage(stages, stageFrom(stageStarted, "retrieve_long_term_memory", "检索长期记忆", "completed",
                memories.isEmpty() ? "未检索到相关长期记忆。" : "已检索 " + memories.size() + " 条相关长期记忆。"));
        return memories;
    }

    private void indexLongTermMemory(
            RequestSubject subject,
            StudentExplanationResponse response,
            List<StudentExplanationResponse.WorkflowStage> stages) {
        if (studentMemoryRagService == null || studentId(subject) == null) {
            return;
        }
        studentMemoryRagService.index(subject, response);
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
            upsertStage(stages, stageFrom(stageStarted, "search_textbook", "检索教材", "failed", e.getMessage()));
            throw e;
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
            List<KnowledgeGraphSpineResponse.Node> nodes = spine.nodes().stream()
                    .filter(node -> matchesKnowledgeTopic(query, node))
                    .map(node -> new NodeMatch(node, knowledgeScore(query, node), knowledgeMatchReason(query, node)))
                    .filter(match -> qualifiesNodeMatch(match.node(), match.score(), match.reason()))
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
            upsertStage(stages, stageFrom(stageStarted, "search_teacher_resources", "检索教师资料", "failed",
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
        return new StudentExplanationResponse.ExplanationSource(
                "teacher_resource",
                hit.documentTitle(),
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

    /** Keeps ordinary AI retrieval on the concrete requested branch before score-based ranking. */
    private static boolean matchesConcreteTopic(String query, String title, String snippet) {
        String normalizedQuery = compactForMatch(query);
        String text = compactForMatch(text(title) + " " + text(snippet));
        if (normalizedQuery.contains("二次函数")) {
            boolean quadratic = text.contains("二次函数") || text.contains("x^2") || text.contains("x²")
                    || text.contains("x2");
            return quadratic && !text.contains("x^3") && !text.contains("x³") && !text.contains("x3")
                    && !text.contains("双曲线") && !text.contains("椭圆") && !text.contains("圆锥曲线")
                    && !text.contains("抛物线");
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
     * Builds the retrieval query from user text plus real vision text when available.
     */
    private static String queryText(
            StudentExplanationRequest request,
            StudentExplanationVisionService.VisionAnalysis visionAnalysis) {
        String typed = text(request.questionText());
        String visual = text(visionAnalysis.problemText());
        if (!typed.isBlank() && !visual.isBlank()) {
            return typed + "\n" + visual;
        }
        return !typed.isBlank() ? typed : visual;
    }

    /**
     * Determines the visible problem text shown to the user and stored in history.
     */
    private static String visibleQuestion(
            StudentExplanationRequest request,
            StudentExplanationVisionService.VisionAnalysis visionAnalysis) {
        String value = text(request.questionText()).strip();
        if (!value.isBlank()) {
            return value;
        }
        String visual = text(visionAnalysis.problemText()).strip();
        return visual.isBlank() ? "图片讲题" : visual;
    }

    /** Materializes one owner-validated upload as ephemeral model context without exposing its server path. */
    private static String imageDataUrl(StudentExplanationImageRecord imageRecord) {
        if (imageRecord == null) {
            return "";
        }
        try {
            byte[] bytes = Files.readAllBytes(imageRecord.localPath());
            return "data:" + imageRecord.contentType() + ";base64," + Base64.getEncoder().encodeToString(bytes);
        } catch (IOException exception) {
            throw new IllegalStateException("Uploaded image is no longer readable", exception);
        }
    }

    /**
     * Returns the image handling status without over-claiming OCR.
     */
    private static String imageStatus(
            StudentExplanationRequest request,
            StudentExplanationImageRecord imageRecord,
            StudentExplanationVisionService.VisionAnalysis visionAnalysis) {
        if ("direct-image-context".equals(visionAnalysis.message())) {
            return "image_direct_context";
        }
        if (visionAnalysis.succeeded()) {
            return "image_understood_by_vision";
        }
        if (visionAnalysis.enabled() && imageRecord != null) {
            return "image_vision_failed";
        }
        if (imageRecord != null) {
            return "image_uploaded_without_vision_analysis";
        }
        if (request.imageUploadId() == null && request.imageFileName() == null) {
            return "none";
        }
        return "image_received_without_vision_analysis";
    }

    /**
     * Converts image analysis metadata into response metadata.
     */
    private static StudentExplanationResponse.ImageUnderstanding imageUnderstanding(
            StudentExplanationVisionService.VisionAnalysis analysis) {
        if (analysis == null) {
            return StudentExplanationResponse.ImageUnderstanding.none();
        }
        return new StudentExplanationResponse.ImageUnderstanding(
                analysis.enabled(),
                analysis.succeeded(),
                text(analysis.providerName()),
                text(analysis.modelCode()),
                text(analysis.problemText()),
                analysis.confidence(),
                analysis.promptTokens(),
                analysis.completionTokens(),
                analysis.totalTokens(),
                text(analysis.message()));
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
            steps.add("先识别题图");
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
     * Identity, tenant IDs, capability tokens, and raw provider prompts intentionally never cross this boundary.
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
        for (int index = 0; index < stages.size(); index++) {
            if (text(stages.get(index).stageKey()).equals(nextStage.stageKey())) {
                stages.set(index, nextStage);
                return;
            }
        }
        stages.add(nextStage);
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
            StudentExplanationVisionService.VisionAnalysis visionAnalysis,
            StudentExplanationResponse.ImageUnderstanding imageUnderstanding,
            StudentExplanationResponse.AiDraft aiDraft,
            String conversationTitle,
            long startedNanos,
            String message) {
        listener.onProgress(new StudentExplanationStreamProgress(
                request.conversationId(),
                conversationTitle,
                visibleQuestion,
                imageStatus(request, imageRecord, visionAnalysis),
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
