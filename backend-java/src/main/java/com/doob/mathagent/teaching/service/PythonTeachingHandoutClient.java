package com.doob.mathagent.teaching.service;

import com.doob.mathagent.teaching.TeachingEvidence;
import com.doob.mathagent.teaching.dto.TeachingTaskRequest;
import com.doob.mathagent.teaching.vo.TeachingTaskResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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

/**
 * Teaching-task adapter for the single Python handout graph.
 *
 * <p>The graph receives only a task/run identifier and opaque evidence references. Java retains task ownership,
 * evidence authorization and publication; the returned teacher, student and lecture documents are projected onto
 * the existing durable teaching-task draft rather than creating a second workflow row.</p>
 */
@Service
public class PythonTeachingHandoutClient implements TeachingHandoutAiClient {

    private static final String CONTRACT_VERSION = "handout-ai-v1";
    private static final long MIN_TIMEOUT_MS = 1_000L;
    private static final long DEFAULT_TIMEOUT_MS = 900_000L;
    private static final long DEFAULT_CONNECT_TIMEOUT_MS = 5_000L;
    private static final int MAX_EVIDENCE_REFS = 24;
    private static final int TRACE_ID_HEX_LENGTH = 32;
    private static final int SPAN_ID_HEX_LENGTH = 16;

    private final Environment environment;
    private final RestClient client;
    private final long timeoutMs;

    /** Configures bounded connect/read timeouts from the same task lease budget as the Worker. */
    public PythonTeachingHandoutClient(Environment environment, ObjectMapper objectMapper) {
        this.environment = environment;
        long configuredTimeout = environment.getProperty("math-agent.python-handout.timeout-ms", Long.class, DEFAULT_TIMEOUT_MS);
        long leaseMs = environment.getProperty("math-agent.agent-worker.runtime.lease-seconds", Long.class, 900L) * 1_000L;
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
                        Map.entry("evidenceRefs", evidenceRefs(evidence)),
                        Map.entry("graphVersion", environment.getProperty("math-agent.python-handout.graph-version", "handout-v1")),
                        Map.entry("idempotencyKey", "teaching-handout:" + taskId),
                        Map.entry("traceparent", traceparent(taskId)),
                        Map.entry("resume", true),
                        Map.entry("deadlineEpochMs", System.currentTimeMillis() + timeoutMs)))
                .retrieve()
                .body(JsonNode.class);
        if (root == null || root.isNull()) {
            throw new IllegalStateException("Python handout returned an empty response");
        }
        return project(root);
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
                List.copyOf(events));
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

    private static List<String> evidenceRefs(List<TeachingEvidence> evidence) {
        if (evidence == null || evidence.isEmpty()) {
            return List.of();
        }
        return evidence.stream()
                .filter(item -> item != null)
                .map(item -> bounded(item.sourceScope(), 80) + ":" + bounded(item.chunkId(), 240))
                .filter(item -> !item.equals(":"))
                .distinct()
                .limit(MAX_EVIDENCE_REFS)
                .toList();
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
