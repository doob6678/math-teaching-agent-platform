package com.doob.mathagent.agent.service;

import com.doob.mathagent.infrastructure.ai.AiProviderCatalog;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.springframework.core.env.Environment;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * 非讲义 AI workload 的 Java 到 Python 内部协议客户端。
 *
 * <p>Java 只传递已授权的输入、证据和 provider/model allow-list；Python 独占 provider 调用、重试和 usage 记账。</p>
 */
@Service
public class PythonMigratedWorkloadClient {

    private static final long MIN_TIMEOUT_MS = 1_000L;
    private static final long DEFAULT_TIMEOUT_MS = 60_000L;
    /**
     * 动画讲题渲染是分钟级长任务（实测 5~25 分钟），必须独立于交互式 120 秒 workload 客户端；
     * 冻结契约 2026-09-09 要求 HTTP 超时可配且默认 ≥1800 秒，由 Agent Worker 租约心跳在等待期续租。
     */
    private static final long ANIMATED_LESSON_DEFAULT_TIMEOUT_MS = 1_800_000L;
    private static final int MAX_FALLBACKS = 3;
    /** 思考轨迹持久化上限：覆盖长推理链（约 6 万字符 ≈ 2-3 万汉字），超出截断防止 ai_draft_json 无界膨胀。 */
    private static final int REASONING_TRACE_MAX_CHARS = 65_536;
    /**
     * 学生讲解上下文预算的 wire 钳位，与 Python v2 契约 StudentExplanationGraphLimits 的上限一致。
     * 2026-09-02 起压缩触发为 130k 级（兜底而非常态，保护 provider 前缀缓存），131072 对齐 128K
     * 上下文模型档位；超出契约会被 worker 校验拒绝，两侧必须同步调整。
     */
    private static final int CONTEXT_MAX_INPUT_TOKENS_CAP = 131_072;
    private static final int CONTEXT_SUMMARY_TRIGGER_TOKENS_CAP = 130_000;

    private final Environment environment;
    private final RestClient client;
    /** 分钟级长任务（动画讲题渲染）专用；与交互式 workload 客户端共享 base-url，但读超时独立可配。 */
    private final RestClient longTaskClient;
    private final AiProviderCatalog providerCatalog;
    private final ProviderRouteGrantSigner routeGrantSigner;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public PythonMigratedWorkloadClient(
            Environment environment,
            AiProviderCatalog providerCatalog,
            ProviderRouteGrantSigner routeGrantSigner) {
        this.environment = environment;
        this.providerCatalog = providerCatalog;
        this.routeGrantSigner = routeGrantSigner;
        String baseUrl = environment.getProperty("math-agent.python-agent.base-url", "http://ai-worker:8091");
        Long connectTimeoutMs = environment.getProperty(
                "math-agent.python-agent.connect-timeout-ms", Long.class, 5_000L);
        long timeoutMs = Math.max(MIN_TIMEOUT_MS, environment.getProperty(
                "math-agent.python-agent.timeout-ms", Long.class, DEFAULT_TIMEOUT_MS));
        this.client = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(buildRequestFactory(timeoutMs, connectTimeoutMs))
                .build();
        long animatedLessonTimeoutMs = Math.max(MIN_TIMEOUT_MS, environment.getProperty(
                "math-agent.animated-lesson.timeout-ms", Long.class, ANIMATED_LESSON_DEFAULT_TIMEOUT_MS));
        this.longTaskClient = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(buildRequestFactory(animatedLessonTimeoutMs, connectTimeoutMs))
                .build();
    }

    /** JDK HttpClient 请求工厂：连接超时收敛到 [read, 5s] 区间内，读超时即 worker 等待上限。 */
    private static JdkClientHttpRequestFactory buildRequestFactory(long readTimeoutMs, long connectTimeoutMs) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(Math.min(readTimeoutMs, connectTimeoutMs)))
                .version(HttpClient.Version.HTTP_1_1)
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(Duration.ofMillis(readTimeoutMs));
        return requestFactory;
    }

    /** 调用学习意图分类 endpoint，并校验 Python 返回的有限字段。 */
    public IntentResult recognizeIntent(String runId, String message, List<KnowledgePoint> knowledgePoints) {
        JsonNode root = post("/v1/learning-intents/sync", runId, Map.of(
                "runId", runId,
                "message", bounded(message, 4_000),
                "knowledgePoints", knowledgePoints == null ? List.of() : knowledgePoints.stream()
                        .map(item -> Map.of("knowledgePointId", bounded(item.knowledgePointId(), 160),
                                "knowledgePointName", bounded(item.knowledgePointName(), 240)))
                        .toList(),
                "providerRoute", providerRoute(runId, "learning_intent")));
        requireCompleted(root, "learning intent");
        return new IntentResult(
                bounded(root.path("intentCode").asText("UNKNOWN"), 64),
                boundedConfidence(root.path("confidence").asDouble(0.0d)),
                bounded(root.path("knowledgePointId").asText(), 160),
                bounded(root.path("providerName").asText(), 64),
                bounded(root.path("modelCode").asText(), 160));
    }

    /** 调用受限图片转写 endpoint；调用方必须先完成文件授权、MIME 和大小校验。 */
    public TranscriptionResult transcribeImage(String runId, String mimeType, String imageDataUrl) {
        JsonNode root = post("/v1/image-transcriptions/sync", runId, Map.of(
                "runId", runId,
                "mimeType", bounded(mimeType, 80),
                "imageDataUrl", imageDataUrl == null ? "" : imageDataUrl,
                "providerRoute", providerRoute(runId, "image_transcription", true)));
        boolean completed = "COMPLETED".equals(root.path("status").asText());
        return new TranscriptionResult(
                completed,
                bounded(root.path("problemText").asText(), 16_000),
                boundedConfidence(root.path("confidence").asDouble(0.0d)),
                bounded(root.path("providerName").asText(), 64),
                bounded(root.path("modelCode").asText(), 160));
    }

    /** 调用 Python ReAct 决策；工具和查询会由 Java 调用方再次按本轮权限裁剪。 */
    public ExplanationDecision decideStudentExplanation(
            String runId,
            String problem,
            List<ExplanationEvidence> evidence,
            List<String> availableTools,
            List<String> observations,
            String imageDataUrl) {
        JsonNode root = post("/v1/student-explanations/sync", runId, Map.of(
                "runId", runId,
                "mode", "react",
                "problem", bounded(problem, 8_000),
                "evidence", explanationEvidence(evidence),
                "availableTools", availableTools == null ? List.of() : availableTools.stream().map(item -> bounded(item, 80)).toList(),
                "observations", observations == null ? List.of() : observations.stream().map(item -> bounded(item, 800)).toList(),
                "imageDataUrl", imageDataUrl == null ? "" : imageDataUrl,
                "providerRoute", providerRoute(runId, "student_explanation", hasImage(imageDataUrl))));
        requireCompleted(root, "student explanation decision");
        return explanationDecision(root);
    }

    /**
     * 调用 Python 流式 ReAct 决策：final 轮的 title/summary JSON 增量实时交给公共投影层，
     * 学生端首字从整包完成（秒级十位）降到模型首个文本字段到达。工具与检索词仍由 Java 调用方复验。
     */
    public ExplanationDecision streamDecideStudentExplanation(
            String runId,
            String problem,
            List<ExplanationEvidence> evidence,
            List<String> availableTools,
            List<String> observations,
            String imageDataUrl,
            Consumer<ExplanationStreamEvent> listener) {
        return streamDecideStudentExplanation(runId, problem, evidence, availableTools, observations,
                imageDataUrl, listener, null, null);
    }

    /** 带模型偏好的流式决策：偏好无效时 providerRoute 自动回退默认路由。 */
    public ExplanationDecision streamDecideStudentExplanation(
            String runId,
            String problem,
            List<ExplanationEvidence> evidence,
            List<String> availableTools,
            List<String> observations,
            String imageDataUrl,
            Consumer<ExplanationStreamEvent> listener,
            String preferredProviderName,
            String preferredModelCode) {
        String workerKey = environment.getProperty(
                "math-agent.python-agent.worker-key", environment.getProperty("math-agent.worker-api-key", ""));
        if (workerKey == null || workerKey.isBlank()) {
            throw new IllegalStateException("Python agent worker key is not configured");
        }
        Map<String, Object> payload = Map.of(
                "runId", bounded(runId, 128),
                "mode", "react",
                "problem", bounded(problem, 8_000),
                "evidence", explanationEvidence(evidence),
                "availableTools", availableTools == null ? List.of() : availableTools.stream().map(item -> bounded(item, 80)).toList(),
                "observations", observations == null ? List.of() : observations.stream().map(item -> bounded(item, 800)).toList(),
                "imageDataUrl", imageDataUrl == null ? "" : imageDataUrl,
                "providerRoute", providerRoute(runId, "student_explanation", preferredProviderName, preferredModelCode,
                        hasImage(imageDataUrl)));
        try {
            return client.post()
                    .uri("/v1/student-explanations/stream")
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.TEXT_EVENT_STREAM)
                    .header("Authorization", "Bearer " + workerKey)
                    .header("X-Trace-Id", bounded(runId, 128))
                    .body(payload)
                    .exchange((request, response) -> readDecisionStream(response, listener));
        } catch (RestClientException exception) {
            throw new IllegalStateException("Python worker streaming request failed", exception);
        }
    }

    /** 把流式 completed 载荷解析成与同步决策一致的 ExplanationDecision。 */
    private ExplanationDecision explanationDecision(JsonNode root) {
        List<ExplanationCard> finalCards = new ArrayList<>();
        for (JsonNode item : root.path("cards")) {
            finalCards.add(new ExplanationCard(
                    bounded(item.path("cardKey").asText(), 80),
                    bounded(item.path("title").asText(), 160),
                    bounded(item.path("summary").asText(), 8_000),
                    stringArray(item.path("items"), 16, 800),
                    stringArray(item.path("sourceUris"), 24, 320),
                    bounded(item.path("renderMode").asText("text"), 32)));
        }
        return new ExplanationDecision(
                bounded(root.path("decision").asText("final"), 16),
                stringArray(root.path("tools"), 3, 80),
                stringArray(root.path("queries"), 6, 80),
                bounded(root.path("conversationTitle").asText(), 80),
                List.copyOf(finalCards),
                usage(root),
                bounded(root.path("providerName").asText(), 64),
                bounded(root.path("modelCode").asText(), 160),
                bounded(root.path("reasoningTrace").asText(), REASONING_TRACE_MAX_CHARS));
    }

    /** 调用 Python 流式卡片 endpoint；每个 delta 到达后立即交给 Java 公共 SSE 投影层。 */
    public ExplanationResult streamStudentExplanation(
            String runId,
            String problem,
            List<ExplanationEvidence> evidence,
            String imageDataUrl,
            Consumer<ExplanationStreamEvent> listener) {
        return streamStudentExplanation(runId, problem, evidence, imageDataUrl, listener, null, null);
    }

    /** 带模型偏好的流式卡片生成：偏好无效时 providerRoute 自动回退默认路由。 */
    public ExplanationResult streamStudentExplanation(
            String runId,
            String problem,
            List<ExplanationEvidence> evidence,
            String imageDataUrl,
            Consumer<ExplanationStreamEvent> listener,
            String preferredProviderName,
            String preferredModelCode) {
        String workerKey = environment.getProperty(
                "math-agent.python-agent.worker-key", environment.getProperty("math-agent.worker-api-key", ""));
        if (workerKey == null || workerKey.isBlank()) {
            throw new IllegalStateException("Python agent worker key is not configured");
        }
        Map<String, Object> payload = Map.of(
                "runId", bounded(runId, 128),
                "mode", "compose",
                "problem", bounded(problem, 8_000),
                "evidence", explanationEvidence(evidence),
                "availableTools", List.of(),
                "observations", List.of(),
                "imageDataUrl", imageDataUrl == null ? "" : imageDataUrl,
                "providerRoute", providerRoute(runId, "student_explanation", preferredProviderName, preferredModelCode,
                        hasImage(imageDataUrl)));
        try {
            return client.post()
                    .uri("/v1/student-explanations/stream")
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.TEXT_EVENT_STREAM)
                    .header("Authorization", "Bearer " + workerKey)
                    .header("X-Trace-Id", bounded(runId, 128))
                    .body(payload)
                    .exchange((request, response) -> readExplanationStream(response, listener));
        } catch (RestClientException exception) {
            throw new IllegalStateException("Python worker streaming request failed", exception);
        }
    }

    private ExplanationResult readExplanationStream(
            org.springframework.http.client.ClientHttpResponse response,
            Consumer<ExplanationStreamEvent> listener) throws java.io.IOException {
        return readWorkerEventStream(response, listener, this::explanationResult);
    }

    private ExplanationDecision readDecisionStream(
            org.springframework.http.client.ClientHttpResponse response,
            Consumer<ExplanationStreamEvent> listener) throws java.io.IOException {
        return readWorkerEventStream(response, listener, this::explanationDecision);
    }

    /**
     * 逐行读取 worker SSE：delta 立即回调，completed 交给调用方提供的解析器，error 直接抛出。
     * 卡片流解析 ExplanationResult，决策流解析 ExplanationDecision，两种端点共享同一份帧解析。
     */
    private <T> T readWorkerEventStream(
            org.springframework.http.client.ClientHttpResponse response,
            Consumer<ExplanationStreamEvent> listener,
            java.util.function.Function<JsonNode, T> completedParser) throws java.io.IOException {
        if (!response.getStatusCode().is2xxSuccessful()) {
            throw new IllegalStateException("Python worker streaming request returned " + response.getStatusCode().value());
        }
        String eventName = "";
        StringBuilder data = new StringBuilder();
        T completed = null;
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(response.getBody(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty()) {
                    if (!data.isEmpty()) {
                        JsonNode payload = objectMapper.readTree(data.toString());
                        if (!payload.isObject()) {
                            throw new IllegalStateException("Python worker stream payload is not a JSON object");
                        }
                        String normalized = bounded(eventName, 32);
                        if ("delta".equals(normalized)) {
                            if (listener != null) {
                                listener.accept(new ExplanationStreamEvent("delta", payload.path("content").asText(""),
                                        payload.path("reasoning").asText(""), null,
                                        bounded(payload.path("providerName").asText(), 64),
                                        bounded(payload.path("modelCode").asText(), 160), ""));
                            }
                        } else if ("completed".equals(normalized)) {
                            completed = completedParser.apply(payload);
                        } else if ("error".equals(normalized)) {
                            throw new AiProviderUnavailableException(503, bounded(payload.path("message").asText("Python worker stream failed"), 240));
                        }
                    }
                    eventName = "";
                    data.setLength(0);
                } else if (line.startsWith("event:")) {
                    eventName = line.substring(6).trim();
                } else if (line.startsWith("data:")) {
                    if (!data.isEmpty()) data.append('\n');
                    data.append(line.substring(5).stripLeading());
                }
            }
        }
        if (completed == null) {
            throw new IllegalStateException("Python worker stream ended without a completed result");
        }
        return completed;
    }

    private ExplanationResult explanationResult(JsonNode root) {
        List<ExplanationCard> cards = new ArrayList<>();
        for (JsonNode item : root.path("cards")) {
            cards.add(new ExplanationCard(
                    bounded(item.path("cardKey").asText(), 80), bounded(item.path("title").asText(), 160),
                    bounded(item.path("summary").asText(), 8_000), stringArray(item.path("items"), 16, 800),
                    stringArray(item.path("sourceUris"), 24, 320), bounded(item.path("renderMode").asText("text"), 32)));
        }
        return new ExplanationResult(bounded(root.path("conversationTitle").asText(), 80), List.copyOf(cards),
                usage(root), bounded(root.path("providerName").asText(), 64), bounded(root.path("modelCode").asText(), 160),
                bounded(root.path("reasoningTrace").asText(), REASONING_TRACE_MAX_CHARS));
    }

    /**
     * Invokes only the V2 deterministic context graph. The existing V1 ReAct/evidence pipeline still owns generation.
     */
    public ConversationContextPreparation prepareStudentExplanationContext(
            String runId,
            String problem,
            List<ConversationContextMessage> context,
            ConversationContextSummary summary,
            int maxInputTokens,
            int reservedOutputTokens,
            int summaryTriggerTokens) {
        Map<String, Object> contextPayload = new LinkedHashMap<>();
        contextPayload.put("schemaVersion", "student-conversation-context-v1");
        contextPayload.put("revision", bounded(runId, 160));
        contextPayload.put("messages", context == null ? List.of() : context.stream().limit(200).map(item -> Map.of(
                "messageId", bounded(item.messageId(), 160),
                "questionText", bounded(item.questionText(), 8_000),
                "answerText", bounded(item.answerText(), 8_000),
                "createdAt", bounded(item.createdAt(), 64))).toList());
        if (summary != null && !bounded(summary.content(), 16_000).isBlank()) {
            contextPayload.put("summary", Map.of(
                    "summaryFromMessageId", bounded(summary.fromMessageId(), 160),
                    "summaryToMessageId", bounded(summary.toMessageId(), 160),
                    "summaryVersion", Math.max(1, summary.version()),
                    "contentHash", bounded(summary.contentHash(), 128),
                    "content", bounded(summary.content(), 16_000)));
        }
        JsonNode root = post("/v2/student-explanations/prepare", runId, Map.of(
                "contractVersion", "student-explanation-ai-v2",
                "runId", bounded(runId, 128),
                "deadlineEpochMs", System.currentTimeMillis() + environment.getProperty(
                        "math-agent.python-agent.timeout-ms", Long.class, DEFAULT_TIMEOUT_MS),
                "problem", bounded(problem, 8_000),
                "imageDataUrl", "",
                "context", contextPayload,
                "limits", Map.of(
                        "maxInputTokens", Math.max(512, Math.min(maxInputTokens, CONTEXT_MAX_INPUT_TOKENS_CAP)),
                        "reservedOutputTokens", Math.max(128, Math.min(reservedOutputTokens, 32_000)),
                        "summaryTriggerTokens", Math.max(256, Math.min(summaryTriggerTokens, CONTEXT_SUMMARY_TRIGGER_TOKENS_CAP)),
                        "maxProviderCalls", 1),
                "providerRoute", providerRoute(runId, "student_explanation")));
        List<String> selected = stringArray(root.path("selectedMessageIds"), 200, 160);
        JsonNode update = root.path("memoryUpdate");
        ConversationContextSummary memoryUpdate = update.isObject()
                ? new ConversationContextSummary(
                        bounded(update.path("summaryFromMessageId").asText(), 160),
                        bounded(update.path("summaryToMessageId").asText(), 160),
                        Math.max(1, update.path("summaryVersion").asInt(1)),
                        bounded(update.path("contentHash").asText(), 128),
                        bounded(update.path("content").asText(), 16_000))
                : null;
        return new ConversationContextPreparation(
                bounded(root.path("packedContext").asText(), 32_000),
                Math.max(0, root.path("inputTokens").asInt(0)),
                selected,
                memoryUpdate);
    }

    /**
     * 灰度调用一次 v2 student graph stream；Python 在同一 durable run 中完成上下文预算与卡片生成。
     */
    public ExplanationResult streamStudentExplanationV2(
            String runId,
            String problem,
            List<ConversationContextMessage> context,
            String imageDataUrl,
            int maxInputTokens,
            int reservedOutputTokens,
            int summaryTriggerTokens,
            Consumer<ExplanationStreamEvent> listener) {
        String workerKey = environment.getProperty(
                "math-agent.python-agent.worker-key", environment.getProperty("math-agent.worker-api-key", ""));
        if (workerKey == null || workerKey.isBlank()) {
            throw new IllegalStateException("Python agent worker key is not configured");
        }
        Map<String, Object> payload = Map.of(
                "contractVersion", "student-explanation-ai-v2",
                "runId", bounded(runId, 128),
                "deadlineEpochMs", System.currentTimeMillis() + environment.getProperty(
                        "math-agent.python-agent.timeout-ms", Long.class, DEFAULT_TIMEOUT_MS),
                "problem", bounded(problem, 8_000),
                "imageDataUrl", imageDataUrl == null ? "" : imageDataUrl,
                "context", Map.of(
                        "schemaVersion", "student-conversation-context-v1",
                        "revision", bounded(runId, 160),
                        "messages", context == null ? List.of() : context.stream().limit(200).map(item -> Map.of(
                                "messageId", bounded(item.messageId(), 160),
                                "questionText", bounded(item.questionText(), 8_000),
                                "answerText", bounded(item.answerText(), 8_000),
                                "createdAt", bounded(item.createdAt(), 64))).toList()),
                "limits", Map.of(
                        "maxInputTokens", Math.max(512, Math.min(maxInputTokens, CONTEXT_MAX_INPUT_TOKENS_CAP)),
                        "reservedOutputTokens", Math.max(128, Math.min(reservedOutputTokens, 32_000)),
                        "summaryTriggerTokens", Math.max(256, Math.min(summaryTriggerTokens, CONTEXT_SUMMARY_TRIGGER_TOKENS_CAP)),
                        "maxProviderCalls", 1),
                "providerRoute", providerRoute(runId, "student_explanation", hasImage(imageDataUrl)));
        try {
            return client.post()
                    .uri("/v2/student-explanations/stream")
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.TEXT_EVENT_STREAM)
                    .header("Authorization", "Bearer " + workerKey)
                    .header("X-Trace-Id", bounded(runId, 128))
                    .body(payload)
                    .exchange((request, response) -> readExplanationStream(response, listener));
        } catch (RestClientException exception) {
            throw new IllegalStateException("Python v2 student explanation streaming request failed", exception);
        }
    }

    /** 调用 Python 最终卡片生成，并保留 Java 对卡片内容和引用的最终校验权。 */
    public ExplanationResult composeStudentExplanation(
            String runId,
            String problem,
            List<ExplanationEvidence> evidence,
            String imageDataUrl) {
        JsonNode root = post("/v1/student-explanations/sync", runId, Map.of(
                "runId", runId,
                "mode", "compose",
                "problem", bounded(problem, 8_000),
                "evidence", explanationEvidence(evidence),
                "imageDataUrl", imageDataUrl == null ? "" : imageDataUrl,
                "providerRoute", providerRoute(runId, "student_explanation", hasImage(imageDataUrl))));
        requireCompleted(root, "student explanation");
        return explanationResult(root);
    }

    /**
     * 调用动画讲题同步 endpoint（冻结契约 2026-09-09）：题面 → 分镜生成 → Manim 渲染，整单分钟级。
     *
     * <p>请求体字段与 Python {@code AnimatedLessonRunRequest}（extra="forbid"）逐一对应，多送字段会被
     * 422 拒绝，因此这里固定六键；lessonId/storyboard 允许 null 但键必须存在。走 longTaskClient 的独立
     * 长超时（math-agent.animated-lesson.timeout-ms，默认 1800s），调用方必须在 Agent Worker 的
     * 租约心跳线程里执行，避免占用交互式请求预算。原始响应 JSON 一并返回，由任务服务原样持久化，
     * Java 不改写任何教学字段。</p>
     */
    public AnimatedLessonResult runAnimatedLesson(
            String runId, String problemText, String lessonId, boolean render, JsonNode storyboard) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("runId", bounded(runId, 128));
        payload.put("providerRoute", animatedLessonRoute(runId));
        payload.put("problemText", bounded(problemText, 20_000));
        payload.put("lessonId", lessonId == null || lessonId.isBlank() ? null : bounded(lessonId, 64));
        payload.put("render", render);
        payload.put("storyboard", storyboard == null || storyboard.isNull() ? null : storyboard);
        JsonNode root = post(longTaskClient, "/v1/animated-lessons/sync", runId, payload);
        requireCompleted(root, "animated lesson");
        try {
            return new AnimatedLessonResult(
                    bounded(root.path("lessonId").asText(), 64),
                    bounded(root.path("videoPath").asText(), 512),
                    bounded(root.path("storyboardPath").asText(), 512),
                    bounded(root.path("chaptersPath").asText(), 512),
                    root.path("durationSec").asDouble(0.0d),
                    bounded(root.path("providerName").asText(), 64),
                    bounded(root.path("modelCode").asText(), 160),
                    objectMapper.writeValueAsString(root));
        } catch (Exception exception) {
            throw new IllegalStateException("Animated lesson response is not serializable", exception);
        }
    }

    /** 调用 Python provider probe，并只投影脱敏的公开健康字段。 */
    public List<HealthResult> providerHealth(String runId) {
        JsonNode root = post("/v1/provider-health/sync", runId, Map.of(
                "runId", runId,
                "providerRoute", providerRoute(runId, "provider_health")));
        requireCompleted(root, "provider health");
        List<HealthResult> results = new ArrayList<>();
        for (JsonNode item : root.path("results")) {
            results.add(new HealthResult(
                    bounded(item.path("providerName").asText(), 64),
                    bounded(item.path("modelCode").asText(), 160),
                    item.path("configured").asBoolean(false),
                    item.path("available").asBoolean(false),
                    item.path("statusCode").canConvertToInt() ? item.path("statusCode").asInt() : null,
                    Math.max(0L, item.path("elapsedMs").asLong(0L)),
                    bounded(item.path("message").asText("Provider health check failed."), 240)));
        }
        return List.copyOf(results);
    }

    private JsonNode post(String path, String runId, Map<String, Object> payload) {
        return post(client, path, runId, payload);
    }

    /** 指定目标客户端的同步 POST；长超时 workload（动画讲题）走 longTaskClient，其余走共享客户端。 */
    private JsonNode post(RestClient target, String path, String runId, Map<String, Object> payload) {
        String workerKey = environment.getProperty(
                "math-agent.python-agent.worker-key", environment.getProperty("math-agent.worker-api-key", ""));
        if (workerKey == null || workerKey.isBlank()) {
            throw new IllegalStateException("Python agent worker key is not configured");
        }
        try {
            JsonNode root = target.post()
                    .uri(path)
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("Authorization", "Bearer " + workerKey)
                    .header("X-Trace-Id", bounded(runId, 128))
                    .body(payload)
                    .retrieve()
                    .body(JsonNode.class);
            if (root == null || root.isNull()) {
                throw new IllegalStateException("Python worker returned an empty response");
            }
            return root;
        } catch (RestClientException exception) {
            throw new IllegalStateException("Python worker request failed", exception);
        }
    }

    private static List<Map<String, String>> explanationEvidence(List<ExplanationEvidence> evidence) {
        if (evidence == null || evidence.isEmpty()) {
            return List.of();
        }
        return evidence.stream()
                .map(item -> Map.<String, String>of(
                        "sourceUri", bounded(item.sourceUri(), 320),
                        "title", bounded(item.title(), 400),
                        "snippet", bounded(item.snippet(), 1_600)))
                .toList();
    }

    private static List<String> stringArray(JsonNode values, int maxItems, int maxLength) {
        List<String> result = new ArrayList<>();
        if (values == null || !values.isArray()) {
            return List.of();
        }
        for (JsonNode value : values) {
            String normalized = bounded(value.asText(), maxLength);
            if (!normalized.isBlank() && !result.contains(normalized)) {
                result.add(normalized);
            }
            if (result.size() >= maxItems) {
                break;
            }
        }
        return List.copyOf(result);
    }

    private static Usage usage(JsonNode root) {
        JsonNode usage = root.path("usage");
        int prompt = Math.max(0, usage.path("promptTokens").asInt(0));
        int completion = Math.max(0, usage.path("completionTokens").asInt(0));
        int total = Math.max(0, usage.path("totalTokens").asInt(prompt + completion));
        if (total < prompt || total < completion) {
            throw new IllegalStateException("Python student explanation usage totals are inconsistent");
        }
        return new Usage(prompt, completion, total);
    }

    private Map<String, Object> providerRoute(String runId, String workload) {
        return providerRoute(runId, workload, null, null, false);
    }

    /** 无模型偏好但可能带图的路由签发（同步决策、转写等入口）。 */
    private Map<String, Object> providerRoute(String runId, String workload, boolean imageRequired) {
        return providerRoute(runId, workload, null, null, imageRequired);
    }

    /**
     * 签发本轮 provider 路由；前端模型切换传入偏好时，偏好模型成为 primary（仍经目录白名单校验），
     * 其余启用模型按原顺序作 fallback。routeGrant 按实际列表签名，worker 校验与之一致。
     *
     * <p>imageRequired=true（本轮携带题图）时，primary 与 fallback 都只允许已实测支持图片输入的模型：
     * deepseek 系收到 image_url 块会静默丢图（2026-09-06 探针）。老板 2026-09-06 拍板：用户显式
     * 选择文本模型又带图时不静默降级，直接抛错让前端提示"该模型不支持图片"；只有"自动"路由才切到
     * 视觉默认，保证"发送后原图直接进入 AI 上下文"对学生端始终成立。</p>
     */
    Map<String, Object> providerRoute(
            String runId, String workload, String preferredProviderName, String preferredModelCode,
            boolean imageRequired) {
        AiProviderCatalog.Provider preferred =
                providerCatalog.preferredProvider(preferredProviderName, preferredModelCode).orElse(null);
        if (imageRequired && preferred != null && !AiProviderCatalog.supportsVision(preferred.chatModel())) {
            throw new IllegalArgumentException("所选模型 " + preferred.name() + "/" + preferred.chatModel()
                    + " 不支持图片输入，请切换到视觉模型或移除题图");
        }
        AiProviderCatalog.Provider primary;
        if (preferred != null) {
            primary = preferred;
        } else if (imageRequired) {
            primary = providerCatalog.visionDefaultProvider().orElseGet(providerCatalog::defaultProvider);
        } else {
            primary = providerCatalog.defaultProvider();
        }
        // 带图轮换只允许视觉候选（含同提供商其余已验证视觉模型）；纯文本轮保持原有按提供商默认的路由。
        java.util.stream.Stream<AiProviderCatalog.Provider> fallbackSource = imageRequired
                ? providerCatalog.visionRoutes().stream()
                : providerCatalog.enabledProviders().stream();
        return signedRoute(runId, workload, primary, fallbackSource
                .filter(provider -> !provider.name().equals(primary.name())
                        || !provider.chatModel().equals(primary.chatModel()))
                .limit(MAX_FALLBACKS)
                .toList());
    }

    /**
     * 动画讲题任务路由签发（老板 2026-09-09 拍板）：主位固定 glm——Terra（openai 网关）已从动画讲题
     * 链路剔除；worker 的 anthropic_compat 适配层承担 glm 线格式差异，Java 侧无需感知。模型编码取
     * catalog 中 glm 档案的 chatModel 现值为准（环境变量换模型时路由随之漂移，不做本地硬编码）；
     * fallback 按契约取 deepseek。glm 未在 catalog 启用时抛错终止任务，绝不静默回退 openai——
     * 静默换链会违反本 workloads 的显式路由口径。
     */
    private Map<String, Object> animatedLessonRoute(String runId) {
        AiProviderCatalog.Provider primary = providerCatalog.provider("glm")
                .orElseThrow(() -> new IllegalStateException(
                        "Animated lesson requires the enabled glm route; no silent fallback to the openai gateway"));
        List<AiProviderCatalog.Provider> fallbacks = providerCatalog.provider("deepseek")
                .stream()
                .filter(provider -> !provider.name().equals(primary.name())
                        || !provider.chatModel().equals(primary.chatModel()))
                .toList();
        return signedRoute(runId, "animated_lesson", primary, fallbacks);
    }

    /** 把 primary + 有序 fallback 列表编成 worker 契约的 providerRoute，并按实际列表签发 routeGrant。 */
    private Map<String, Object> signedRoute(
            String runId, String workload, AiProviderCatalog.Provider primary,
            List<AiProviderCatalog.Provider> fallbackProviders) {
        List<Map<String, String>> fallbacks = fallbackProviders.stream()
                .map(provider -> Map.of("name", provider.name(), "model", provider.chatModel()))
                .toList();
        List<ProviderRouteGrantSigner.ProviderRoute> routes = new java.util.ArrayList<>();
        routes.add(new ProviderRouteGrantSigner.ProviderRoute(primary.name(), primary.chatModel()));
        fallbackProviders.forEach(provider ->
                routes.add(new ProviderRouteGrantSigner.ProviderRoute(provider.name(), provider.chatModel())));
        return Map.of(
                "primary", Map.of("name", primary.name(), "model", primary.chatModel()),
                "fallbacks", fallbacks,
                "routeGrant", routeGrantSigner.sign(runId, workload, routes));
    }

    /** 本轮 payload 是否携带题图；决定 provider 路由是否强制视觉能力。 */
    private static boolean hasImage(String imageDataUrl) {
        return imageDataUrl != null && !imageDataUrl.isBlank();
    }

    private static void requireCompleted(JsonNode root, String workload) {
        if (!"COMPLETED".equals(root.path("status").asText())) {
            throw new IllegalStateException("Python " + workload + " did not complete");
        }
    }

    private static String bounded(String value, int limit) {
        String normalized = value == null ? "" : value.strip();
        if (normalized.length() <= limit) {
            return normalized;
        }
        // 2026-09-08 思考乱码修复：截断点若落在 UTF-16 代理对中间会留下孤立高代理项，
        // reasoningTrace 持久化后历史回看渲染成 ""（前端侧新乱码）。退一格丢掉残缺代理对即可，
        // 长度预算语义不变（仍按 limit 码元计）。
        int end = Math.max(0, limit - 3);
        if (end > 0 && Character.isHighSurrogate(normalized.charAt(end - 1))) {
            end--;
        }
        return normalized.substring(0, end) + "...";
    }

    private static double boundedConfidence(double value) {
        return Double.isFinite(value) ? Math.max(0.0d, Math.min(1.0d, value)) : 0.0d;
    }

    public record KnowledgePoint(String knowledgePointId, String knowledgePointName) {
    }

    public record IntentResult(String intentCode, double confidence, String knowledgePointId, String providerName, String modelCode) {
    }

    public record TranscriptionResult(
            boolean completed, String problemText, double confidence, String providerName, String modelCode) {
    }

    public record ExplanationEvidence(String sourceUri, String title, String snippet) {
    }

    public record ConversationContextMessage(String messageId, String questionText, String answerText, String createdAt) {
    }

    public record ConversationContextSummary(
            String fromMessageId,
            String toMessageId,
            int version,
            String contentHash,
            String content) {
    }

    public record ConversationContextPreparation(
            String packedContext,
            int inputTokens,
            List<String> selectedMessageIds,
            ConversationContextSummary memoryUpdate) {
    }

    public record ExplanationDecision(
            String decision,
            List<String> tools,
            List<String> queries,
            String conversationTitle,
            List<ExplanationCard> cards,
            Usage usage,
            String providerName,
            String modelCode,
            String reasoningTrace) {
    }

    public record ExplanationCard(
            String cardKey,
            String title,
            String summary,
            List<String> items,
            List<String> sourceUris,
            String renderMode) {
    }

    public record ExplanationStreamEvent(
            String eventName,
            String content,
            String reasoning,
            ExplanationResult result,
            String providerName,
            String modelCode,
            String message) {
    }

    public record ExplanationResult(
            String conversationTitle,
            List<ExplanationCard> cards,
            Usage usage,
            String providerName,
            String modelCode,
            String reasoningTrace) {
    }

    public record Usage(int promptTokens, int completionTokens, int totalTokens) {
    }

    /**
     * 动画讲题 worker 响应的类型化投影；rawJson 保留响应原文（含 chapters/problem/usage），
     * 供任务服务原样持久化，Java 不增删教学字段。路径字符串是 worker 容器内的绝对产物路径，
     * 由 compose 将同一宿主目录以相同容器路径挂进 worker 与 backend 后对 Java 可读。
     */
    public record AnimatedLessonResult(
            String lessonId,
            String videoPath,
            String storyboardPath,
            String chaptersPath,
            double durationSec,
            String providerName,
            String modelCode,
            String rawJson) {
    }

    public record HealthResult(
            String providerName,
            String modelCode,
            boolean configured,
            boolean reachable,
            Integer statusCode,
            long elapsedMs,
            String safeReason) {
    }
}
