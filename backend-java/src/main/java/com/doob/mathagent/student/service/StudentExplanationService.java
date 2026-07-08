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
import com.doob.mathagent.teacher.service.TeacherResourceBlockSearchService;
import com.doob.mathagent.teacher.vo.TeacherResourceBlockSearchResponse;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Orchestrates the student-side explanation DAG from problem input to evidence-backed cards.
 */
@Service
public class StudentExplanationService {

    private static final String ENDPOINT = "/api/students/explanations";
    private static final String GENERATED_BY = "student_explanation_card_orchestrator_v0.1";

    private final TextbookResourceProperties textbookResourceProperties;
    private final TextbookRetrievalService textbookRetrievalService;
    private final KnowledgeGraphSpineService knowledgeGraphSpineService;
    private final TeacherResourceBlockSearchService teacherResourceBlockSearchService;
    private final StudentExplanationAiCardService aiCardService;
    private final StudentExplanationImageStoreService imageStoreService;
    private final StudentExplanationVisionService visionService;
    private final StudentExplanationHistoryStore historyStore;

    /**
     * Creates the student explanation orchestrator.
     *
     * @param textbookResourceProperties configured textbook processed root
     * @param textbookRetrievalService textbook BM25 retrieval service
     * @param knowledgeGraphSpineService curated graph service
     * @param teacherResourceBlockSearchService teacher resource block search service
     */
    @Autowired
    public StudentExplanationService(
            TextbookResourceProperties textbookResourceProperties,
            TextbookRetrievalService textbookRetrievalService,
            KnowledgeGraphSpineService knowledgeGraphSpineService,
            TeacherResourceBlockSearchService teacherResourceBlockSearchService,
            StudentExplanationAiCardService aiCardService,
            StudentExplanationImageStoreService imageStoreService,
            StudentExplanationVisionService visionService,
            StudentExplanationHistoryStore historyStore) {
        this.textbookResourceProperties = Objects.requireNonNull(
                textbookResourceProperties, "textbookResourceProperties is required");
        this.textbookRetrievalService = Objects.requireNonNull(
                textbookRetrievalService, "textbookRetrievalService is required");
        this.knowledgeGraphSpineService = Objects.requireNonNull(
                knowledgeGraphSpineService, "knowledgeGraphSpineService is required");
        this.teacherResourceBlockSearchService = Objects.requireNonNull(
                teacherResourceBlockSearchService, "teacherResourceBlockSearchService is required");
        this.aiCardService = Objects.requireNonNull(aiCardService, "aiCardService is required");
        this.imageStoreService = Objects.requireNonNull(imageStoreService, "imageStoreService is required");
        this.visionService = Objects.requireNonNull(visionService, "visionService is required");
        this.historyStore = Objects.requireNonNull(historyStore, "historyStore is required");
    }

    /**
     * Runs the explanation DAG with backend-resolved identity and scoped retrieval toggles.
     *
     * @param request user problem and retrieval preferences
     * @param subject backend-resolved request subject
     * @return card-based explanation response
     */
    public StudentExplanationResponse explain(StudentExplanationRequest request, RequestSubject subject) {
        long startedNanos = System.nanoTime();
        StudentExplanationRequest normalizedRequest = request == null
                ? new StudentExplanationRequest(null, null, null, null, null, null, null, null, null, null, null).normalize()
                : request.normalize();
        if (!normalizedRequest.hasProblemInput()) {
            throw new IllegalArgumentException("Student explanation requires questionText or image metadata");
        }
        normalizedRequest = normalizedRequest.withConversationId(conversationId(normalizedRequest));
        RequestSubject normalizedSubject = requireSubject(subject).normalize();
        StudentExplanationImageRecord imageRecord = resolveImageRecord(normalizedRequest, normalizedSubject);
        List<StudentExplanationResponse.WorkflowStage> stages = new ArrayList<>();
        List<StudentExplanationResponse.ExplanationSource> sources = new ArrayList<>();
        StudentExplanationVisionService.VisionAnalysis visionAnalysis = analyzeImage(imageRecord, stages);
        requireRealTextForImageOnlyRequest(normalizedRequest, imageRecord, visionAnalysis);
        String query = queryText(normalizedRequest, imageRecord, visionAnalysis);
        List<StudentExplanationHistorySummary> recentHistory =
                loadRecentHistory(normalizedRequest, normalizedSubject, stages);

        stages.add(stage("understand_problem", "理解题意", "completed",
                "已使用题目文本或真实视觉识别结果规划检索，不把图片文件名当作题目内容。",
                startedNanos));
        List<TextbookSearchHit> textbookHits = searchTextbooks(normalizedRequest, normalizedSubject, query, stages);
        textbookHits.stream().map(StudentExplanationService::textbookSource).forEach(sources::add);

        List<KnowledgeGraphSpineResponse.Node> knowledgeNodes =
                matchKnowledgeGraph(normalizedRequest, normalizedSubject, query, stages);
        knowledgeNodes.stream().map(StudentExplanationService::knowledgeSource).forEach(sources::add);

        List<TeacherResourceBlockSearchResponse.Hit> teacherHits =
                searchTeacherResources(normalizedRequest, normalizedSubject, query, stages);
        teacherHits.stream().map(StudentExplanationService::teacherSource).forEach(sources::add);

        StudentExplanationAiCardService.AiCardDraft aiCardDraft = aiCardService.generate(
                normalizedRequest,
                query,
                imageStatus(normalizedRequest, imageRecord, visionAnalysis),
                sources,
                recentHistory,
                stages);
        stages.add(stage("assemble_cards", "整理讲解卡片", "completed",
                "已使用真实模型输出并解析为讲解卡片。", startedNanos));
        String explanationId = UUID.randomUUID().toString();
        stages.add(stage("persist_history", "保存讲解记录", historyStore.durable() ? "completed" : "skipped",
                historyStore.durable()
                        ? "已写入 MySQL 历史记录，后续可恢复会话。"
                        : "当前本地运行未启用数据库历史记录。",
                startedNanos));
        StudentExplanationResponse response = new StudentExplanationResponse(
                explanationId,
                normalizedRequest.conversationId(),
                normalizedSubject.tenantId(),
                studentId(normalizedSubject),
                normalizedSubject.subjectType(),
                normalizedRequest.questionText(),
                imageStatus(normalizedRequest, imageRecord, visionAnalysis),
                imageUnderstanding(visionAnalysis),
                GENERATED_BY,
                aiCardDraft.aiDraft(),
                List.copyOf(stages),
                aiCardDraft.cards(),
                List.copyOf(sources),
                elapsedMs(startedNanos));
        historyStore.save(normalizedRequest, normalizedSubject, imageRecord, response);
        return response;
    }

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
        try {
            List<StudentExplanationHistorySummary> history = historyStore.findRecent(
                    subject.tenantId(),
                    subject.subjectType(),
                    subject.subjectId(),
                    request.conversationId(),
                    6);
            stages.add(stageFrom(stageStarted, "load_conversation_context", "读取上下文", "completed",
                    "已读取 " + history.size() + " 条最近会话。"));
            return history;
        } catch (RuntimeException e) {
            stages.add(stageFrom(stageStarted, "load_conversation_context", "读取上下文", "failed",
                    e.getClass().getSimpleName()));
            throw e;
        }
    }

    /**
     * Returns a backend conversation id, preserving a valid caller-supplied one.
     */
    private static String conversationId(StudentExplanationRequest request) {
        String value = safe(request.conversationId()).strip();
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
     * Runs real image understanding for an owner-validated temporary upload.
     */
    private StudentExplanationVisionService.VisionAnalysis analyzeImage(
            StudentExplanationImageRecord imageRecord,
            List<StudentExplanationResponse.WorkflowStage> stages) {
        long stageStarted = System.nanoTime();
        if (imageRecord == null) {
            stages.add(stageFrom(stageStarted, "analyze_image", "识别题图", "skipped", "未上传题图。"));
            return StudentExplanationVisionService.VisionAnalysis.skipped("no-image");
        }
        StudentExplanationVisionService.VisionAnalysis analysis = visionService.analyze(imageRecord);
        stages.add(stageFrom(stageStarted, "analyze_image", "识别题图",
                analysis.succeeded() ? "completed" : analysis.enabled() ? "failed" : "skipped",
                analysis.succeeded()
                        ? analysis.providerName() + "/" + analysis.modelCode() + " tokens=" + analysis.totalTokens()
                        : analysis.message()));
        return analysis;
    }

    /**
     * Searches configured textbook resources when the student allows textbook retrieval.
     */
    private List<TextbookSearchHit> searchTextbooks(
            StudentExplanationRequest request,
            RequestSubject subject,
            String query,
            List<StudentExplanationResponse.WorkflowStage> stages) {
        long stageStarted = System.nanoTime();
        if (!Boolean.TRUE.equals(request.searchTextbook())) {
            stages.add(stageFrom(stageStarted, "search_textbook", "检索教材", "skipped",
                    "本轮未启用教材检索。"));
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
            stages.add(stageFrom(stageStarted, "search_textbook", "检索教材", "completed",
                    "命中 " + response.total() + " 条教材证据。"));
            return response.hits();
        } catch (RuntimeException e) {
            stages.add(stageFrom(stageStarted, "search_textbook", "检索教材", "failed", e.getMessage()));
            throw e;
        }
    }

    /**
     * Matches the curated display spine without using the noisy OCR-level graph.
     */
    private List<KnowledgeGraphSpineResponse.Node> matchKnowledgeGraph(
            StudentExplanationRequest request,
            RequestSubject subject,
            String query,
            List<StudentExplanationResponse.WorkflowStage> stages) {
        long stageStarted = System.nanoTime();
        if (!Boolean.TRUE.equals(request.searchKnowledgeGraph())) {
            stages.add(stageFrom(stageStarted, "match_knowledge_graph", "匹配知识点", "skipped",
                    "本轮未启用知识点匹配。"));
            return List.of();
        }
        try {
            KnowledgeGraphSpineResponse spine = knowledgeGraphSpineService.displaySpine(
                    subject.tenantId(),
                    subject.subjectType(),
                    subject.subjectId());
            List<KnowledgeGraphSpineResponse.Node> nodes = spine.nodes().stream()
                    .map(node -> new NodeMatch(node, knowledgeScore(query, node)))
                    .filter(match -> match.score() > 0)
                    .sorted(Comparator.comparingInt(NodeMatch::score).reversed()
                            .thenComparing(match -> match.node().label()))
                    .limit(5)
                    .map(NodeMatch::node)
                    .toList();
            stages.add(stageFrom(stageStarted, "match_knowledge_graph", "匹配知识点", "completed",
                    "命中 " + nodes.size() + " 个主干知识点。"));
            return nodes;
        } catch (RuntimeException e) {
            stages.add(stageFrom(stageStarted, "match_knowledge_graph", "匹配知识点", "failed", e.getMessage()));
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
            stages.add(stageFrom(stageStarted, "search_teacher_resources", "检索教师资料", "skipped",
                    "本轮未启用教师资料检索。"));
            return List.of();
        }
        if (!isTeacherOrAdmin(subject.subjectType())) {
            stages.add(stageFrom(stageStarted, "search_teacher_resources", "检索教师资料", "skipped",
                    "学生身份不能读取教师私有资料。"));
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
            stages.add(stageFrom(stageStarted, "search_teacher_resources", "检索教师资料", "completed",
                    "命中 " + response.hitCount() + " 条教师资料。"));
            return response.hits();
        } catch (RuntimeException e) {
            stages.add(stageFrom(stageStarted, "search_teacher_resources", "检索教师资料", "failed", e.getMessage()));
            return List.of();
        }
    }

    /**
     * Converts a textbook hit to a stable source entry.
     */
    private static StudentExplanationResponse.ExplanationSource textbookSource(TextbookSearchHit hit) {
        return new StudentExplanationResponse.ExplanationSource(
                "textbook",
                hit.bookName() + " p." + hit.pageNo(),
                textbookUri(hit),
                "PUBLIC_TEXTBOOK",
                compact(hit.textSnippet()),
                hit.score());
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
                safe(node.chapterPath()),
                1.0);
    }

    /**
     * Converts a teacher resource hit to a stable source entry.
     */
    private static StudentExplanationResponse.ExplanationSource teacherSource(TeacherResourceBlockSearchResponse.Hit hit) {
        return new StudentExplanationResponse.ExplanationSource(
                "teacher_resource",
                hit.documentTitle(),
                "teacher-resource://" + hit.documentId() + "/block/" + hit.blockId(),
                hit.permissionScope(),
                compact(hit.snippet()),
                hit.score());
    }

    /**
     * Builds a stable textbook source URI.
     */
    private static String textbookUri(TextbookSearchHit hit) {
        return "textbook://" + hit.docId() + "/page/" + hit.pageNo() + "#chunk=" + hit.chunkId();
    }

    /**
     * Builds a stable curated graph source URI.
     */
    private static String knowledgeUri(KnowledgeGraphSpineResponse.Node node) {
        return "math-agent://knowledge/graph-spine/v0.1#node=" + node.id();
    }

    /**
     * Scores a graph node against the query using exact and character-overlap signals.
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

    /**
     * Builds a query from text first, then image metadata when text is absent.
     */
    private static String queryText(
            StudentExplanationRequest request,
            StudentExplanationImageRecord imageRecord,
            StudentExplanationVisionService.VisionAnalysis visionAnalysis) {
        if (request.questionText() != null) {
            return String.join(" ", request.questionText(), safe(visionAnalysis.problemText())).strip();
        }
        if (visionAnalysis.succeeded() && !safe(visionAnalysis.problemText()).isBlank()) {
            return visionAnalysis.problemText();
        }
        return "";
    }

    /**
     * Prevents image metadata or filenames from being used as a fake math problem.
     */
    private static void requireRealTextForImageOnlyRequest(
            StudentExplanationRequest request,
            StudentExplanationImageRecord imageRecord,
            StudentExplanationVisionService.VisionAnalysis visionAnalysis) {
        if (request.questionText() != null) {
            return;
        }
        boolean hasImageReference = imageRecord != null
                || request.imageUploadId() != null
                || request.imageFileName() != null
                || request.imageContentType() != null;
        if (hasImageReference && !visionAnalysis.succeeded()) {
            throw new IllegalArgumentException(
                    "Image-only explanation requires successful real vision analysis or explicit questionText");
        }
    }

    /**
     * Returns the image handling status without claiming OCR work.
     */
    private static String imageStatus(
            StudentExplanationRequest request,
            StudentExplanationImageRecord imageRecord,
            StudentExplanationVisionService.VisionAnalysis visionAnalysis) {
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
                safe(analysis.providerName()),
                safe(analysis.modelCode()),
                safe(analysis.problemText()),
                analysis.confidence(),
                analysis.promptTokens(),
                analysis.completionTokens(),
                analysis.totalTokens(),
                safe(analysis.message()));
    }

    /**
     * Returns the student id visible in response from backend identity.
     */
    private static String studentId(RequestSubject subject) {
        return "student".equals(subject.subjectType()) ? subject.subjectId() : null;
    }

    /**
     * Returns whether the backend subject may use teacher resources.
     */
    private static boolean isTeacherOrAdmin(String role) {
        return "teacher".equals(role) || "admin".equals(role);
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
        return new StudentExplanationResponse.WorkflowStage(key, title, status, safe(detail), elapsedMs(stageStartedNanos));
    }

    /**
     * Returns elapsed milliseconds from a nanoTime start.
     */
    private static long elapsedMs(long startedNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos);
    }

    /**
     * Compacts evidence text for card display.
     */
    private static String compact(String value) {
        String stripped = safe(value).replaceAll("\\s+", " ").strip();
        return stripped.length() <= 180 ? stripped : stripped.substring(0, 180);
    }

    /**
     * Compacts text for rough matching.
     */
    private static String compactForMatch(String value) {
        return safe(value).replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
    }

    /**
     * Returns non-null text.
     */
    private static String safe(String value) {
        return value == null ? "" : value;
    }


    /**
     * Internal graph node match score.
     */
    private record NodeMatch(KnowledgeGraphSpineResponse.Node node, int score) {
    }
}
