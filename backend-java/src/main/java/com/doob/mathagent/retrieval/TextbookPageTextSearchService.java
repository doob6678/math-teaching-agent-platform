package com.doob.mathagent.retrieval;

import com.doob.mathagent.resources.TextbookPageImageService;
import com.doob.mathagent.vector.service.VectorHttpResponse;
import com.doob.mathagent.vector.service.VectorHttpTransport;
import com.doob.mathagent.vector.service.VectorIndexProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * Calls the worker-owned BGE textbook-page index for stage-one document admission.
 *
 * <p>The Java process never loads the embedding model or scans page vectors. Keeping those operations in the warmed
 * worker makes the retrieval path predictable, while this service retains backend control of transport credentials
 * and public page-image URI construction.</p>
 */
@Service
public class TextbookPageTextSearchService {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private final VectorIndexProperties vectorIndexProperties;
    private final VectorHttpTransport transport;
    private final TextbookPageImageService pageImageService;

    public TextbookPageTextSearchService(
            VectorIndexProperties vectorIndexProperties,
            VectorHttpTransport transport,
            TextbookPageImageService pageImageService) {
        this.vectorIndexProperties = vectorIndexProperties;
        this.transport = transport;
        this.pageImageService = pageImageService;
    }

    public TextbookPageTextSearchResponse search(TextbookPageTextSearchRequest request) {
        TextbookPageTextSearchRequest normalized = request == null
                ? new TextbookPageTextSearchRequest("", 1, List.of())
                : request;
        if (normalized.query().isBlank()) {
            throw new IllegalArgumentException("query is required");
        }
        requireWorkerConfigured();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("query", normalized.query());
        body.put("limit", normalized.limit());
        if (!normalized.docIds().isEmpty()) {
            body.put("docIds", normalized.docIds());
        }
        VectorHttpResponse response = transport.postJson(
                endpoint(vectorIndexProperties.embeddingBaseUrl(), "/text/page-search"),
                Map.of("Authorization", "Bearer " + vectorIndexProperties.embeddingApiKey()),
                writeJson(body),
                Duration.ofMillis(vectorIndexProperties.normalizedTimeoutMs()));
        JsonNode root = readJson(response);
        if (!response.success2xx()) {
            throw new IllegalStateException("BGE textbook page search failed: HTTP " + response.statusCode());
        }
        List<TextbookPageTextSearchHit> hits = new ArrayList<>();
        for (JsonNode item : root.path("hits")) {
            String docId = item.path("docId").asText("");
            int pageNo = item.path("pageNo").asInt(0);
            if (!docId.isBlank() && pageNo > 0) {
                hits.add(new TextbookPageTextSearchHit(
                        item.path("score").asDouble(),
                        item.path("chunkId").asText(""),
                        item.path("sectionId").asText(item.path("chunkId").asText("")),
                        item.path("sourceChunkId").asText(""),
                        docId,
                        item.path("bookName").asText(""),
                        item.path("chapterPath").asText(""),
                        pageNo,
                        item.path("printedPageNo").asText(""),
                        item.path("sectionTitle").asText(""),
                        item.path("text").asText(""),
                        pageImageService.pageImageUri(docId, pageNo)));
            }
        }
        return new TextbookPageTextSearchResponse(
                normalized.query(), normalized.limit(), root.path("provider").asText(""), root.path("model").asText(""), hits.size(), List.copyOf(hits));
    }

    private void requireWorkerConfigured() {
        if (vectorIndexProperties.embeddingBaseUrl() == null || vectorIndexProperties.embeddingBaseUrl().isBlank()
                || vectorIndexProperties.embeddingApiKey() == null || vectorIndexProperties.embeddingApiKey().isBlank()) {
            throw new IllegalStateException("local embedding worker base URL and API key are required");
        }
    }

    private static URI endpoint(String baseUrl, String path) {
        String normalized = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        return URI.create(normalized + path);
    }

    private static String writeJson(Object value) {
        try {
            return OBJECT_MAPPER.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to serialize BGE textbook page search request", exception);
        }
    }

    private static JsonNode readJson(VectorHttpResponse response) {
        try {
            return OBJECT_MAPPER.readTree(response.body());
        } catch (Exception exception) {
            throw new IllegalStateException("BGE textbook page search returned invalid JSON", exception);
        }
    }
}
