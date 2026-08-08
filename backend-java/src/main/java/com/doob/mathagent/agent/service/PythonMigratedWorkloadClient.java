package com.doob.mathagent.agent.service;

import com.doob.mathagent.infrastructure.ai.AiProviderCatalog;
import com.fasterxml.jackson.databind.JsonNode;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
    private static final int MAX_FALLBACKS = 3;

    private final Environment environment;
    private final RestClient client;
    private final AiProviderCatalog providerCatalog;
    private final ProviderRouteGrantSigner routeGrantSigner;

    public PythonMigratedWorkloadClient(
            Environment environment,
            AiProviderCatalog providerCatalog,
            ProviderRouteGrantSigner routeGrantSigner) {
        this.environment = environment;
        this.providerCatalog = providerCatalog;
        this.routeGrantSigner = routeGrantSigner;
        long timeoutMs = Math.max(MIN_TIMEOUT_MS, environment.getProperty(
                "math-agent.python-agent.timeout-ms", Long.class, DEFAULT_TIMEOUT_MS));
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(Math.min(timeoutMs, environment.getProperty(
                        "math-agent.python-agent.connect-timeout-ms", Long.class, 5_000L))))
                .version(HttpClient.Version.HTTP_1_1)
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(Duration.ofMillis(timeoutMs));
        this.client = RestClient.builder()
                .baseUrl(environment.getProperty("math-agent.python-agent.base-url", "http://ai-worker:8091"))
                .requestFactory(requestFactory)
                .build();
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
                "providerRoute", providerRoute(runId, "image_transcription")));
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
                "providerRoute", providerRoute(runId, "student_explanation")));
        requireCompleted(root, "student explanation decision");
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
                bounded(root.path("modelCode").asText(), 160));
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
                "providerRoute", providerRoute(runId, "student_explanation")));
        requireCompleted(root, "student explanation");
        List<ExplanationCard> cards = new ArrayList<>();
        for (JsonNode item : root.path("cards")) {
            cards.add(new ExplanationCard(
                    bounded(item.path("cardKey").asText(), 80),
                    bounded(item.path("title").asText(), 160),
                    bounded(item.path("summary").asText(), 8_000),
                    stringArray(item.path("items"), 16, 800),
                    stringArray(item.path("sourceUris"), 24, 320),
                    bounded(item.path("renderMode").asText("text"), 32)));
        }
        return new ExplanationResult(
                bounded(root.path("conversationTitle").asText(), 80),
                List.copyOf(cards),
                usage(root),
                bounded(root.path("providerName").asText(), 64),
                bounded(root.path("modelCode").asText(), 160));
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
        String workerKey = environment.getProperty(
                "math-agent.python-agent.worker-key", environment.getProperty("math-agent.worker-api-key", ""));
        if (workerKey == null || workerKey.isBlank()) {
            throw new IllegalStateException("Python agent worker key is not configured");
        }
        try {
            JsonNode root = client.post()
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
                .map(item -> Map.of(
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
        AiProviderCatalog.Provider primary = providerCatalog.defaultProvider();
        List<Map<String, String>> fallbacks = providerCatalog.enabledProviders().stream()
                .filter(provider -> !provider.name().equals(primary.name())
                        || !provider.chatModel().equals(primary.chatModel()))
                .limit(MAX_FALLBACKS)
                .map(provider -> Map.of("name", provider.name(), "model", provider.chatModel()))
                .toList();
        List<ProviderRouteGrantSigner.ProviderRoute> routes = new java.util.ArrayList<>();
        routes.add(new ProviderRouteGrantSigner.ProviderRoute(primary.name(), primary.chatModel()));
        fallbacks.stream().map(item -> new ProviderRouteGrantSigner.ProviderRoute(item.get("name"), item.get("model")))
                .forEach(routes::add);
        return Map.of(
                "primary", Map.of("name", primary.name(), "model", primary.chatModel()),
                "fallbacks", fallbacks,
                "routeGrant", routeGrantSigner.sign(runId, workload, routes));
    }

    private static void requireCompleted(JsonNode root, String workload) {
        if (!"COMPLETED".equals(root.path("status").asText())) {
            throw new IllegalStateException("Python " + workload + " did not complete");
        }
    }

    private static String bounded(String value, int limit) {
        String normalized = value == null ? "" : value.strip();
        return normalized.length() <= limit ? normalized : normalized.substring(0, Math.max(0, limit - 3)) + "...";
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

    public record ExplanationDecision(
            String decision,
            List<String> tools,
            List<String> queries,
            String conversationTitle,
            List<ExplanationCard> cards,
            Usage usage,
            String providerName,
            String modelCode) {
    }

    public record ExplanationCard(
            String cardKey,
            String title,
            String summary,
            List<String> items,
            List<String> sourceUris,
            String renderMode) {
    }

    public record ExplanationResult(
            String conversationTitle,
            List<ExplanationCard> cards,
            Usage usage,
            String providerName,
            String modelCode) {
    }

    public record Usage(int promptTokens, int completionTokens, int totalTokens) {
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
