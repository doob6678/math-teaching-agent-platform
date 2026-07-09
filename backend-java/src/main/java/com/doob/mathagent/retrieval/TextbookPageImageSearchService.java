package com.doob.mathagent.retrieval;

import com.doob.mathagent.resources.TextbookPageImageService;
import com.doob.mathagent.vector.service.VectorHttpResponse;
import com.doob.mathagent.vector.service.VectorHttpTransport;
import com.doob.mathagent.vector.service.VectorIndexProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * Proxies public textbook CLIP page-image search to the local worker.
 *
 * <p>The worker already owns the heavy CLIP runtime and the reused page-image index under processed_books. Keep Java
 * focused on access control, request validation, and URL shaping instead of reloading numpy indexes or model weights
 * inside the backend JVM.</p>
 */
@Service
public class TextbookPageImageSearchService {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final VectorIndexProperties vectorIndexProperties;
    private final VectorHttpTransport transport;
    private final TextbookPageImageService pageImageService;

    public TextbookPageImageSearchService(
            VectorIndexProperties vectorIndexProperties,
            VectorHttpTransport transport,
            TextbookPageImageService pageImageService) {
        this.vectorIndexProperties = vectorIndexProperties;
        this.transport = transport;
        this.pageImageService = pageImageService;
    }

    /**
     * Searches the reused processed_books page-image index through the worker CLIP endpoint.
     */
    public TextbookPageImageSearchResponse search(
            TextbookPageImageSearchRequest request) {
        TextbookPageImageSearchRequest normalized = request == null
                ? new TextbookPageImageSearchRequest(null, null, 10, List.of())
                : request;
        String query = text(normalized.query());
        String image = text(normalized.image());
        if (query.isBlank() && image.isBlank()) {
            throw new IllegalArgumentException("query or image is required");
        }
        requireWorkerConfigured();
        Map<String, Object> body = new LinkedHashMap<>();
        if (!query.isBlank()) {
            body.put("texts", List.of(query));
        }
        if (!image.isBlank()) {
            body.put("images", List.of(image));
        }
        body.put("limit", normalized.normalizedLimit());
        List<String> docIds = normalizeDocIds(normalized.docIds());
        if (!docIds.isEmpty()) {
            body.put("docIds", docIds);
        }
        VectorHttpResponse response = transport.postJson(
                endpoint(vectorIndexProperties.embeddingBaseUrl(), "/clip/page-search"),
                Map.of("Authorization", "Bearer " + vectorIndexProperties.embeddingApiKey()),
                writeJson(body),
                Duration.ofMillis(vectorIndexProperties.normalizedTimeoutMs()));
        JsonNode root = readJson(response);
        if (!response.success2xx()) {
            throw new IllegalStateException("CLIP textbook page search failed: HTTP " + response.statusCode()
                    + " body=" + abbreviate(response.body(), 300));
        }
        List<TextbookPageImageSearchHit> hits = new ArrayList<>();
        for (JsonNode item : root.path("hits")) {
            String docId = item.path("docId").asText("");
            int pageNo = item.path("pageNo").asInt(0);
            hits.add(new TextbookPageImageSearchHit(
                    item.path("score").asDouble(0.0),
                    docId,
                    item.path("bookName").asText(""),
                    repairMojibake(item.path("chapterPath").asText("")),
                    pageNo,
                    repairMojibake(item.path("printedPageNo").asText("")),
                    repairMojibake(item.path("sectionTitle").asText("")),
                    repairMojibake(item.path("text").asText("")),
                    pageImageService.pageImageUri(docId, pageNo)));
        }
        return new TextbookPageImageSearchResponse(
                query,
                normalized.normalizedLimit(),
                root.path("provider").asText(""),
                root.path("model").asText(""),
                hits.size(),
                List.copyOf(hits));
    }

    private void requireWorkerConfigured() {
        if (vectorIndexProperties.embeddingBaseUrl() == null || vectorIndexProperties.embeddingBaseUrl().isBlank()) {
            throw new IllegalStateException("MATH_AGENT_EMBEDDING_BASE_URL must point to the local worker");
        }
        if (vectorIndexProperties.embeddingApiKey() == null || vectorIndexProperties.embeddingApiKey().isBlank()) {
            throw new IllegalStateException("MATH_AGENT_EMBEDDING_API_KEY or MATH_AGENT_WORKER_API_KEY must be configured");
        }
    }

    private static List<String> normalizeDocIds(List<String> docIds) {
        if (docIds == null || docIds.isEmpty()) {
            return List.of();
        }
        return docIds.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(String::strip)
                .distinct()
                .toList();
    }

    private static URI endpoint(String baseUrl, String path) {
        String normalizedBase = text(baseUrl);
        normalizedBase = normalizedBase.endsWith("/") ? normalizedBase.substring(0, normalizedBase.length() - 1) : normalizedBase;
        return URI.create(normalizedBase + path);
    }

    private static JsonNode readJson(VectorHttpResponse response) {
        try {
            return OBJECT_MAPPER.readTree(response.body());
        } catch (Exception exception) {
            throw new IllegalStateException("CLIP textbook page search returned invalid JSON: HTTP "
                    + response.statusCode() + " body=" + abbreviate(response.body(), 300), exception);
        }
    }

    private static String writeJson(Object value) {
        try {
            return OBJECT_MAPPER.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to serialize CLIP textbook page search request", exception);
        }
    }

    private static String abbreviate(String value, int maxLength) {
        String safeValue = value == null ? "" : value.replace('\n', ' ').replace('\r', ' ');
        return safeValue.length() <= maxLength ? safeValue : safeValue.substring(0, maxLength) + "...";
    }

    private static String text(String value) {
        return value == null ? "" : value.strip();
    }

    /**
     * Repairs common UTF-8 text that was accidentally interpreted as ISO-8859-1 by a downstream provider.
     */
    static String repairMojibake(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        if (!looksLikeMojibake(value)) {
            return value;
        }
        String repaired = new String(value.getBytes(StandardCharsets.ISO_8859_1), StandardCharsets.UTF_8);
        return cjkCount(repaired) > cjkCount(value) ? repaired : value;
    }

    private static boolean looksLikeMojibake(String value) {
        return value.indexOf('\u00e9') >= 0
                || value.indexOf('\u00e8') >= 0
                || value.indexOf('\u00e4') >= 0
                || value.indexOf('\u00e5') >= 0
                || value.indexOf('\u00e3') >= 0
                || value.indexOf('\u00ef') >= 0
                || value.indexOf('\u0098') >= 0
                || value.indexOf('\u0080') >= 0
                || value.indexOf('\u0082') >= 0;
    }

    private static long cjkCount(String value) {
        return value.codePoints()
                .filter(codePoint -> codePoint >= 0x4E00 && codePoint <= 0x9FFF)
                .count();
    }
}
