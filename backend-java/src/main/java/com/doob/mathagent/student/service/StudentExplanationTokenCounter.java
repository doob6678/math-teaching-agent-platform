package com.doob.mathagent.student.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Calls the real local worker tokenizer before context selection.
 * Provider usage returned after generation remains the authoritative count for the complete text+image request.
 */
@Service
public class StudentExplanationTokenCounter {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private final HttpClient client = HttpClient.newHttpClient();
    private final String baseUrl;
    private final String apiKey;
    private final Duration timeout;

    public StudentExplanationTokenCounter(
            @Value("${math-agent.vector-index.embedding-base-url:}") String baseUrl,
            @Value("${math-agent.vector-index.embedding-api-key:}") String apiKey,
            @Value("${math-agent.vector-index.request-timeout-ms:30000}") long timeoutMs) {
        this.baseUrl = baseUrl == null ? "" : baseUrl.strip();
        this.apiKey = apiKey == null ? "" : apiKey.strip();
        this.timeout = Duration.ofMillis(Math.max(1000, timeoutMs));
    }

    /** Returns worker tokenizer ids, or an unavailable result without fabricating counts. */
    public TokenCount count(List<String> texts, String model) {
        if (baseUrl.isBlank() || apiKey.isBlank() || texts == null || texts.isEmpty()) {
            return TokenCount.unavailable("worker_tokenizer_not_configured");
        }
        try {
            String body = OBJECT_MAPPER.writeValueAsString(new Request(texts, model == null ? "" : model));
            HttpRequest request = HttpRequest.newBuilder(endpoint())
                    .timeout(timeout)
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) return TokenCount.unavailable("worker_tokenizer_http_" + response.statusCode());
            JsonNode root = OBJECT_MAPPER.readTree(response.body());
            if (!root.path("counts").isArray()) return TokenCount.unavailable("worker_tokenizer_invalid_response");
            List<Integer> counts = new java.util.ArrayList<>();
            root.path("counts").forEach(node -> counts.add(Math.max(0, node.asInt())));
            return new TokenCount(List.copyOf(counts), Math.max(0, root.path("total").asInt()), root.path("encoding").asText(""), "");
        } catch (Exception exception) {
            return TokenCount.unavailable("worker_tokenizer_" + exception.getClass().getSimpleName());
        }
    }

    private URI endpoint() {
        return URI.create(baseUrl.replaceAll("/+$", "") + "/tokenize");
    }

    private record Request(List<String> texts, String model) {
    }

    /** Exact local tokenizer result; unavailable never means estimated. */
    public record TokenCount(List<Integer> counts, int total, String encoding, String failureCode) {
        static TokenCount unavailable(String failureCode) {
            return new TokenCount(List.of(), 0, "", failureCode);
        }

        public boolean available() {
            return failureCode == null || failureCode.isBlank();
        }
    }
}
