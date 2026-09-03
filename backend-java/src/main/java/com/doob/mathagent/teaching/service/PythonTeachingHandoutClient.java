package com.doob.mathagent.teaching.service;

import com.doob.mathagent.agent.service.ProviderRouteGrantSigner;
import com.doob.mathagent.infrastructure.ai.AiProviderCatalog;
import com.doob.mathagent.teaching.TeachingEvidence;
import com.doob.mathagent.teaching.dto.TeachingTaskRequest;
import com.doob.mathagent.teaching.vo.TeachingTaskResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.core.env.Environment;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

/**
 * Teaching-task adapter for the single Python handout graph.
 *
 * <p>The graph receives only a task/run identifier and opaque evidence references. Java retains task ownership,
 * evidence authorization and publication; the returned teacher, student and lecture documents are projected onto
 * the existing durable teaching-task draft rather than creating a second workflow row.</p>
 */
@Service
public class PythonTeachingHandoutClient implements TeachingHandoutAiClient, ModelLatexRepairClient {

    private static final Logger LOGGER = LoggerFactory.getLogger(PythonTeachingHandoutClient.class);
    private static final String CONTRACT_VERSION = "handout-ai-v2";
    private static final long MIN_TIMEOUT_MS = 1_000L;
    private static final long DEFAULT_TIMEOUT_MS = 300_000L;
    private static final long DEFAULT_CONNECT_TIMEOUT_MS = 5_000L;
    private static final int MAX_EVIDENCE_REFS = 24;
    private static final int TRACE_ID_HEX_LENGTH = 32;
    private static final int SPAN_ID_HEX_LENGTH = 16;
    private static final int DEFAULT_EVENT_PAGE_LIMIT = 100;
    private static final int MAX_EVENT_PAGE_LIMIT = 500;

    /** Safe operational fields emitted by the Python handout checkpoint stream. */
    private static final java.util.Set<String> EVENT_FIELDS = java.util.Set.of(
            "event", "status", "node", "phase", "revisionRound", "turn", "provider", "model", "deterministicRepair");

    private final Environment environment;
    private final AiProviderCatalog providerCatalog;
    private final ProviderRouteGrantSigner routeGrantSigner;
    private final RestClient client;
    private final long timeoutMs;

    /** Configures bounded connect/read timeouts from the same task lease budget as the Worker. */
    public PythonTeachingHandoutClient(
            Environment environment,
            ObjectMapper objectMapper,
            AiProviderCatalog providerCatalog,
            ProviderRouteGrantSigner routeGrantSigner) {
        this.environment = environment;
        this.providerCatalog = providerCatalog;
        this.routeGrantSigner = routeGrantSigner;
        long configuredTimeout = environment.getProperty("math-agent.python-handout.timeout-ms", Long.class, DEFAULT_TIMEOUT_MS);
        // This request runs under the top-level lecture-task lease claimed by LectureTaskConsumer. Using the
        // unrelated stage-worker lease can let the Python call outlive its owning lecture-task generation.
        long leaseMs = environment.getProperty("math-agent.teaching.lecture-task.lease-seconds", Long.class, 900L) * 1_000L;
        long safetyMarginMs = environment.getProperty("math-agent.python-handout.lease-safety-margin-ms", Long.class, 15_000L);
        this.timeoutMs = Math.max(MIN_TIMEOUT_MS, Math.min(configuredTimeout, Math.max(MIN_TIMEOUT_MS, leaseMs - safetyMarginMs)));
        long connectTimeoutMs = Math.min(timeoutMs, environment.getProperty(
                "math-agent.python-handout.connect-timeout-ms", Long.class, DEFAULT_CONNECT_TIMEOUT_MS));
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(Math.max(MIN_TIMEOUT_MS, connectTimeoutMs)))
                .version(HttpClient.Version.HTTP_1_1)
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(Duration.ofMillis(timeoutMs));
        this.client = RestClient.builder()
                .baseUrl(environment.getProperty("math-agent.python-handout.base-url", "http://ai-worker:8091"))
                .requestFactory(requestFactory)
                .build();
    }

    /**
     * Reads one durable Python event page after the supplied numeric cursor.
     * The worker's event payload is operational only; content-bearing checkpoint fields are never projected.
     */
    List<HandoutEvent> readEvents(String runId, long afterId, int limit) {
        if (runId == null || runId.isBlank()) {
            throw new IllegalArgumentException("runId is required");
        }
        if (afterId < 0) {
            throw new IllegalArgumentException("afterId must be non-negative");
        }
        int boundedLimit = Math.max(1, Math.min(limit, MAX_EVENT_PAGE_LIMIT));
        String workerKey = environment.getProperty(
                "math-agent.python-handout.worker-key", environment.getProperty("math-agent.worker-api-key", ""));
        if (workerKey == null || workerKey.isBlank()) {
            throw new IllegalStateException("Python handout worker key is not configured");
        }
        JsonNode root = client.get()
                .uri(uriBuilder -> uriBuilder.path("/v1/handout-runs/{runId}/events")
                        .queryParam("afterId", afterId).queryParam("limit", boundedLimit).build(runId))
                .header("Authorization", "Bearer " + workerKey)
                .retrieve().body(JsonNode.class);
        if (root == null || !runId.equals(root.path("runId").asText())) {
            throw new IllegalStateException("Python handout event response has an invalid runId");
        }
        List<HandoutEvent> events = new ArrayList<>();
        long cursor = afterId;
        for (JsonNode item : root.path("events")) {
            long eventId = item.path("eventId").asLong(-1);
            if (eventId <= cursor) {
                continue;
            }
            String eventName = item.path("event").asText("");
            if (eventName.isBlank()) {
                continue;
            }
            Map<String, Object> safe = projectEvent(item);
            if (safe.isEmpty()) {
                continue;
            }
            events.add(new HandoutEvent(eventId, safe));
            cursor = eventId;
        }
        long nextAfterId = root.path("nextAfterId").asLong(cursor);
        if (nextAfterId < cursor) {
            throw new IllegalStateException("Python handout event cursor moved backwards");
        }
        return List.copyOf(events);
    }

    static Map<String, Object> projectEvent(JsonNode item) {
        if (item == null || item.path("event").asText("").isBlank()) {
            return Map.of();
        }
        Map<String, Object> safe = new java.util.LinkedHashMap<>();
        EVENT_FIELDS.stream().filter(item::has).forEach(field -> safe.put(field, scalar(item.get(field))));
        safe.put("event", item.path("event").asText());
        return Map.copyOf(safe);
    }

    private static Object scalar(JsonNode value) {
        if (value == null || value.isNull()) return null;
        if (value.isBoolean()) return value.booleanValue();
        if (value.isNumber()) return value.numberValue();
        return value.isTextual() ? value.textValue() : null;
    }

    record HandoutEvent(long eventId, Map<String, Object> data) {}

    /** Calls the only handout graph and projects its three audience documents into the teaching-task draft shape. */
    @Override
    public TeachingTaskResponse.AiDraft execute(
            String taskId, TeachingTaskRequest request, List<TeachingEvidence> evidence) {
        String workerKey = environment.getProperty(
                "math-agent.python-handout.worker-key", environment.getProperty("math-agent.worker-api-key", ""));
        if (workerKey == null || workerKey.isBlank()) {
            throw new IllegalStateException("Python handout worker key is not configured");
        }
        JsonNode root = client.post()
                .uri("/v1/handout-runs/sync")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer " + workerKey)
                .header("X-Trace-Id", taskId)
                .body(Map.ofEntries(
                        Map.entry("contractVersion", CONTRACT_VERSION),
                        Map.entry("runId", taskId),
                        Map.entry("taskId", taskId),
                        Map.entry("writingGoal", bounded(request.learningGoal(), 1_200)),
                        Map.entry("questionText", bounded(request.questionText(), 16_000)),
                        Map.entry("evidenceRefs", evidenceRefs(taskId, evidence)),
                        Map.entry("graphVersion", environment.getProperty("math-agent.python-handout.graph-version", "handout-v2")),
                        Map.entry("idempotencyKey", "teaching-handout:" + taskId),
                        Map.entry("traceparent", traceparent(taskId)),
                        Map.entry("providerRoute", providerRoute(taskId)),
                        Map.entry("resume", true),
                        Map.entry("deadlineEpochMs", System.currentTimeMillis() + timeoutMs)))
                .retrieve()
                .body(JsonNode.class);
        if (root == null || root.isNull()) {
            throw new IllegalStateException("Python handout returned an empty response");
        }
        return project(root);
    }

    /**
     * Signs the bounded handout route accepted by the worker. Terra remains a supported route, while a deployment may
     * explicitly prefer the verified DeepSeek flash route for acceptance so a known Terra outage cannot consume its
     * task lease before generation begins.
     */
    private Map<String, Object> providerRoute(String runId) {
        String preferred = environment.getProperty("math-agent.handout.preferred-provider", "deepseek").strip();
        if ("deepseek".equalsIgnoreCase(preferred)) {
            AiProviderCatalog.Provider deepseek = providerCatalog.provider("deepseek")
                    .orElseThrow(() -> new IllegalStateException("DeepSeek handout route is not enabled"));
            List<ProviderRouteGrantSigner.ProviderRoute> routes = List.of(
                    new ProviderRouteGrantSigner.ProviderRoute(deepseek.name(), deepseek.chatModel()));
            return Map.of(
                    "primary", Map.of("name", deepseek.name(), "model", deepseek.chatModel()),
                    "fallbacks", List.of(),
                    "routeGrant", routeGrantSigner.sign(runId, "handout", routes));
        }
        AiProviderCatalog.Provider terra = providerCatalog.provider("openai")
                .map(provider -> new AiProviderCatalog.Provider(provider.name(), "gpt-5.6-terra"))
                .orElseThrow(() -> new IllegalStateException("Terra handout route is not enabled"));
        List<AiProviderCatalog.Provider> fallbackProviders = providerCatalog.provider("deepseek").stream().toList();
        List<Map<String, String>> fallbacks = fallbackProviders.stream()
                .map(provider -> Map.of("name", provider.name(), "model", provider.chatModel()))
                .toList();
        List<ProviderRouteGrantSigner.ProviderRoute> routes = new ArrayList<>();
        routes.add(new ProviderRouteGrantSigner.ProviderRoute(terra.name(), terra.chatModel()));
        fallbackProviders.forEach(provider -> routes.add(new ProviderRouteGrantSigner.ProviderRoute(
                provider.name(), provider.chatModel())));
        return Map.of(
                "primary", Map.of("name", terra.name(), "model", terra.chatModel()),
                "fallbacks", fallbacks,
                "routeGrant", routeGrantSigner.sign(runId, "handout", routes));
    }

    /**
     * 把编译失败的完整文档与真实 XeLaTeX 错误摘录交给 Python 的模型修复端点。
     *
     * <p>任何异常都折叠为 empty（超时、5xx、结构校验被拒、worker key 缺失），
     * 调用方因此可以继续走 recovery-stub 路径；修复文本只有再次编译成功才会发布。</p>
     */
    @Override
    public Optional<String> repairLatex(String runId, String latexSource, String compilerError, int turn) {
        if (runId == null || runId.isBlank() || latexSource == null || latexSource.isBlank()) {
            return Optional.empty();
        }
        String workerKey = configuredWorkerKey();
        if (workerKey == null) {
            return Optional.empty();
        }
        try {
            JsonNode root = client.post()
                    .uri("/v1/latex-repair/sync")
                    .header("Authorization", "Bearer " + workerKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of(
                            "runId", runId,
                            "latexSource", latexSource,
                            "compilerError", compilerError == null ? "" : compilerError,
                            "turn", turn))
                    .retrieve().body(JsonNode.class);
            if (root != null && "REPAIRED".equals(root.path("status").asText())) {
                String repaired = root.path("repairedLatex").asText("");
                if (!repaired.isBlank()) {
                    return Optional.of(repaired);
                }
            }
            return Optional.empty();
        } catch (RuntimeException exception) {
            LOGGER.warn("LaTeX repair call failed for run {} turn {}: {}", runId, turn, exception.toString());
            return Optional.empty();
        }
    }

    /**
     * 读取 Python 私有 model-turn 诊断投影（含思考轨迹截断片段）。
     *
     * <p>worker key 只在本机/内网边界使用；Python 端投影已剔除 prompt/rawResponse/答案原文，
     * 调用方必须仍先完成任务归属与教师/管理员身份校验。</p>
     */
    public List<Map<String, Object>> readModelDiagnostics(String runId, int excerptChars) {
        if (runId == null || runId.isBlank()) {
            throw new IllegalArgumentException("runId is required");
        }
        String workerKey = environment.getProperty(
                "math-agent.python-handout.worker-key", environment.getProperty("math-agent.worker-api-key", ""));
        if (workerKey == null || workerKey.isBlank()) {
            throw new IllegalStateException("Python handout worker key is not configured");
        }
        JsonNode root = client.get()
                .uri(uriBuilder -> uriBuilder.path("/v1/handout-runs/{runId}/model-diagnostics")
                        .queryParam("excerptChars", Math.max(0, Math.min(excerptChars, 20_000))).build(runId))
                .header("Authorization", "Bearer " + workerKey)
                .retrieve().body(JsonNode.class);
        if (root == null || !runId.equals(root.path("runId").asText())) {
            throw new IllegalStateException("Python model diagnostics response has an invalid runId");
        }
        List<Map<String, Object>> turns = new ArrayList<>();
        ObjectMapper mapper = new ObjectMapper();
        for (JsonNode item : root.path("turns")) {
            if (!item.isObject()) {
                continue;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> row = mapper.convertValue(item, Map.class);
            // 保留 null 值（finishReason 可为空）：Map.copyOf 会 NPE，这里用只读 LinkedHashMap。
            turns.add(java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(row)));
        }
        return List.copyOf(turns);
    }

    /** Returns the configured worker key, or null when repair calls cannot be authenticated. */
    private String configuredWorkerKey() {
        String workerKey = environment.getProperty(
                "math-agent.python-handout.worker-key", environment.getProperty("math-agent.worker-api-key", ""));
        return workerKey == null || workerKey.isBlank() ? null : workerKey;
    }

    /** Keeps provider values and token totals from Python while treating absent pricing as unknown. */
    static TeachingTaskResponse.AiDraft project(JsonNode root) {
        JsonNode documents = root.path("documents");
        JsonNode metrics = root.path("metrics");
        String teacher = documentMarkdown(documents.path("teacher_writer"));
        String student = documentMarkdown(documents.path("student_writer"));
        String lecture = documentMarkdown(documents.path("lecture_writer"));
        String provider = nodeMetric(metrics, "teacher_writer").path("provider").asText("python-langgraph");
        String model = nodeMetric(metrics, "teacher_writer").path("model").asText("");
        boolean completed = "COMPLETED".equals(root.path("status").asText())
                && !teacher.isBlank() && !student.isBlank() && !lecture.isBlank();
        List<TeachingTaskResponse.AiRecoveryEvent> events = new ArrayList<>();
        for (JsonNode metric : metrics.path("nodeMetrics")) {
            events.add(new TeachingTaskResponse.AiRecoveryEvent(
                    "PYTHON_HANDOUT_" + metric.path("node").asText("NODE").toUpperCase(),
                    metric.path("provider").asText(provider), metric.path("model").asText(model),
                    1, completed, false, metric.path("status").asText("completed")));
        }
        String content = String.join("\n\n", List.of(teacher, student, lecture));
        return new TeachingTaskResponse.AiDraft(
                true,
                provider,
                model,
                metrics.path("promptTokens").asInt(0),
                metrics.path("completionTokens").asInt(0),
                metrics.path("totalTokens").asInt(0),
                content,
                completed ? "Python handout graph completed." : "Python handout graph did not return all documents.",
                completed,
                teacher,
                student,
                lecture,
                List.of(),
                List.of(),
                completed ? "" : "HANDOUT_GRAPH_INCOMPLETE",
                0,
                0,
                false,
                List.copyOf(events),
                assetPlacements(documents));
    }

    private static JsonNode nodeMetric(JsonNode metrics, String node) {
        for (JsonNode metric : metrics.path("nodeMetrics")) {
            if (node.equals(metric.path("node").asText())) {
                return metric;
            }
        }
        return metrics;
    }

    private static String documentMarkdown(JsonNode document) {
        return document.path("markdown").asText("").strip();
    }

    /** Parses the current source-row placement contract; legacy question/asset-id placement is rejected. */
    private static List<TeachingTaskResponse.AssetPlacement> assetPlacements(JsonNode documents) {
        List<TeachingTaskResponse.AssetPlacement> placements = new ArrayList<>();
        for (String stage : List.of("teacher_writer", "student_writer", "lecture_writer")) {
            for (JsonNode item : documents.path(stage).path("assetPlacements")) {
                String logicalPath = item.path("logicalPath").asText("").strip();
                String markdownLine = item.path("markdownLine").asText("").strip();
                String anchorBefore = item.path("anchorBefore").asText("");
                String anchorAfter = item.path("anchorAfter").asText("");
                String layout = item.path("layout").asText("");
                String caption = item.path("caption").asText("");
                List<String> variants = stringValues(item.path("variants"), 3);
                if (logicalPath.isBlank() || !markdownLine.startsWith("![") || !markdownLine.contains("](")
                        || logicalPath.contains("..") || logicalPath.contains("://")
                        || markdownLine.contains("http://") || markdownLine.contains("https://")
                        || !("single".equals(layout) || "vertical_sequence".equals(layout) || "two_column".equals(layout))
                        || variants.isEmpty() || !variants.contains(stage)
                        || variants.stream().anyMatch(variant -> !List.of(
                                "teacher_writer", "student_writer", "lecture_writer").contains(variant))) {
                    throw new IllegalStateException("Python handout returned invalid source-image placement");
                }
                TeachingTaskResponse.AssetPlacement placement = new TeachingTaskResponse.AssetPlacement(
                        logicalPath, markdownLine, anchorBefore, anchorAfter, layout, variants, caption);
                if (!placements.contains(placement)) {
                    placements.add(placement);
                }
            }
        }
        return List.copyOf(placements);
    }

    private static List<String> stringValues(JsonNode values, int maxItems) {
        List<String> result = new ArrayList<>();
        for (JsonNode value : values) {
            String text = value.asText("").strip();
            if (!text.isBlank() && !result.contains(text)) {
                result.add(text);
            }
        }
        return result.size() <= maxItems ? List.copyOf(result) : List.of();
    }

    /** 使用与 Broker 相同的运行级签发规则，禁止将来源范围或内部块 ID 放到模型边界。 */
    private List<String> evidenceRefs(String taskId, List<TeachingEvidence> evidence) {
        if (evidence == null || evidence.isEmpty()) {
            return List.of();
        }
        return evidence.stream()
                .filter(item -> item != null)
                .map(item -> evidenceRef(taskId, item))
                .distinct()
                .limit(MAX_EVIDENCE_REFS)
                .toList();
    }

    private String evidenceRef(String taskId, TeachingEvidence evidence) {
        // 必须与 AgentToolBrokerController.evidenceRef / MultiAgentWritingService.issuedEvidenceRef 完全同构
        //（含 "|assets=" 分量）。缺该分量时，任何带 assetIds 的证据在 handout-context 回调侧重算指纹
        // 都会对不上，导致整个讲义任务 403 HANDOUT_BROKER_CLIENT_FAILURE。
        String assets = evidence.assetIds() == null
                ? ""
                : evidence.assetIds().stream().sorted().collect(java.util.stream.Collectors.joining(","));
        return "ev_" + fingerprint(taskId + "|evidence|" + evidence.sourceDocumentId() + "|"
                + evidence.sourceScope() + "|" + evidence.sourceTitle() + "|" + evidence.chunkId()
                + "|assets=" + assets);
    }

    private String fingerprint(String value) {
        try {
            String secret = environment.getProperty("math-agent.agent-worker.shared-key", "");
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest((secret + "|" + value).getBytes(StandardCharsets.UTF_8));
            StringBuilder encoded = new StringBuilder(digest.length * 2);
            for (byte item : digest) {
                encoded.append(String.format("%02x", item));
            }
            return encoded.substring(0, 32);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static String bounded(String value, int limit) {
        String normalized = value == null ? "" : value.strip();
        return normalized.length() <= limit ? normalized : normalized.substring(0, Math.max(0, limit - 3)) + "...";
    }

    /** Produces a W3C trace id without placing subject or prompt text on the worker wire contract. */
    private static String traceparent(String taskId) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest((taskId == null ? "" : taskId).getBytes(StandardCharsets.UTF_8));
            StringBuilder encoded = new StringBuilder(digest.length * 2);
            for (byte item : digest) {
                encoded.append(String.format("%02x", item));
            }
            return "00-" + encoded.substring(0, TRACE_ID_HEX_LENGTH)
                    + "-" + encoded.substring(TRACE_ID_HEX_LENGTH, TRACE_ID_HEX_LENGTH + SPAN_ID_HEX_LENGTH)
                    + "-01";
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
