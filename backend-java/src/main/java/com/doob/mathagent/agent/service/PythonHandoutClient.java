package com.doob.mathagent.agent.service;

import com.doob.mathagent.agent.dto.MultiAgentWritingRequest;
import com.doob.mathagent.agent.vo.AgentRunExecuteResponse;
import com.doob.mathagent.agent.vo.MultiAgentWritingResponse;
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
            String traceId,
            boolean resume) {
        Map<String, Object> payload = requestPayload(workflowId, request, traceId, resume, requestTimeoutMs, environment);
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
        return parse(root, workflowId);
    }

    /**
     * Builds the bounded Python contract without serializing Java authentication state.
     *
     * <p>Python authenticates its broker call using the opaque run id; tenant and subject data remain in Java's
     * workflow store so a model-controlled request cannot broaden authorization.</p>
     */
    static Map<String, Object> requestPayload(
            String workflowId,
            MultiAgentWritingRequest request,
            String traceId,
            boolean resume,
            long timeoutMs,
            Environment environment) {
        return Map.ofEntries(
                Map.entry("runId", workflowId),
                Map.entry("taskId", workflowId),
                Map.entry("contractVersion", HANDOUT_CONTRACT_VERSION),
                Map.entry("writingGoal", request.writingGoal()),
                Map.entry("questionText", request.questionText()),
                Map.entry("evidenceRefs", request.evidenceRefs().stream()
                        .filter(PythonHandoutClient::isIssuedEvidenceRef)
                        .toList()),
                Map.entry("initialEvidence", initialEvidence(request, workflowId, environment)),
                Map.entry("graphVersion", environment.getProperty("math-agent.python-handout.graph-version", "handout-v1")),
                Map.entry("traceId", traceId == null ? workflowId : traceId),
                Map.entry("traceparent", traceparent(traceId == null ? workflowId : traceId)),
                // Reusing this key on lease redelivery lets Python return its durable package without another model call.
                Map.entry("idempotencyKey", "handout:" + workflowId),
                // The same run id lets Python reuse durable node checkpoints after a queue retry.
                Map.entry("resume", resume),
                Map.entry("deadlineEpochMs", System.currentTimeMillis() + timeoutMs));
    }

    private static List<Map<String, Object>> initialEvidence(
            MultiAgentWritingRequest request, String workflowId, Environment environment) {
        String sharedKey = environment.getProperty("math-agent.agent-worker.shared-key", "");
        List<com.doob.mathagent.teaching.TeachingEvidence> evidence = request.initialEvidence();
        List<String> refs = request.evidenceRefs().stream()
                .filter(PythonHandoutClient::isIssuedEvidenceRef)
                .toList();
        List<Map<String, Object>> result = new ArrayList<>();
        for (int index = 0; index < evidence.size() && index < refs.size(); index++) {
            com.doob.mathagent.teaching.TeachingEvidence item = evidence.get(index);
            String ref = refs.get(index);
            Map<String, Object> row = new java.util.LinkedHashMap<>();
            row.put("ref", ref);
            row.put("title", item.sourceTitle());
            row.put("documentName", item.sourceTitle());
            // This opaque reference must use the identical run-scoped secret namespace as AgentToolBrokerController;
            // otherwise Python can select an initial evidence document that the broker cannot subsequently authorize.
            row.put("documentRef", item.sourceDocumentId().isBlank() ? ""
                    : "doc_" + fingerprint(sharedKey + "|" + workflowId + "|document|" + item.sourceDocumentId()));
            row.put("transparentRef", transparentReference(item));
            row.put("pageNo", item.pageNo());
            row.put("excerpt", item.snippet());
            row.put("imageRefs", item.imageRefs());
            result.add(row);
        }
        return List.copyOf(result);
    }

    private static String transparentReference(com.doob.mathagent.teaching.TeachingEvidence item) {
        String scope = item.sourceScope();
        if ("PUBLIC_TEXTBOOK".equals(scope) && !item.sourceDocumentId().isBlank() && !item.chunkId().isBlank()) {
            return "textbook://" + item.sourceDocumentId() + "/chunk/" + item.chunkId();
        }
        if ("TEACHER_RESOURCE".equals(scope) && !item.sourceDocumentId().isBlank() && !item.chunkId().isBlank()) {
            return "feishu://group/TEACHER_SHARED/resource/" + item.sourceDocumentId() + "/block/" + item.chunkId();
        }
        if ("CANONICAL_MATH_PAPER".equals(scope)
                && !item.sourceDocumentId().isBlank() && !item.canonicalQuestionNumber().isBlank()) {
            return "gaokao://canonical/" + item.sourceDocumentId() + "/question/" + item.canonicalQuestionNumber();
        }
        return "";
    }

    private static String fingerprint(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest, 0, 16);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static boolean isIssuedEvidenceRef(String value) {
        return value != null && value.matches("ev_[0-9a-f]{32}");
    }

    private PythonHandoutResult parse(JsonNode root, String workflowId) {
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
        String generated = generatedMarkdown(content);
        AgentRunExecuteResponse.TokenUsage usage = new AgentRunExecuteResponse.TokenUsage(
                intValue(metric, "promptTokens"), intValue(metric, "completionTokens"), intValue(metric, "totalTokens"));
        long elapsed = metric.path("elapsedMs").asLong(0L);
        return new MultiAgentWritingResponse.StageResult(
                stageCode, agentCode, workflowId + ":" + stageCode, provider, model,
                "COMPLETED".equals(graphStatus) ? "COMPLETED" : "FAILED", usage,
                "COMPLETED".equals(graphStatus) ? "Python LangGraph node completed." : "Python LangGraph node failed.",
                generated, elapsed, citations(content), assetPlacements(content));
    }

    /** Projects only writer-authored Markdown while preserving opaque asset placement data for the exporter. */
    private static String generatedMarkdown(JsonNode content) {
        if (content == null || content.isMissingNode() || content.isNull()) {
            return "";
        }
        JsonNode markdown = content.path("markdown");
        if (!markdown.isTextual() || markdown.asText().isBlank()) {
            return content.toString();
        }
        return markdown.asText();
    }

    private static List<String> citations(JsonNode content) {
        List<String> result = new ArrayList<>();
        JsonNode values = content == null ? null : content.path("citations");
        if (values == null || !values.isArray()) {
            values = content == null ? null : content.path("items");
        }
        if (values != null && values.isArray()) {
            for (JsonNode value : values) {
                String candidate = value.isTextual() ? value.asText() : text(value, "ref", "");
                String transparent = value.isObject() ? text(value, "transparentRef", "") : "";
                if (candidate.matches("ev_[0-9a-f]{32}") && !result.contains(candidate)) result.add(candidate);
                if (transparent.matches("(?:textbook|feishu|gaokao)://[^\\s]{1,500}") && !result.contains(transparent)) {
                    result.add(transparent);
                }
                if (result.size() >= 48) break;
            }
        }
        return List.copyOf(result);
    }

    private static List<MultiAgentWritingResponse.AssetPlacement> assetPlacements(JsonNode content) {
        List<MultiAgentWritingResponse.AssetPlacement> result = new ArrayList<>();
        JsonNode values = content == null ? null : content.path("assetPlacements");
        if (values != null && values.isArray()) {
            for (JsonNode value : values) {
                String logicalPath = text(value, "logicalPath", "");
                String markdownLine = text(value, "markdownLine", "");
                String anchorBefore = text(value, "anchorBefore", "");
                String anchorAfter = text(value, "anchorAfter", "");
                String layout = text(value, "layout", "");
                List<String> variants = textArray(value.path("variants"), "(teacher_writer|student_writer|lecture_writer)", 3);
                if (!logicalPath.isBlank() && markdownLine.startsWith("![")
                        && markdownLine.contains("](")
                        && layout.matches("single|vertical_sequence|two_column") && !variants.isEmpty()) {
                    result.add(new MultiAgentWritingResponse.AssetPlacement(
                            logicalPath, markdownLine, anchorBefore, anchorAfter, layout, variants,
                            text(value, "caption", "").substring(0, Math.min(text(value, "caption", "").length(), 1000))));
                }
            }
        }
        return List.copyOf(result.subList(0, Math.min(result.size(), 48)));
    }

    private static List<String> textArray(JsonNode node, String pattern, int max) {
        List<String> result = new ArrayList<>();
        if (node != null && node.isArray()) {
            for (JsonNode value : node) {
                if (value.isTextual() && value.asText().matches(pattern) && !result.contains(value.asText())) result.add(value.asText());
                if (result.size() >= max) break;
            }
        }
        return result;
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
