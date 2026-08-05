package com.doob.mathagent.agent.service;

import com.doob.mathagent.agent.dto.MultiAgentWritingRequest;
import com.doob.mathagent.agent.vo.AgentRunExecuteResponse;
import com.doob.mathagent.agent.vo.MultiAgentWritingResponse;
import com.doob.mathagent.infrastructure.security.RequestSubject;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import org.springframework.core.env.Environment;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;

/**
 * One-request Java boundary for the Python LangGraph handout runtime.
 *
 * <p>The client sends only the normalized writing request and opaque evidence refs. Python never receives a Java
 * database connection, local path, browser identity, model credential, or raw asset bytes. Java keeps ownership of
 * the returned workflow snapshot and publication gate.</p>
 */
@Service
public class PythonHandoutClient {
    private static final long MIN_TIMEOUT_MS = 1_000L;
    private static final long DEFAULT_REQUEST_TIMEOUT_MS = 900_000L;
    private static final long DEFAULT_LEASE_SECONDS = 900L;
    private static final long DEFAULT_LEASE_SAFETY_MARGIN_MS = 15_000L;
    private static final long DEFAULT_CONNECT_TIMEOUT_MS = 5_000L;
    private static final String HANDOUT_CONTRACT_VERSION = "handout-ai-v1";
    private final Environment environment;
    private final ObjectMapper objectMapper;
    private final RestClient client;
    private final long requestTimeoutMs;

    public PythonHandoutClient(Environment environment, ObjectMapper objectMapper) {
        this.environment = environment;
        this.objectMapper = objectMapper;
        String baseUrl = environment.getProperty("math-agent.python-handout.base-url", "http://ai-worker:8091");
        long configuredTimeout = environment.getProperty(
                "math-agent.python-handout.timeout-ms", Long.class, DEFAULT_REQUEST_TIMEOUT_MS);
        long leaseMs = environment.getProperty(
                "math-agent.agent-worker.runtime.lease-seconds", Long.class, DEFAULT_LEASE_SECONDS) * 1000L;
        long leaseSafetyMarginMs = environment.getProperty(
                "math-agent.python-handout.lease-safety-margin-ms", Long.class, DEFAULT_LEASE_SAFETY_MARGIN_MS);
        // The HTTP budget must expire before RabbitMQ can reclaim the lease; otherwise the old Worker can finish after
        // redelivery and publish a duplicate package. Both connect and read timeouts use this one bounded deadline.
        this.requestTimeoutMs = Math.max(MIN_TIMEOUT_MS, Math.min(configuredTimeout, Math.max(MIN_TIMEOUT_MS, leaseMs - leaseSafetyMarginMs)));
        long connectTimeoutMs = Math.min(
                requestTimeoutMs,
                environment.getProperty("math-agent.python-handout.connect-timeout-ms", Long.class, DEFAULT_CONNECT_TIMEOUT_MS));
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(Math.max(MIN_TIMEOUT_MS, connectTimeoutMs)))
                .version(HttpClient.Version.HTTP_1_1)
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(Duration.ofMillis(requestTimeoutMs));
        // RestClient is built once so TCP connection reuse covers the complete production handout workload.
        this.client = RestClient.builder().baseUrl(baseUrl).requestFactory(requestFactory).build();
    }

    /** Calls the complete graph once and converts its bounded package to the existing workflow stage contract. */
    public PythonHandoutResult execute(
            String workflowId,
            MultiAgentWritingRequest request,
            RequestSubject subject,
            String traceId,
            boolean resume) {
        Map<String, Object> payload = Map.of(
                "runId", workflowId,
                "taskId", workflowId,
                "contractVersion", HANDOUT_CONTRACT_VERSION,
                "writingGoal", request.writingGoal(),
                "questionText", request.questionText(),
                "evidenceRefs", request.evidenceRefs(),
                "graphVersion", environment.getProperty("math-agent.python-handout.graph-version", "handout-v1"),
                "traceId", traceId == null ? workflowId : traceId,
                "traceparent", traceparent(traceId == null ? workflowId : traceId),
                // Reusing this key on lease redelivery lets Python return its durable package without another model call.
                "idempotencyKey", "handout:" + workflowId,
                // The same run id lets Python reuse durable node checkpoints after a queue retry.
                "resume", resume,
                "deadlineEpochMs", System.currentTimeMillis() + requestTimeoutMs);
        String workerKey = environment.getProperty(
                "math-agent.python-handout.worker-key",
                environment.getProperty("math-agent.worker-api-key", ""));
        if (workerKey == null || workerKey.isBlank()) {
            throw new IllegalStateException("Python handout worker key is not configured");
        }
        JsonNode root = client.post()
                .uri("/v1/handout-runs/sync")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer " + workerKey)
                .header("X-Trace-Id", traceId == null ? workflowId : traceId)
                .body(payload)
                .retrieve()
                .body(JsonNode.class);
        if (root == null || root.isNull()) {
            throw new IllegalStateException("Python handout returned an empty response");
        }
        return parse(root, workflowId, subject);
    }

    /** Maps one Python document to the existing stage result without copying raw prompt data. */
    private PythonHandoutResult parse(JsonNode root, String workflowId, RequestSubject subject) {
        String status = text(root, "status", "FAILED");
        JsonNode metrics = root.path("metrics");
        JsonNode documents = root.path("documents");
        JsonNode evidence = root.path("evidence");
        List<MultiAgentWritingResponse.StageResult> stages = new ArrayList<>();
        stages.add(stage("resource_curation", "TeacherAssistantAgent", root.path("runId").asText(workflowId),
                evidence, nodeMetric(metrics, "resource_curation"), status));
        stages.add(stage("teacher_writer", "CoursewareAgent", root.path("runId").asText(workflowId),
                documents.path("teacher_writer"), nodeMetric(metrics, "teacher_writer"), status));
        stages.add(stage("student_writer", "TeacherAssistantAgent", root.path("runId").asText(workflowId),
                documents.path("student_writer"), nodeMetric(metrics, "student_writer"), status));
        stages.add(stage("lecture_writer", "HandoutFormatterAgent", root.path("runId").asText(workflowId),
                documents.path("lecture_writer"), nodeMetric(metrics, "lecture_writer"), status));
        int prompt = intValue(metrics, "promptTokens");
        int completion = intValue(metrics, "completionTokens");
        int total = intValue(metrics, "totalTokens");
        AgentRunExecuteResponse.TokenUsage usage = new AgentRunExecuteResponse.TokenUsage(prompt, completion, total);
        return new PythonHandoutResult(status, stages, usage, text(root, "graphVersion", "handout-v1"), text(root.path("validation"), "errors", ""));
    }

    private MultiAgentWritingResponse.StageResult stage(
            String stageCode,
            String agentCode,
            String workflowId,
            JsonNode content,
            JsonNode metric,
            String graphStatus) {
        String provider = text(metric, "provider", "python-langgraph");
        String model = text(metric, "model", "");
        String generated = content == null || content.isMissingNode() ? "" : content.toString();
        AgentRunExecuteResponse.TokenUsage usage = new AgentRunExecuteResponse.TokenUsage(
                intValue(metric, "promptTokens"), intValue(metric, "completionTokens"), intValue(metric, "totalTokens"));
        long elapsed = metric.path("elapsedMs").asLong(0L);
        return new MultiAgentWritingResponse.StageResult(
                stageCode, agentCode, workflowId + ":" + stageCode, provider, model,
                "COMPLETED".equals(graphStatus) ? "COMPLETED" : "FAILED", usage,
                "COMPLETED".equals(graphStatus) ? "Python LangGraph node completed." : "Python LangGraph node failed.",
                generated, elapsed);
    }

    private static JsonNode nodeMetric(JsonNode metrics, String node) {
        for (JsonNode metric : metrics.path("nodeMetrics")) {
            if (node.equals(metric.path("node").asText())) return metric;
        }
        return JsonNodeFactory.instance.objectNode();
    }

    private static String text(JsonNode node, String field, String fallback) {
        JsonNode value = node == null ? null : node.get(field);
        return value != null && value.isValueNode() ? value.asText(fallback) : fallback;
    }

    private static int intValue(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null ? 0 : value.asInt(0);
    }

    /** Builds a W3C-compatible traceparent from an opaque internal correlation id without exposing request content. */
    private static String traceparent(String correlationId) {
        String digest = sha256(correlationId == null ? "" : correlationId);
        return "00-" + digest.substring(0, 32) + "-" + digest.substring(32, 48) + "-01";
    }

    private static String sha256(String value) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder encoded = new StringBuilder(bytes.length * 2);
            for (byte item : bytes) encoded.append(String.format("%02x", item));
            return encoded.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    /** Result used by the Java workflow store; it deliberately contains no Python checkpoint internals. */
    public record PythonHandoutResult(
            String status,
            List<MultiAgentWritingResponse.StageResult> stages,
            AgentRunExecuteResponse.TokenUsage usage,
            String graphVersion,
            String validationErrors) {
    }
}
