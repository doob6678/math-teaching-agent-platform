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
    private final TextbookMilvusSearchClient milvusSearchClient;

    public TextbookPageTextSearchService(
            VectorIndexProperties vectorIndexProperties,
            VectorHttpTransport transport,
            TextbookPageImageService pageImageService) {
        this.vectorIndexProperties = vectorIndexProperties;
        this.transport = transport;
        this.pageImageService = pageImageService;
        this.milvusSearchClient = new TextbookMilvusSearchClient(vectorIndexProperties, transport);
    }

    public TextbookPageTextSearchResponse search(TextbookPageTextSearchRequest request) {
        TextbookPageTextSearchRequest normalized = request == null
                ? new TextbookPageTextSearchRequest("", 1, List.of())
                : request;
        if (normalized.query().isBlank()) {
            throw new IllegalArgumentException("query is required");
        }
        requireWorkerConfigured();
        List<TextbookMilvusSearchClient.MilvusHit> milvusHits = milvusSearchClient.searchText(
                normalized.query(), normalized.limit(), normalized.docIds());
        List<TextbookPageTextSearchHit> hits = new ArrayList<>();
        java.util.Set<String> seenSections = new java.util.LinkedHashSet<>();
        for (TextbookMilvusSearchClient.MilvusHit item : milvusHits) {
            JsonNode metadata = item.metadata();
            String docId = metadata.path("doc_id").asText("");
            int pageNo = metadata.path("page_no").asInt(0);
            if (!docId.isBlank() && pageNo > 0) {
                String sectionId = metadata.path("section_id").asText(metadata.path("chunk_id").asText(""));
                if (sectionId.isBlank() || !seenSections.add(docId + "#" + sectionId)) {
                    continue;
                }
                hits.add(new TextbookPageTextSearchHit(
                        item.score(),
                        metadata.path("chunk_id").asText(""),
                        sectionId,
                        metadata.path("source_chunk_id").asText(""),
                        docId,
                        metadata.path("book_name").asText(""),
                        metadata.path("chapter_path").asText(""),
                        pageNo,
                        metadata.path("printed_page_no").asText(""),
                        metadata.path("section_title").asText(""),
                        item.text(),
                        pageImageService.pageImageUri(docId, pageNo)));
            }
        }
        return new TextbookPageTextSearchResponse(
                normalized.query(), normalized.limit(), "milvus", vectorIndexProperties.embeddingModel(), hits.size(), List.copyOf(hits));
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
