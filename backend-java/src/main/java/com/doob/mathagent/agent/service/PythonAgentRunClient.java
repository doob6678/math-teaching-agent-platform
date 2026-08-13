package com.doob.mathagent.agent.service;

import com.doob.mathagent.agent.dto.AgentRunExecuteRequest;
import com.doob.mathagent.agent.vo.AgentRunExecuteResponse;
import com.doob.mathagent.agent.vo.AgentRunPlanResponse;
import com.doob.mathagent.infrastructure.ai.AiProviderCatalog;
import com.fasterxml.jackson.databind.JsonNode;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
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
 * 通用 Agent 的 Java 到 Python 内部协议适配器。
 *
 * <p>Java 只签发已校验的计划、限额和 provider/model allow-list；Python 负责模型调用、重试、结构化输出和用量记账。</p>
 */
@Service
public class PythonAgentRunClient implements AgentRunClient {

    private static final String CONTRACT_VERSION = "ai-run-v1";
    private static final long MIN_TIMEOUT_MS = 1_000L;
    private static final long DEFAULT_TIMEOUT_MS = 120_000L;
    private static final long DEFAULT_CONNECT_TIMEOUT_MS = 5_000L;
    private static final int MAX_EVIDENCE_REFS = 24;
    private static final int MAX_PROVIDER_CALLS = 4;
    /** A parent lecture task retries its child branch durably, so one selected provider avoids stacking timeouts. */
    private static final int QUESTION_AGENT_MAX_PROVIDER_CALLS = 1;
    private static final String TEACHER_ASSISTANT_AGENT = "TeacherAssistantAgent";
    private static final int MAX_OUTPUT_CHARS = 64_000;
    private static final int TRACE_ID_HEX_LENGTH = 32;
    private static final int SPAN_ID_HEX_LENGTH = 16;

    private final Environment environment;
    private final RestClient client;
    private final AiProviderCatalog providerCatalog;
    private final ProviderRouteGrantSigner routeGrantSigner;
    private final long timeoutMs;

    /** 使用任务 lease 预算配置 Worker 请求的连接和读取超时。 */
    public PythonAgentRunClient(
            Environment environment,
            AiProviderCatalog providerCatalog,
            ProviderRouteGrantSigner routeGrantSigner) {
        this.environment = environment;
        this.providerCatalog = providerCatalog;
        this.routeGrantSigner = routeGrantSigner;
        long configuredTimeout = environment.getProperty(
                "math-agent.python-agent.timeout-ms", Long.class, DEFAULT_TIMEOUT_MS);
        long leaseMs = environment.getProperty(
                "math-agent.agent-worker.runtime.lease-seconds", Long.class, 900L) * 1_000L;
        long safetyMarginMs = environment.getProperty(
                "math-agent.python-agent.lease-safety-margin-ms", Long.class, 15_000L);
        this.timeoutMs = Math.max(MIN_TIMEOUT_MS,
                Math.min(configuredTimeout, Math.max(MIN_TIMEOUT_MS, leaseMs - safetyMarginMs)));
        long connectTimeoutMs = Math.min(timeoutMs, environment.getProperty(
                "math-agent.python-agent.connect-timeout-ms", Long.class, DEFAULT_CONNECT_TIMEOUT_MS));
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(Math.max(MIN_TIMEOUT_MS, connectTimeoutMs)))
                .version(HttpClient.Version.HTTP_1_1)
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(Duration.ofMillis(timeoutMs));
        this.client = RestClient.builder()
                .baseUrl(environment.getProperty("math-agent.python-agent.base-url", "http://ai-worker:8091"))
                .requestFactory(requestFactory)
                .build();
    }

    /** 将已签发的 Agent plan 投影为内部 Python 请求。 */
    @Override
    public AgentRunClient.Result execute(
            String traceId, AgentRunExecuteRequest request, AgentRunPlanResponse plan) {
        String workerKey = environment.getProperty(
                "math-agent.python-agent.worker-key", environment.getProperty("math-agent.worker-api-key", ""));
        if (workerKey == null || workerKey.isBlank()) {
            throw new IllegalStateException("Python agent worker key is not configured");
        }
        JsonNode root;
        try {
            root = client.post()
                    .uri("/v1/ai-runs/sync")
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("Authorization", "Bearer " + workerKey)
                    .header("X-Trace-Id", traceId)
                    .body(Map.ofEntries(
                            Map.entry("contractVersion", CONTRACT_VERSION),
                            Map.entry("runId", traceId),
                            Map.entry("workload", "generic_agent"),
                            Map.entry("idempotencyKey", "agent-run:" + traceId),
                            Map.entry("traceparent", traceparent(traceId)),
                            Map.entry("deadlineEpochMs", System.currentTimeMillis() + timeoutMs),
                            Map.entry("providerRoute", providerRoute(plan, traceId)),
                            Map.entry("limits", Map.of(
                                    "maxProviderCalls", maximumProviderCalls(plan),
                                    "maxTotalTokens", Math.max(1, plan.maxInputTokens() + plan.maxOutputTokens()),
                                    // The Worker maps this signed completion cap to the provider's max_tokens;
                                    // maxTotalTokens alone cannot prevent one short task from producing a long reply.
                                    "maxOutputTokens", Math.max(1, plan.maxOutputTokens()),
                                    "maxOutputChars", MAX_OUTPUT_CHARS)),
                            Map.entry("input", Map.of("message", bounded(request.userInputSummary(), 16_000))),
                            Map.entry("evidenceRefs", evidenceRefs(request.evidenceRefs())),
                            Map.entry("allowedTools", brokerTools(plan.allowedToolScopes()))))
                    .retrieve()
                    .body(JsonNode.class);
        } catch (RestClientException exception) {
            throw new IllegalStateException("Python agent request failed", exception);
        }
        return project(root);
    }

    /** 校验 Python 返回的有限协议字段，避免未验证的 Worker 输出进入 Java trace 或公共 API。 */
    static AgentRunClient.Result project(JsonNode root) {
        if (root == null || root.isNull()) {
            throw new IllegalStateException("Python agent returned an empty response");
        }
        if (!CONTRACT_VERSION.equals(root.path("contractVersion").asText())
                || !"COMPLETED".equals(root.path("status").asText())) {
            throw new IllegalStateException("Python agent did not complete the requested contract");
        }
        String provider = bounded(root.path("providerName").asText(), 64);
        String model = bounded(root.path("modelCode").asText(), 160);
        String content = bounded(root.path("generatedContent").asText(), MAX_OUTPUT_CHARS);
        if (provider.isBlank() || model.isBlank() || content.isBlank()) {
            throw new IllegalStateException("Python agent returned an incomplete result");
        }
        JsonNode usage = root.path("actualUsage");
        int prompt = nonNegativeInt(usage.path("promptTokens"), "promptTokens");
        int completion = nonNegativeInt(usage.path("completionTokens"), "completionTokens");
        int total = nonNegativeInt(usage.path("totalTokens"), "totalTokens");
        if (total < prompt || total < completion) {
            throw new IllegalStateException("Python agent usage totals are inconsistent");
        }
        boolean costKnown = root.path("costKnown").asBoolean(false);
        double actualCost = root.path("actualCost").asDouble(-1.0d);
        if (!costKnown) {
            actualCost = -1.0d;
        } else if (actualCost < 0.0d) {
            throw new IllegalStateException("Python agent returned an invalid known cost");
        }
        return new AgentRunClient.Result(
                provider,
                model,
                new AgentRunExecuteResponse.TokenUsage(prompt, completion, total),
                bounded(root.path("message").asText("Python AI run completed."), 512),
                content,
                actualCost,
                costKnown);
    }

    private Map<String, Object> providerRoute(AgentRunPlanResponse plan, String runId) {
        AiProviderCatalog.Provider primary = providerCatalog.preferredProvider(plan.providerName(), plan.modelCode())
                .orElseThrow(() -> new IllegalArgumentException("Agent plan provider/model is not enabled"));
        List<Map<String, String>> fallbacks = new ArrayList<>();
        int maximumCalls = maximumProviderCalls(plan);
        for (AiProviderCatalog.Provider provider : providerCatalog.enabledProviders()) {
            if (!provider.name().equals(primary.name()) && fallbacks.size() < maximumCalls - 1) {
                fallbacks.add(Map.of("name", provider.name(), "model", provider.chatModel()));
            }
        }
        List<ProviderRouteGrantSigner.ProviderRoute> routes = new ArrayList<>();
        routes.add(new ProviderRouteGrantSigner.ProviderRoute(primary.name(), primary.chatModel()));
        fallbacks.stream()
                .map(item -> new ProviderRouteGrantSigner.ProviderRoute(item.get("name"), item.get("model")))
                .forEach(routes::add);
        return Map.of(
                "primary", Map.of("name", primary.name(), "model", primary.chatModel()),
                "fallbacks", List.copyOf(fallbacks),
                "routeGrant", routeGrantSigner.sign(runId, "generic_agent", routes));
    }

    /** Keeps bounded child explanations within the parent lecture lease instead of multiplying provider timeouts. */
    private static int maximumProviderCalls(AgentRunPlanResponse plan) {
        return TEACHER_ASSISTANT_AGENT.equals(plan.agentCode())
                ? QUESTION_AGENT_MAX_PROVIDER_CALLS
                : MAX_PROVIDER_CALLS;
    }

    private static List<String> evidenceRefs(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        return values.stream()
                .map(value -> bounded(value, 320))
                .filter(value -> !value.isBlank())
                .distinct()
                .limit(MAX_EVIDENCE_REFS)
                .toList();
    }

    /** 将业务 scope 映射为固定 broker 工具，不将 Java 内部 scope 名称交给模型。 */
    private static List<String> brokerTools(List<String> scopes) {
        if (scopes == null || scopes.isEmpty()) {
            return List.of();
        }
        List<String> tools = new ArrayList<>();
        if (scopes.stream().anyMatch(scope -> "tool:search:textbook".equals(scope) || "tool:search:private".equals(scope))) {
            tools.add("search_visible_resources");
        }
        return List.copyOf(tools);
    }

    private static int nonNegativeInt(JsonNode value, String field) {
        if (!value.canConvertToInt() || value.asInt() < 0) {
            throw new IllegalStateException("Python agent returned invalid " + field);
        }
        return value.asInt();
    }

    private static String bounded(String value, int limit) {
        String normalized = value == null ? "" : value.strip();
        return normalized.length() <= limit ? normalized : normalized.substring(0, Math.max(0, limit - 3)) + "...";
    }

    /** 使用 run trace 生成不含身份和 prompt 的 W3C traceparent。 */
    private static String traceparent(String traceId) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest((traceId == null ? "" : traceId).getBytes(StandardCharsets.UTF_8));
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
