package com.doob.mathagent.vector.service;

import com.doob.mathagent.teacher.block.TeacherDocumentBlockStore;
import com.doob.mathagent.teacher.document.TeacherResourceStore;
import com.doob.mathagent.teacher.block.TeacherDocumentBlockResponse;
import com.doob.mathagent.teacher.document.TeacherResourceDocumentResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Rebuilds teacher resource vector indexes with a real embedding API and Milvus REST.
 */
@Service
public class VectorIndexService {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final Logger log = LoggerFactory.getLogger(VectorIndexService.class);
    private static final int EMBEDDING_BATCH_SIZE = 32;
    private static final int MILVUS_UPSERT_BATCH_SIZE = 128;
    private static final int MILVUS_RATE_LIMIT_RETRY_ATTEMPTS = 4;
    private static final Duration MILVUS_RATE_LIMIT_RETRY_DELAY = Duration.ofSeconds(12);
    private static final int VECTOR_SEARCH_RETRY_ATTEMPTS = 3;

    private final VectorIndexProperties properties;
    private final VectorHttpTransport transport;
    private final TeacherResourceStore resourceStore;
    private final TeacherDocumentBlockStore blockStore;

    public VectorIndexService(
            VectorIndexProperties properties,
            VectorHttpTransport transport,
            TeacherResourceStore resourceStore,
            TeacherDocumentBlockStore blockStore) {
        this.properties = properties;
        this.transport = transport;
        this.resourceStore = resourceStore;
        this.blockStore = blockStore;
    }

    public VectorIndexStatusResponse status() {
        String baseStatus = properties.enabled()
                ? properties.fullyConfigured() ? "ready_to_index" : "configuration_error"
                : "disabled";
        RuntimeVectorStatus runtime = properties.fullyConfigured()
                ? inspectRuntimeStatus()
                : RuntimeVectorStatus.empty(baseStatus);
        return new VectorIndexStatusResponse(
                properties.enabled(),
                properties.fullyConfigured(),
                properties.normalizedCollectionName(),
                properties.normalizedDimension(),
                safe(properties.embeddingModel()),
                safe(properties.milvusUri()),
                runtime.collectionState(),
                runtime.indexState(),
                runtime.loadState(),
                runtime.rowCount(),
                runtime.status());
    }

    public VectorIndexRebuildResponse rebuildTeacherResource(
            String tenantId,
            String subjectType,
            String subjectId,
            String documentId) {
        if (!"teacher".equals(subjectType) && !"admin".equals(subjectType)) {
            throw new IllegalArgumentException("Vector index rebuild requires teacher or admin role");
        }
        TeacherResourceDocumentResponse document = resourceStore.find(tenantId, documentId);
        if (document == null) {
            throw new IllegalArgumentException("Teacher resource not found: " + documentId);
        }
        if ("teacher".equals(subjectType) && !subjectId.equals(document.ownerSubjectId())) {
            throw new IllegalArgumentException("Teacher can rebuild only owned resource indexes");
        }
        List<TeacherDocumentBlockResponse> blocks = blockStore.listByDocument(tenantId, documentId).stream()
                .filter(block -> !text(block.normalizedText()).isBlank())
                .toList();
        try {
            properties.requireFullyConfigured();
            if (blocks.isEmpty()) {
                return new VectorIndexRebuildResponse(
                        "no_blocks",
                        documentId,
                        properties.normalizedCollectionName(),
                        0,
                        0,
                        0,
                        properties.embeddingModel(),
                        0,
                        "No parsed active blocks are available for indexing.");
            }
            EmbeddingBatch embeddings = embed(blocks.stream().map(TeacherDocumentBlockResponse::normalizedText).toList());
            ensureCollection();
            ensureVectorIndex();
            deleteExistingDocumentVectors(document);
            int upserted = upsert(document, blocks, embeddings.vectors());
            flushCollection();
            loadCollection();
            resourceStore.save(withIndexStatus(document, "ready", "ready"));
            return new VectorIndexRebuildResponse(
                    "indexed",
                    documentId,
                    properties.normalizedCollectionName(),
                    blocks.size(),
                    embeddings.vectors().size(),
                    upserted,
                    properties.embeddingModel(),
                    embeddings.promptTokens(),
                    "Milvus upsert completed.");
        } catch (RuntimeException exception) {
            resourceStore.save(withIndexStatus(document, "failed", "failed"));
            return new VectorIndexRebuildResponse(
                    "failed",
                    documentId,
                    properties.normalizedCollectionName(),
                    blocks.size(),
                    0,
                    0,
                    properties.embeddingModel(),
                    0,
                    "Vector index rebuild failed: " + safe(exception.getMessage()));
        }
    }

    public List<VectorSearchHit> searchTeacherResourceBlocks(String query, int limit) {
        return searchTeacherResourceBlocks(query, limit, VectorSearchFilter.EMPTY);
    }

    public List<VectorSearchHit> searchTeacherResourceBlocks(String query, int limit, VectorSearchFilter filter) {
        return retryVectorSearch("teacher_resource_vector_search", () -> searchTeacherResourceBlocksOnce(query, limit, filter));
    }

    /**
     * Scores one query against candidate texts with the same real embedding endpoint used by Milvus indexing.
     *
     * <p>This is the backend semantic-rerank primitive for stage-two retrieval. Keep it here so teacher search,
     * textbook search, and future image/text hybrid retrieval all reuse one configured embedding runtime instead of
     * each feature introducing its own ad-hoc client and scoring behavior.</p>
     *
     * @param query normalized user query
     * @param candidateTexts ordered candidate texts to compare
     * @return cosine similarities aligned to {@code candidateTexts}
     */
    public List<Double> semanticSimilarity(String query, List<String> candidateTexts) {
        properties.requireFullyConfigured();
        String normalizedQuery = text(query).strip();
        if (normalizedQuery.isBlank() || candidateTexts == null || candidateTexts.isEmpty()) {
            return List.of();
        }
        List<String> normalizedCandidates = candidateTexts.stream()
                .map(VectorIndexService::text)
                .map(String::strip)
                .toList();
        EmbeddingBatch embeddings = embed(buildSemanticInputs(normalizedQuery, normalizedCandidates));
        List<List<Double>> vectors = embeddings.vectors();
        if (vectors.size() != normalizedCandidates.size() + 1) {
            throw new IllegalStateException("Semantic rerank embedding count mismatch: expected "
                    + (normalizedCandidates.size() + 1) + " but got " + vectors.size());
        }
        List<Double> queryVector = vectors.getFirst();
        List<Double> scores = new ArrayList<>(normalizedCandidates.size());
        for (int index = 0; index < normalizedCandidates.size(); index += 1) {
            scores.add(cosineSimilarity(queryVector, vectors.get(index + 1)));
        }
        return List.copyOf(scores);
    }

    /**
     * Scores one query against candidate texts with a dedicated rerank endpoint when the local worker exposes one.
     *
     * <p>Stage-one document rerank and stage-two block rerank should prefer an actual cross-encoder style score when
     * available, then fall back to embedding cosine similarity if the worker has not been upgraded yet. Keeping the
     * fallback here prevents callers from silently reintroducing bespoke score heuristics.</p>
     */
    public List<Double> rerankTexts(String query, List<String> candidateTexts) {
        properties.requireFullyConfigured();
        String normalizedQuery = text(query).strip();
        if (normalizedQuery.isBlank() || candidateTexts == null || candidateTexts.isEmpty()) {
            return List.of();
        }
        List<String> normalizedCandidates = candidateTexts.stream()
                .map(VectorIndexService::text)
                .map(String::strip)
                .toList();
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("query", normalizedQuery);
            body.put("documents", normalizedCandidates);
            VectorHttpResponse response = transport.postJson(
                    endpoint(properties.embeddingBaseUrl(), "/rerank"),
                    Map.of("Authorization", "Bearer " + properties.embeddingApiKey()),
                    writeJson(body),
                    Duration.ofMillis(properties.normalizedTimeoutMs()));
            JsonNode root = readJson("rerank API", response);
            if (!response.success2xx()) {
                throw new IllegalStateException("Rerank API failed: HTTP " + response.statusCode()
                        + " body=" + abbreviate(response.body(), 300));
            }
            List<Double> scores = new ArrayList<>();
            for (JsonNode item : root.path("data")) {
                scores.add(item.path("score").asDouble(0.0d));
            }
            if (scores.size() != normalizedCandidates.size()) {
                throw new IllegalStateException("Rerank API returned " + scores.size()
                        + " scores for " + normalizedCandidates.size() + " candidates");
            }
            return List.copyOf(scores);
        } catch (RuntimeException exception) {
            log.warn("vector_rerank_fallback query={} message={}",
                    normalizedQuery,
                    text(exception.getMessage()),
                    exception);
            return semanticSimilarity(normalizedQuery, normalizedCandidates);
        }
    }

    /**
     * Executes one real vector search attempt. The outer retry wrapper decides whether to retry transient failures.
     */
    private List<VectorSearchHit> searchTeacherResourceBlocksOnce(String query, int limit, VectorSearchFilter filter) {
        properties.requireFullyConfigured();
        String normalizedQuery = text(query).strip();
        if (normalizedQuery.isBlank()) {
            return List.of();
        }
        EmbeddingBatch embedding = embed(List.of(normalizedQuery));
        ensureCollection();
        ensureVectorIndex();
        loadCollection();
        Map<String, Object> body = searchBody(embedding.vectors().getFirst(), limit, filter);
        VectorHttpResponse response = milvusPost("/v2/vectordb/entities/search", body);
        JsonNode root;
        try {
            root = readJson("Milvus search", response);
        } catch (RuntimeException exception) {
            if (filter == null || filter.empty()) {
                throw exception;
            }
            // Filter parsing errors may be returned as non-JSON by older Milvus gateways.
            response = milvusPost("/v2/vectordb/entities/search", searchBody(embedding.vectors().getFirst(), limit, VectorSearchFilter.EMPTY));
            root = readJson("Milvus search", response);
        }
        if (milvusSearchFailed(response, root) && filter != null && !filter.empty()) {
            // Some Milvus deployments differ in JSON filter support. Fall back to unfiltered vector
            // search and let the caller's visibility/post-filters enforce boundaries.
            response = milvusPost("/v2/vectordb/entities/search", searchBody(embedding.vectors().getFirst(), limit, VectorSearchFilter.EMPTY));
            root = readJson("Milvus search", response);
        }
        if (!response.success2xx() || root.path("code").asInt(-1) != 0) {
            throw new IllegalStateException("Milvus search failed: HTTP " + response.statusCode()
                    + " body=" + abbreviate(response.body(), 300));
        }
        List<VectorSearchHit> hits = new ArrayList<>();
        for (JsonNode item : root.path("data")) {
            JsonNode metadata = readMetadata(item.path("metadata").asText("{}"));
            String documentId = metadata.path("documentId").asText("");
            String blockId = metadata.path("blockId").asText("");
            if (!documentId.isBlank() && !blockId.isBlank()) {
                hits.add(new VectorSearchHit(
                        documentId,
                        blockId,
                        item.path("text").asText(""),
                        item.path("distance").asDouble(0.0)));
            }
        }
        return List.copyOf(hits);
    }

    private static List<String> buildSemanticInputs(String normalizedQuery, List<String> candidateTexts) {
        List<String> inputs = new ArrayList<>(candidateTexts.size() + 1);
        inputs.add(normalizedQuery);
        inputs.addAll(candidateTexts);
        return List.copyOf(inputs);
    }

    private static double cosineSimilarity(List<Double> left, List<Double> right) {
        if (left == null || right == null || left.isEmpty() || right.isEmpty() || left.size() != right.size()) {
            return 0.0d;
        }
        double dot = 0.0d;
        double leftNorm = 0.0d;
        double rightNorm = 0.0d;
        for (int index = 0; index < left.size(); index += 1) {
            double leftValue = left.get(index);
            double rightValue = right.get(index);
            dot += leftValue * rightValue;
            leftNorm += leftValue * leftValue;
            rightNorm += rightValue * rightValue;
        }
        if (leftNorm <= 0.0d || rightNorm <= 0.0d) {
            return 0.0d;
        }
        return dot / (Math.sqrt(leftNorm) * Math.sqrt(rightNorm));
    }

    /**
     * Retries only transient vector-search failures with exponential backoff.
     */
    private <T> T retryVectorSearch(String operation, Supplier<T> supplier) {
        RuntimeException lastFailure = null;
        for (int attempt = 0; attempt < VECTOR_SEARCH_RETRY_ATTEMPTS; attempt++) {
            try {
                return supplier.get();
            } catch (RuntimeException exception) {
                lastFailure = exception;
                if (!isRetryableVectorFailure(exception) || attempt >= VECTOR_SEARCH_RETRY_ATTEMPTS - 1) {
                    throw exception;
                }
                long delayMs = (long) Math.min(4000L, 500L * Math.pow(2, attempt));
                log.warn("vector_search_retry operation={} attempt={} delayMs={} message={}",
                        operation,
                        attempt + 1,
                        delayMs,
                        safe(exception.getMessage()),
                        exception);
                sleepBeforeRetry(delayMs);
            }
        }
        throw lastFailure == null
                ? new IllegalStateException("Vector search failed without a captured exception")
                : lastFailure;
    }

    /**
     * Keeps retries for network, timeout, rate-limit, and temporary upstream failures only.
     */
    private static boolean isRetryableVectorFailure(RuntimeException exception) {
        String message = safe(exception.getMessage()).toLowerCase(Locale.ROOT);
        if (message.contains("must be configured")
                || message.contains("dimension mismatch")
                || message.contains("returned 0 vectors")
                || message.contains("returned non-json")) {
            return false;
        }
        return message.contains("timeout")
                || message.contains("timed out")
                || message.contains("connection")
                || message.contains("refused")
                || message.contains("reset")
                || message.contains("temporarily")
                || message.contains("eof")
                || message.contains("http 408")
                || message.contains("http 429")
                || message.contains("http 500")
                || message.contains("http 502")
                || message.contains("http 503")
                || message.contains("http 504");
    }

    private static void sleepBeforeRetry(long delayMs) {
        try {
            Thread.sleep(Math.max(0, delayMs));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for vector-search retry", exception);
        }
    }

    private Map<String, Object> searchBody(List<Double> vector, int limit, VectorSearchFilter filter) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("collectionName", properties.normalizedCollectionName());
        body.put("data", List.of(vector));
        body.put("limit", Math.max(1, limit));
        body.put("outputFields", List.of("id", "text", "metadata"));
        String expression = milvusMetadataFilter(filter);
        if (!expression.isBlank()) {
            body.put("filter", expression);
        }
        return body;
    }

    public int deleteTeacherResourceVectors(String tenantId, String documentId) {
        if (!properties.enabled()) {
            return 0;
        }
        properties.requireFullyConfigured();
        TeacherResourceDocumentResponse document = resourceStore.find(tenantId, documentId);
        if (document == null) {
            return 0;
        }
        List<TeacherDocumentBlockResponse> blocks = blockStore.listByDocument(tenantId, documentId).stream()
                .filter(block -> text(block.blockId()).isBlank() == false)
                .toList();
        deleteExistingDocumentVectors(document);
        flushCollection();
        return blocks.size();
    }

    private EmbeddingBatch embed(List<String> texts) {
        List<List<Double>> vectors = new ArrayList<>();
        int promptTokens = 0;
        for (int start = 0; start < texts.size(); start += EMBEDDING_BATCH_SIZE) {
            EmbeddingBatch batch = embedBatch(texts.subList(start, Math.min(start + EMBEDDING_BATCH_SIZE, texts.size())));
            vectors.addAll(batch.vectors());
            promptTokens += batch.promptTokens();
        }
        if (vectors.size() != texts.size()) {
            throw new IllegalStateException("Embedding API returned " + vectors.size() + " vectors for " + texts.size() + " inputs");
        }
        return new EmbeddingBatch(List.copyOf(vectors), promptTokens);
    }

    private EmbeddingBatch embedBatch(List<String> texts) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", properties.embeddingModel());
        body.put("input", texts);
        VectorHttpResponse response = transport.postJson(
                endpoint(properties.embeddingBaseUrl(), "/embeddings"),
                Map.of("Authorization", "Bearer " + properties.embeddingApiKey()),
                writeJson(body),
                Duration.ofMillis(properties.normalizedTimeoutMs()));
        JsonNode root = readJson("embedding API", response);
        if (!response.success2xx()) {
            throw new IllegalStateException("Embedding API failed: HTTP " + response.statusCode()
                    + " body=" + abbreviate(response.body(), 300));
        }
        List<List<Double>> vectors = new ArrayList<>();
        for (JsonNode item : root.path("data")) {
            List<Double> vector = new ArrayList<>();
            for (JsonNode value : item.path("embedding")) {
                vector.add(value.asDouble());
            }
            if (vector.size() != properties.normalizedDimension()) {
                throw new IllegalStateException("Embedding dimension mismatch: expected "
                        + properties.normalizedDimension() + " but got " + vector.size());
            }
            vectors.add(List.copyOf(vector));
        }
        if (vectors.size() != texts.size()) {
            throw new IllegalStateException("Embedding API returned " + vectors.size() + " vectors for " + texts.size() + " inputs");
        }
        return new EmbeddingBatch(List.copyOf(vectors), root.path("usage").path("prompt_tokens").asInt(0));
    }

    private void ensureCollection() {
        Map<String, Object> idField = Map.of(
                "fieldName", "id",
                "dataType", "VarChar",
                "isPrimary", true,
                "autoID", false,
                "elementTypeParams", Map.of("max_length", "512"));
        Map<String, Object> vectorField = Map.of(
                "fieldName", "vector",
                "dataType", "FloatVector",
                "elementTypeParams", Map.of("dim", String.valueOf(properties.normalizedDimension())));
        Map<String, Object> textField = Map.of(
                "fieldName", "text",
                "dataType", "VarChar",
                "elementTypeParams", Map.of("max_length", "65535"));
        Map<String, Object> metadataField = Map.of(
                "fieldName", "metadata",
                "dataType", "JSON",
                "isNullable", true);
        Map<String, Object> schema = Map.of(
                "autoID", false,
                "enableDynamicField", true,
                "fields", List.of(idField, vectorField, textField, metadataField));
        Map<String, Object> body = Map.of(
                "collectionName", properties.normalizedCollectionName(),
                "schema", schema);
        VectorHttpResponse response = milvusPost("/v2/vectordb/collections/create", body);
        if (!response.success2xx() || !milvusCodeOk(response.body())) {
            String bodyText = safe(response.body()).toLowerCase();
            if (!bodyText.contains("exist")) {
                throw new IllegalStateException("Milvus collection create failed: HTTP " + response.statusCode());
            }
        }
    }

    private void ensureVectorIndex() {
        Map<String, Object> index = Map.of(
                "fieldName", "vector",
                "indexName", "vector_index",
                "metricType", "COSINE",
                "indexType", "AUTOINDEX");
        VectorHttpResponse response = milvusPost("/v2/vectordb/indexes/create", Map.of(
                "collectionName", properties.normalizedCollectionName(),
                "indexParams", List.of(index)));
        if (!response.success2xx() || !milvusCodeOk(response.body())) {
            String bodyText = safe(response.body()).toLowerCase();
            if (!bodyText.contains("exist")) {
                throw new IllegalStateException("Milvus vector index create failed: HTTP " + response.statusCode()
                        + " body=" + abbreviate(response.body(), 300));
            }
        }
    }

    private void loadCollection() {
        VectorHttpResponse response = milvusPost("/v2/vectordb/collections/load", Map.of(
                "collectionName", properties.normalizedCollectionName()));
        if (!response.success2xx() || !milvusCodeOk(response.body())) {
            String bodyText = safe(response.body()).toLowerCase();
            if (!bodyText.contains("loaded")) {
                throw new IllegalStateException("Milvus collection load failed: HTTP " + response.statusCode()
                        + " body=" + abbreviate(response.body(), 300));
            }
        }
    }

    private void flushCollection() {
        VectorHttpResponse response = milvusPostWithRateLimitRetry("/v2/vectordb/collections/flush", Map.of(
                "collectionName", properties.normalizedCollectionName()));
        if (!response.success2xx() || !milvusCodeOk(response.body())) {
            throw new IllegalStateException("Milvus collection flush failed: HTTP " + response.statusCode()
                    + " body=" + abbreviate(response.body(), 300));
        }
    }

    private RuntimeVectorStatus inspectRuntimeStatus() {
        try {
            JsonNode collection = readSuccessfulMilvusStatus(
                    "Milvus collection describe",
                    milvusPost("/v2/vectordb/collections/describe", Map.of(
                            "collectionName", properties.normalizedCollectionName())));
            String collectionState = collection.path("data").path("state").asText("exists");
            if (collectionState.isBlank()) {
                collectionState = "exists";
            }

            JsonNode index = readSuccessfulMilvusStatus(
                    "Milvus index describe",
                    milvusPost("/v2/vectordb/indexes/describe", Map.of(
                            "collectionName", properties.normalizedCollectionName(),
                            "indexName", "vector_index")));
            String indexState = "unknown";
            JsonNode indexData = index.path("data");
            if (indexData.isArray() && indexData.size() > 0) {
                indexState = indexData.get(0).path("indexState").asText("unknown");
            }

            JsonNode load = readSuccessfulMilvusStatus(
                    "Milvus load state",
                    milvusPost("/v2/vectordb/collections/get_load_state", Map.of(
                            "collectionName", properties.normalizedCollectionName())));
            String loadState = load.path("data").path("loadState").asText("unknown");

            JsonNode count = readSuccessfulMilvusStatus(
                    "Milvus entity count",
                    milvusPost("/v2/vectordb/entities/query", Map.of(
                            "collectionName", properties.normalizedCollectionName(),
                            "filter", "id >= \"\"",
                            "outputFields", List.of("count(*)"),
                            "limit", 0)));
            long rowCount = 0L;
            JsonNode countData = count.path("data");
            if (countData.isArray() && countData.size() > 0) {
                rowCount = countData.get(0).path("count(*)").asLong(0L);
            }
            String status = runtimeStatus(indexState, loadState, rowCount);
            return new RuntimeVectorStatus(collectionState, indexState, loadState, rowCount, status);
        } catch (RuntimeException exception) {
            return new RuntimeVectorStatus(
                    "unknown",
                    "unknown",
                    "unknown",
                    0L,
                    "milvus_status_error");
        }
    }

    private static String runtimeStatus(String indexState, String loadState, long rowCount) {
        boolean indexReady = "Finished".equalsIgnoreCase(indexState);
        boolean loaded = "LoadStateLoaded".equalsIgnoreCase(loadState) || "loaded".equalsIgnoreCase(loadState);
        if (indexReady && loaded && rowCount > 0) {
            return "searchable";
        }
        if (rowCount <= 0) {
            return "index_empty";
        }
        if (!indexReady) {
            return "index_not_ready";
        }
        if (!loaded) {
            return "collection_not_loaded";
        }
        return "ready_to_index";
    }

    private int upsert(
            TeacherResourceDocumentResponse document,
            List<TeacherDocumentBlockResponse> blocks,
            List<List<Double>> vectors) {
        int upserted = 0;
        for (int start = 0; start < blocks.size(); start += MILVUS_UPSERT_BATCH_SIZE) {
            List<Map<String, Object>> data = new ArrayList<>();
            int end = Math.min(start + MILVUS_UPSERT_BATCH_SIZE, blocks.size());
            for (int index = start; index < end; index++) {
                data.add(toMilvusEntity(document, blocks.get(index), vectors.get(index)));
            }
            VectorHttpResponse response = milvusPost("/v2/vectordb/entities/upsert", Map.of(
                    "collectionName", properties.normalizedCollectionName(),
                    "data", data));
            JsonNode root = readJson("Milvus upsert", response);
            if (!response.success2xx() || root.path("code").asInt(-1) != 0) {
                throw new IllegalStateException("Milvus upsert failed: HTTP " + response.statusCode()
                        + " body=" + abbreviate(response.body(), 300));
            }
            upserted += root.path("data").path("upsertCount").asInt(data.size());
        }
        return upserted;
    }

    /**
     * Deletes all vectors for one logical document before upserting the newest active block set.
     *
     * <p>Do not delete only the current active block ids here. Incremental sync may mark old rows inactive because a
     * source file was renamed, split, or deleted; those stale vectors must leave Milvus as well or stage-one document
     * recall will keep seeing ghosts from old syncs.</p>
     */
    private void deleteExistingDocumentVectors(TeacherResourceDocumentResponse document) {
        String filter = "metadata[\"tenantId\"] == " + milvusStringLiteral(text(document.tenantId()))
                + " and metadata[\"documentId\"] == " + milvusStringLiteral(text(document.documentId()));
        VectorHttpResponse response = milvusPost("/v2/vectordb/entities/delete", Map.of(
                "collectionName", properties.normalizedCollectionName(),
                "filter", filter));
        JsonNode root = readJson("Milvus delete", response);
        if (!response.success2xx() || root.path("code").asInt(-1) != 0) {
            throw new IllegalStateException("Milvus delete failed: HTTP " + response.statusCode()
                    + " body=" + abbreviate(response.body(), 300));
        }
    }

    private static Map<String, Object> toMilvusEntity(
            TeacherResourceDocumentResponse document,
            TeacherDocumentBlockResponse block,
            List<Double> vector) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("tenantId", document.tenantId());
        metadata.put("documentId", document.documentId());
        metadata.put("blockId", block.blockId());
        metadata.put("title", text(document.title()));
        metadata.put("sourceType", text(document.sourceType()));
        metadata.put("permissionScope", text(document.permissionScope()));
        metadata.put("chapter", text(block.chapter()));
        metadata.put("section", text(block.section()));
        metadata.put("sourcePath", text(block.sourcePath()));
        metadata.put("blockRole", text(block.blockRole()));
        metadata.put("graphTagsJson", text(block.graphTagNamesJson()));
        metadata.put("checksum", text(block.checksum()));
        return Map.of(
                "id", document.documentId() + ":" + block.blockId(),
                "vector", vector,
                "text", text(block.normalizedText()),
                "metadata", metadata);
    }

    private VectorHttpResponse milvusPost(String path, Object body) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Request-Timeout", String.valueOf(Math.max(1, properties.normalizedTimeoutMs() / 1000)));
        if (properties.milvusToken() != null && !properties.milvusToken().isBlank()) {
            headers.put("Authorization", "Bearer " + properties.milvusToken());
        }
        return transport.postJson(
                endpoint(properties.milvusUri(), path),
                headers,
                writeJson(body),
                Duration.ofMillis(properties.normalizedTimeoutMs()));
    }

    private VectorHttpResponse milvusPostWithRateLimitRetry(String path, Object body) {
        VectorHttpResponse response = null;
        for (int attempt = 1; attempt <= MILVUS_RATE_LIMIT_RETRY_ATTEMPTS; attempt += 1) {
            response = milvusPost(path, body);
            if (!milvusRateLimited(response)) {
                return response;
            }
            if (attempt == MILVUS_RATE_LIMIT_RETRY_ATTEMPTS) {
                return response;
            }
            sleepBeforeMilvusRetry(attempt);
        }
        return response;
    }

    private static boolean milvusRateLimited(VectorHttpResponse response) {
        if (response == null) {
            return false;
        }
        String body = safe(response.body()).toLowerCase();
        return body.contains("rate limit") || body.contains("ratelimiter") || body.contains("\"code\":1807");
    }

    private static boolean milvusSearchFailed(VectorHttpResponse response, JsonNode root) {
        return response == null || !response.success2xx() || root.path("code").asInt(-1) != 0;
    }

    private static void sleepBeforeMilvusRetry(int attempt) {
        try {
            Thread.sleep(MILVUS_RATE_LIMIT_RETRY_DELAY.toMillis() * attempt);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for Milvus rate-limit retry", exception);
        }
    }

    private static URI endpoint(String baseUrl, String path) {
        String base = text(baseUrl);
        String normalizedBase = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        return URI.create(normalizedBase + path);
    }

    private static boolean milvusCodeOk(String body) {
        try {
            return OBJECT_MAPPER.readTree(body).path("code").asInt(-1) == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private static JsonNode readMetadata(String value) {
        try {
            return OBJECT_MAPPER.readTree(value);
        } catch (Exception e) {
            return OBJECT_MAPPER.createObjectNode();
        }
    }

    private static JsonNode readJson(String serviceName, VectorHttpResponse response) {
        try {
            return OBJECT_MAPPER.readTree(response.body());
        } catch (Exception e) {
            throw new IllegalStateException(
                    serviceName + " returned invalid JSON: HTTP " + response.statusCode()
                            + " body=" + abbreviate(response.body(), 300),
                    e);
        }
    }

    private static JsonNode readSuccessfulMilvusStatus(String serviceName, VectorHttpResponse response) {
        JsonNode root = readJson(serviceName, response);
        if (!response.success2xx() || root.path("code").asInt(-1) != 0) {
            throw new IllegalStateException(serviceName + " failed: HTTP " + response.statusCode()
                    + " body=" + abbreviate(response.body(), 300));
        }
        return root;
    }

    private static String abbreviate(String value, int maxLength) {
        String safeValue = value == null ? "" : value.replace('\n', ' ').replace('\r', ' ');
        return safeValue.length() <= maxLength ? safeValue : safeValue.substring(0, maxLength) + "...";
    }

    private static String writeJson(Object value) {
        try {
            return OBJECT_MAPPER.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize vector request JSON", e);
        }
    }

    private static TeacherResourceDocumentResponse withIndexStatus(
            TeacherResourceDocumentResponse document,
            String embeddingStatus,
            String indexStatus) {
        return new TeacherResourceDocumentResponse(
                document.documentId(),
                document.tenantId(),
                document.ownerSubjectId(),
                document.sourceType(),
                document.title(),
                document.originalUrl(),
                document.localPath(),
                document.permissionScope(),
                document.syncStatus(),
                document.parseStatus(),
                embeddingStatus,
                indexStatus,
                document.feishuExportFormat(),
                document.previewFiles(),
                document.parseMode());
    }

    private static String text(String value) {
        return value == null ? "" : value;
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static String milvusStringLiteral(String value) {
        String safeValue = value == null ? "" : value;
        return "\"" + safeValue.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static String milvusMetadataFilter(VectorSearchFilter filter) {
        if (filter == null || filter.empty()) {
            return "";
        }
        List<String> parts = new ArrayList<>();
        if (!filter.documentIds().isEmpty()) {
            parts.add("metadata[\"documentId\"] in ["
                    + filter.documentIds().stream()
                            .map(VectorIndexService::milvusStringLiteral)
                            .collect(java.util.stream.Collectors.joining(","))
                    + "]");
        }
        if (!filter.permissionScopes().isEmpty()) {
            parts.add("metadata[\"permissionScope\"] in ["
                    + filter.permissionScopes().stream()
                            .map(VectorIndexService::milvusStringLiteral)
                            .collect(java.util.stream.Collectors.joining(","))
                    + "]");
        }
        return String.join(" and ", parts);
    }

    private record EmbeddingBatch(List<List<Double>> vectors, int promptTokens) {
    }

    private record RuntimeVectorStatus(
            String collectionState,
            String indexState,
            String loadState,
            long rowCount,
            String status) {
        static RuntimeVectorStatus empty(String status) {
            return new RuntimeVectorStatus("", "", "", 0L, status);
        }
    }
}

