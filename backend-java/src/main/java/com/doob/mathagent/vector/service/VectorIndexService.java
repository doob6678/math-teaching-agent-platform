package com.doob.mathagent.vector.service;

import com.doob.mathagent.teacher.block.TeacherDocumentBlockStore;
import com.doob.mathagent.teacher.document.TeacherResourceStore;
import com.doob.mathagent.teacher.block.TeacherDocumentBlockResponse;
import com.doob.mathagent.teacher.document.TeacherResourceDocumentResponse;
import com.doob.mathagent.teacher.sync.TeacherSourceSyncManifestStore;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Rebuilds teacher resource vector indexes with a real embedding API and Milvus REST.
 */
@Service
public class VectorIndexService {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final Logger log = LoggerFactory.getLogger(VectorIndexService.class);
    private static final int MILVUS_UPSERT_BATCH_SIZE = 128;
    private static final int MILVUS_RATE_LIMIT_RETRY_ATTEMPTS = 9;
    private static final Duration MILVUS_RATE_LIMIT_RETRY_BASE_DELAY = Duration.ofSeconds(1);
    private static final Duration MILVUS_RATE_LIMIT_RETRY_MAX_DELAY = Duration.ofSeconds(4);
    private static final int VECTOR_SEARCH_RETRY_ATTEMPTS = 3;

    private final VectorIndexProperties properties;
    private final VectorHttpTransport transport;
    private final TeacherResourceStore resourceStore;
    private final TeacherDocumentBlockStore blockStore;
    /**
     * Serializes the initial teacher-search readiness probe so concurrent warm-up requests do not all send the same
     * idempotent Milvus control-plane calls. The volatile flag is set only after collection, index, and load succeed.
     */
    private final Object teacherSearchReadinessLock = new Object();
    private volatile boolean teacherSearchReady;
    private TeacherResourceImageClipService teacherImageClipService;
    private TeacherSourceSyncManifestStore manifestStore;

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

    /** Optional setter keeps focused text-index tests independent while production rebuilds both vector routes. */
    @Autowired(required = false)
    public void setTeacherImageClipService(TeacherResourceImageClipService teacherImageClipService) {
        this.teacherImageClipService = teacherImageClipService;
    }

    /** Optional in focused tests; production resolves provider file identity from the existing sync manifest. */
    public void setManifestStore(TeacherSourceSyncManifestStore manifestStore) {
        this.manifestStore = manifestStore;
    }

    public VectorIndexStatusResponse status() {        String baseStatus = properties.enabled()
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
        List<TeacherResourceStore.TeacherFileDocument> fileDocuments = List.of();
        try {
            properties.requireFullyConfigured();
            if (resourceStore.supportsFileDocuments()) {
                return rebuildFileDocumentsPaged(tenantId, subjectType, subjectId, document);
            }
            List<TeacherDocumentBlockResponse> legacyBlocks = blockStore.listByDocument(tenantId, documentId).stream()
                    .filter(block -> !text(block.normalizedText()).isBlank())
                    .toList();
            if (legacyBlocks.isEmpty()) {
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
            return rebuildLegacyDocument(tenantId, subjectType, subjectId, document, legacyBlocks);
        } catch (RuntimeException exception) {
            resourceStore.save(withIndexStatus(document, "failed", "failed"));
            return new VectorIndexRebuildResponse(
                    "failed",
                    documentId,
                    properties.normalizedCollectionName(),
                    0,
                    0,
                    0,
                    properties.embeddingModel(),
                    0,
                    "Vector index rebuild failed: " + safe(exception.getMessage()));
        }
    }

    private VectorIndexRebuildResponse rebuildLegacyDocument(
            String tenantId,
            String subjectType,
            String subjectId,
            TeacherResourceDocumentResponse document,
            List<TeacherDocumentBlockResponse> legacyBlocks) {
        long embeddingStarted = System.nanoTime();
        EmbeddingBatch embeddings = embed(legacyBlocks.stream().map(TeacherDocumentBlockResponse::normalizedText).toList());
        long embeddingElapsedMs = elapsedMillis(embeddingStarted);
        ensureCollection();
        ensureVectorIndex();
        loadCollection();
        long milvusDeleteStarted = System.nanoTime();
        deleteExistingDocumentVectors(document);
        long milvusDeleteElapsedMs = elapsedMillis(milvusDeleteStarted);
        long milvusUpsertStarted = System.nanoTime();
        int upserted = upsert(document, legacyBlocks, embeddings.vectors());
        long milvusUpsertElapsedMs = elapsedMillis(milvusUpsertStarted);
        flushCollection();
        loadCollection();
        resourceStore.save(withIndexStatus(document, "ready", "ready"));
        return new VectorIndexRebuildResponse(
                "indexed", document.documentId(), properties.normalizedCollectionName(), legacyBlocks.size(),
                embeddings.vectors().size(), upserted, properties.embeddingModel(), embeddings.promptTokens(),
                "Legacy ROOT vector rebuild completed; deletedExistingCount=" + legacyBlocks.size()
                        + ", embeddingElapsedMs=" + embeddingElapsedMs
                        + ", milvusDeleteElapsedMs=" + milvusDeleteElapsedMs
                        + ", milvusUpsertElapsedMs=" + milvusUpsertElapsedMs,
                legacyBlocks.size(), embeddingElapsedMs, milvusDeleteElapsedMs, milvusUpsertElapsedMs);
    }

    private VectorIndexRebuildResponse rebuildFileDocumentsPaged(
            String tenantId,
            String subjectType,
            String subjectId,
            TeacherResourceDocumentResponse root) {
        long embeddingStarted = System.nanoTime();
        long milvusDeleteElapsedMs = 0L;
        long milvusUpsertElapsedMs = 0L;
        int fileCount = 0;
        int blockCount = 0;
        int embeddedCount = 0;
        int upsertedCount = 0;
        int deletedExistingCount = 0;
        int promptTokens = 0;
        ensureCollection();
        ensureVectorIndex();
        loadCollection();
        // 20260830 parent-child: the derived child collection is best-effort. A child failure never fails the block
        // rebuild because search falls back to the block collection when the child route yields nothing.
        boolean childCollectionReady = false;
        int childChunkCount = 0;
        try {
            ensureChildCollectionReady();
            childCollectionReady = true;
        } catch (RuntimeException exception) {
            log.warn("teacher_child_collection_prepare_failed rebuild_continues_without_children reason={}",
                    abbreviate(safe(exception.getMessage()), 200));
        }
        String afterFileDocumentId = "";
        while (true) {
            List<TeacherResourceStore.TeacherFileDocument> page = resourceStore.listFileDocumentsForIndexing(
                    tenantId, root.documentId(), 64, afterFileDocumentId);
            if (page.isEmpty()) {
                break;
            }
            for (TeacherResourceStore.TeacherFileDocument file : page) {
                fileCount += 1;
                long deleteStarted = System.nanoTime();
                deleteExistingDocumentVectors(file.document());
                milvusDeleteElapsedMs += elapsedMillis(deleteStarted);
                deletedExistingCount += 1;
                Integer afterBlockOrder = null;
                while (true) {
                    List<TeacherDocumentBlockResponse> blocks = blockStore.listBlocksForFile(
                            tenantId, file.documentId(), 128, afterBlockOrder);
                    if (blocks.isEmpty()) {
                        break;
                    }
                    List<TeacherDocumentBlockResponse> indexable = blocks.stream()
                            .filter(block -> !text(block.normalizedText()).isBlank())
                            .toList();
                    if (!indexable.isEmpty()) {
                        EmbeddingBatch embeddings = embed(indexable.stream()
                                .map(TeacherDocumentBlockResponse::normalizedText)
                                .toList());
                        embeddedCount += embeddings.vectors().size();
                        promptTokens += embeddings.promptTokens();
                        long upsertStarted = System.nanoTime();
                        upsertedCount += upsert(file, indexable, embeddings.vectors());
                        milvusUpsertElapsedMs += elapsedMillis(upsertStarted);
                        blockCount += indexable.size();
                    }
                    afterBlockOrder = blocks.getLast().blockOrder();
                }
                if (childCollectionReady) {
                    childChunkCount += indexChildChunksBestEffort(file);
                }
                resourceStore.save(withIndexStatus(file.document(), "ready", "ready"));
                afterFileDocumentId = file.documentId();
            }
            if (page.size() < 64) {
                break;
            }
        }
        flushCollection();
        loadCollection();
        if (childCollectionReady) {
            try {
                flushCollection(properties.normalizedChildCollectionName());
                loadCollection(properties.normalizedChildCollectionName());
            } catch (RuntimeException exception) {
                log.warn("teacher_child_collection_finalize_failed reason={}",
                        abbreviate(safe(exception.getMessage()), 200));
            }
        }
        resourceStore.save(withIndexStatus(root, "ready", "ready"));
        return new VectorIndexRebuildResponse(
                "indexed", root.documentId(), properties.normalizedCollectionName(), blockCount, embeddedCount,
                upsertedCount, properties.embeddingModel(), promptTokens,
                "FILE-scoped paged Milvus rebuild completed; fileCount=" + fileCount
                        + ", childChunkCount=" + childChunkCount,
                deletedExistingCount, elapsedMillis(embeddingStarted), milvusDeleteElapsedMs, milvusUpsertElapsedMs);
    }

    /**
     * Re-derives and upserts one file's child chunks. Old children of the same file are deleted first so a re-parse
     * with different block ids cannot leave orphan vectors. Deterministic from block text; nothing is stored in MySQL.
     */
    private int indexChildChunksBestEffort(TeacherResourceStore.TeacherFileDocument file) {
        try {
            deleteChildVectors(file.document().tenantId(), file.documentId());
            List<TeacherDocumentBlockResponse> blocks = blockStore.listBlocksForFile(
                    file.document().tenantId(), file.documentId(), Integer.MAX_VALUE - 1, null);
            List<TeacherDocumentBlockResponse> indexable = blocks.stream()
                    .filter(block -> !text(block.normalizedText()).isBlank())
                    .toList();
            if (indexable.isEmpty()) {
                return 0;
            }
            List<Map<String, Object>> entities = new ArrayList<>();
            List<String> childTexts = new ArrayList<>();
            for (TeacherDocumentBlockResponse block : indexable) {
                List<String> chunks = TeacherChildChunkSplitter.split(block.normalizedText());
                for (int childIndex = 0; childIndex < chunks.size(); childIndex += 1) {
                    entities.add(childEntityShell(file, block, childIndex));
                    childTexts.add(chunks.get(childIndex));
                }
            }
            if (entities.isEmpty()) {
                return 0;
            }
            EmbeddingBatch embeddings = embed(childTexts);
            for (int index = 0; index < entities.size(); index += 1) {
                entities.get(index).put("vector", embeddings.vectors().get(index));
            }
            return upsertEntities(properties.normalizedChildCollectionName(), entities, "child chunk upsert");
        } catch (RuntimeException exception) {
            log.warn("teacher_child_chunk_index_failed fileDocumentId={} reason={}",
                    file.documentId(), abbreviate(safe(exception.getMessage()), 200));
            return 0;
        }
    }

    /** Builds the child entity without its vector; the vector is injected after batched embedding. */
    private Map<String, Object> childEntityShell(
            TeacherResourceStore.TeacherFileDocument file,
            TeacherDocumentBlockResponse block,
            int childIndex) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("tenantId", file.document().tenantId());
        metadata.put("documentId", file.documentId());
        metadata.put("fileDocumentId", file.documentId());
        metadata.put("documentKind", "FILE");
        metadata.put("rootDocumentId", file.rootDocumentId());
        // The child row carries its PARENT block identity under the same metadata keys as block rows, so every
        // downstream consumer (file grouping, representative choice, evidence window, evaluation oracle) keeps working
        // without any mapping layer: a child hit is a parent-block hit.
        metadata.put("blockId", block.blockId());
        metadata.put("blockOrder", block.blockOrder());
        metadata.put("childIndex", childIndex);
        metadata.put("unit", "child_chunk");
        metadata.put("title", text(file.document().title()));
        metadata.put("sourceType", text(file.document().sourceType()));
        metadata.put("permissionScope", text(file.document().permissionScope()));
        metadata.put("chapter", text(block.chapter()));
        metadata.put("section", text(block.section()));
        metadata.put("sourcePath", text(file.sourcePath()));
        metadata.put("providerItemId", text(file.providerItemId()));
        metadata.put("splitFingerprint", text(file.splitFingerprint()));
        metadata.put("blockRole", text(block.blockRole()));
        return new LinkedHashMap<>(Map.of(
                "id", file.documentId() + ":" + block.blockId() + ":c" + childIndex,
                "text", "",
                "metadata", metadata));
    }

    private int deleteChildVectors(String tenantId, String fileDocumentId) {
        String filter = "metadata[\"tenantId\"] == " + milvusStringLiteral(text(tenantId))
                + " and metadata[\"documentId\"] == " + milvusStringLiteral(text(fileDocumentId));
        VectorHttpResponse response = milvusPostWithRateLimitRetry("/v2/vectordb/entities/delete", Map.of(
                "collectionName", properties.normalizedChildCollectionName(),
                "filter", filter));
        JsonNode root = readJson("Milvus child delete", response);
        if (!response.success2xx() || root.path("code").asInt(-1) != 0) {
            throw new IllegalStateException("Milvus child delete failed: HTTP " + response.statusCode()
                    + " body=" + abbreviate(response.body(), 300));
        }
        return 0;
    }

    private int upsertEntities(String collectionName, List<Map<String, Object>> data, String action) {
        int upserted = 0;
        for (int start = 0; start < data.size(); start += MILVUS_UPSERT_BATCH_SIZE) {
            VectorHttpResponse response = milvusPost("/v2/vectordb/entities/upsert", Map.of(
                    "collectionName", collectionName,
                    "data", data.subList(start, Math.min(start + MILVUS_UPSERT_BATCH_SIZE, data.size()))));
            JsonNode root = readJson(action, response);
            if (!response.success2xx() || root.path("code").asInt(-1) != 0) {
                throw new IllegalStateException(action + " failed: HTTP " + response.statusCode()
                        + " body=" + abbreviate(response.body(), 300));
            }
            upserted += root.path("data").path("upsertCount").asInt(0);
        }
        return upserted;
    }

    /**
     * Legacy bounded implementation retained below for focused compatibility callers; production FILE rebuilds use the
     * durable cursor method above and never request the whole ROOT FILE set at once.
     */
    private VectorIndexRebuildResponse rebuildFileDocuments(
            String tenantId,
            String subjectType,
            String subjectId,
            TeacherResourceDocumentResponse root,
            List<TeacherResourceStore.TeacherFileDocument> fileDocuments) {
        long embeddingStarted = System.nanoTime();
        long milvusDeleteStarted = 0L;
        long milvusUpsertStarted = 0L;
        int blockCount = 0;
        int embeddedCount = 0;
        int upsertedCount = 0;
        int deletedExistingCount = 0;
        int promptTokens = 0;
        ensureCollection();
        ensureVectorIndex();
        loadCollection();
        for (TeacherResourceStore.TeacherFileDocument file : fileDocuments) {
            TeacherResourceDocumentResponse fileDocument = file.document();
            milvusDeleteStarted = System.nanoTime();
            deleteExistingDocumentVectors(fileDocument);
            deletedExistingCount += 1;
            List<TeacherDocumentBlockResponse> page = blockStore.listBlocksForFile(
                    tenantId, file.documentId(), 128, null);
            while (!page.isEmpty()) {
                List<TeacherDocumentBlockResponse> indexable = page.stream()
                        .filter(block -> !text(block.normalizedText()).isBlank())
                        .toList();
                if (!indexable.isEmpty()) {
                    EmbeddingBatch embeddings = embed(indexable.stream()
                            .map(TeacherDocumentBlockResponse::normalizedText)
                            .toList());
                    embeddedCount += embeddings.vectors().size();
                    promptTokens += embeddings.promptTokens();
                    milvusUpsertStarted = System.nanoTime();
                    upsertedCount += upsert(file, indexable, embeddings.vectors());
                    blockCount += indexable.size();
                }
                int lastOrder = page.getLast().blockOrder();
                page = blockStore.listBlocksForFile(tenantId, file.documentId(), 128, lastOrder);
            }
            resourceStore.save(withIndexStatus(fileDocument, "ready", "ready"));
        }
        flushCollection();
        loadCollection();
        resourceStore.save(withIndexStatus(root, "ready", "ready"));
        long embeddingElapsedMs = elapsedMillis(embeddingStarted);
        long milvusDeleteElapsedMs = milvusDeleteStarted == 0L ? 0L : elapsedMillis(milvusDeleteStarted);
        long milvusUpsertElapsedMs = milvusUpsertStarted == 0L ? 0L : elapsedMillis(milvusUpsertStarted);
        return new VectorIndexRebuildResponse(
                "indexed",
                root.documentId(),
                properties.normalizedCollectionName(),
                blockCount,
                embeddedCount,
                upsertedCount,
                properties.embeddingModel(),
                promptTokens,
                "FILE-scoped Milvus rebuild completed; fileCount=" + fileDocuments.size(),
                deletedExistingCount,
                embeddingElapsedMs,
                milvusDeleteElapsedMs,
                milvusUpsertElapsedMs);
    }

    public List<VectorSearchHit> searchTeacherResourceBlocks(String query, int limit) {
        return searchTeacherResourceBlocks(query, limit, VectorSearchFilter.EMPTY);
    }

    public List<VectorSearchHit> searchTeacherResourceBlocks(String query, int limit, VectorSearchFilter filter) {
        return retryVectorSearch("teacher_resource_vector_search", () -> {
            try {
                return searchTeacherResourceBlocksOnce(query, limit, filter);
            } catch (RuntimeException exception) {
                // A failed search cannot prove the collection is still usable. Force the next attempt to revalidate
                // the collection, index, and load state instead of continuing to trust a stale process-local signal.
                invalidateTeacherSearchReadiness();
                throw exception;
            }
        });
    }

    public void indexStudentMemory(String tenantId, String studentId, String memoryId, String content) {
        properties.requireFullyConfigured();
        String normalizedContent = text(content).strip();
        if (text(tenantId).isBlank() || text(studentId).isBlank() || text(memoryId).isBlank() || normalizedContent.isBlank()) {
            return;
        }
        EmbeddingBatch embedding = embed(List.of(normalizedContent));
        String collectionName = properties.normalizedStudentMemoryCollectionName();
        ensureCollection(collectionName);
        ensureVectorIndex(collectionName);
        loadCollection(collectionName);
        Map<String, Object> metadata = Map.of("tenantId", tenantId, "studentId", studentId, "memoryId", memoryId);
        VectorHttpResponse response = milvusPost("/v2/vectordb/entities/upsert", Map.of(
                "collectionName", collectionName,
                "data", List.of(Map.of("id", tenantId + ":" + studentId + ":" + memoryId,
                        "vector", embedding.vectors().getFirst(), "text", normalizedContent, "metadata", metadata))));
        JsonNode root = readJson("Milvus student memory upsert", response);
        if (!response.success2xx() || root.path("code").asInt(-1) != 0) {
            throw new IllegalStateException("Milvus student memory upsert failed: HTTP " + response.statusCode());
        }
        flushCollection(collectionName);
    }

    public List<StudentMemorySearchHit> searchStudentMemories(String tenantId, String studentId, String query, int limit) {
        properties.requireFullyConfigured();
        String normalizedQuery = text(query).strip();
        if (text(tenantId).isBlank() || text(studentId).isBlank() || normalizedQuery.isBlank()) {
            return List.of();
        }
        String collectionName = properties.normalizedStudentMemoryCollectionName();
        EmbeddingBatch embedding = embed(List.of(normalizedQuery));
        ensureCollection(collectionName);
        ensureVectorIndex(collectionName);
        loadCollection(collectionName);
        String filter = "metadata[\"tenantId\"] == " + milvusStringLiteral(tenantId)
                + " and metadata[\"studentId\"] == " + milvusStringLiteral(studentId);
        Map<String, Object> body = Map.of(
                "collectionName", collectionName,
                "data", List.of(embedding.vectors().getFirst()),
                "annsField", "vector",
                "limit", Math.max(1, Math.min(limit, 20)),
                "filter", filter,
                "outputFields", List.of("text", "metadata"),
                "searchParams", Map.of("metricType", "COSINE", "params", Map.of()));
        VectorHttpResponse response = milvusPost("/v2/vectordb/entities/search", body);
        JsonNode root = readJson("Milvus student memory search", response);
        if (!response.success2xx() || root.path("code").asInt(-1) != 0) {
            throw new IllegalStateException("Milvus student memory search failed: HTTP " + response.statusCode());
        }
        List<StudentMemorySearchHit> hits = new ArrayList<>();
        for (JsonNode item : root.path("data")) {
            JsonNode metadata = readMetadata(item.path("metadata").asText("{}"));
            if (tenantId.equals(metadata.path("tenantId").asText()) && studentId.equals(metadata.path("studentId").asText())) {
                String memoryId = metadata.path("memoryId").asText("");
                String content = text(item.path("text").asText()).strip();
                if (!memoryId.isBlank() && !content.isBlank()) {
                    hits.add(new StudentMemorySearchHit(memoryId, content, item.path("distance").asDouble(0D)));
                }
            }
        }
        return List.copyOf(hits);
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
     * Scores candidate texts with the dedicated GPU rerank service.
     *
     * <p>Rerank availability is a real service dependency. A failed rerank must be repaired at the service boundary,
     * never replaced by request-time embedding cosine similarity.</p>
     */
    public List<Double> rerankTexts(String query, List<String> candidateTexts) {
        return rerankTextsWithTrace(query, candidateTexts).scores();
    }

    /** Runs the dedicated GPU reranker and records the actual execution mechanism. */
    public VectorTextRerankResult rerankTextsWithTrace(String query, List<String> candidateTexts) {
        properties.requireFullyConfigured();
        String normalizedQuery = text(query).strip();
        if (normalizedQuery.isBlank() || candidateTexts == null || candidateTexts.isEmpty()) {
            return new VectorTextRerankResult(List.of(), VectorTextRerankResult.CROSS_ENCODER);
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
            return new VectorTextRerankResult(scores, VectorTextRerankResult.CROSS_ENCODER);
        } catch (RuntimeException exception) {
            throw new IllegalStateException("GPU rerank service failed: " + text(exception.getMessage()), exception);
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
        ensureTeacherSearchReady();
        // 20260830 parent-child fusion route: block-level anchors keep their original behavior (a whole block with
        // several matching paragraphs still scores as one vector), while paragraph-level child anchors ADD parents the
        // block route never surfaced. Both ANN calls reuse one query embedding; per-parent max fusion happens below.
        // This is pure vector search — no additional rerank stage on the latency budget.
        List<VectorSearchHit> blockHits = searchCollectionOnce(
                properties.normalizedCollectionName(), embedding.vectors().getFirst(), limit, filter, "blocks");
        if (!properties.normalizedChildChunkSearchEnabled()) {
            return blockHits;
        }
        List<VectorSearchHit> childHits;
        try {
            childHits = searchChildChunksCollapsedToParents(embedding.vectors().getFirst(), limit, filter);
        } catch (RuntimeException exception) {
            log.warn("teacher_child_chunk_search_failed falling_back_to_blocks reason={}",
                    abbreviate(safe(exception.getMessage()), 200));
            invalidateTeacherSearchReadiness();
            childHits = List.of();
        }
        if (childHits.isEmpty()) {
            return blockHits;
        }
        // Fuse both routes WITHOUT trimming back to the block-route pool size: the union can only add anchor
        // information (per-file caps downstream still bound admission), and trimming was observed to displace
        // block-route anchors and cost 3pp doc@3. One anchor per parent block, keeping the higher score.
        Map<String, VectorSearchHit> fusedByParent = new LinkedHashMap<>();
        for (VectorSearchHit hit : blockHits) {
            fusedByParent.put(hit.fileDocumentId() + ":" + hit.blockId(), hit);
        }
        for (VectorSearchHit hit : childHits) {
            fusedByParent.merge(hit.fileDocumentId() + ":" + hit.blockId(), hit,
                    (blockRoute, childRoute) -> childRoute.score() > blockRoute.score() ? childRoute : blockRoute);
        }
        return fusedByParent.values().stream()
                .sorted(java.util.Comparator.comparingDouble(VectorSearchHit::score).reversed())
                .toList();
    }

    /**
     * Child-chunk ANN collapsed to one anchor per parent block.
     *
     * <p>Naively copying the block-level anchor budget onto child rows lets the 2-3 children of one long block
     * consume several anchor slots for the same parent, shrinking the number of DISTINCT parent blocks that reach
     * stage two and regressing file admission (observed 2026-08-30: doc@3 0.867→0.825 before this collapse). The
     * small-to-big contract is therefore: over-fetch children, keep the best child per parent in Milvus score order,
     * and return exactly {@code limit} parent-level anchors.</p>
     */
    private List<VectorSearchHit> searchChildChunksCollapsedToParents(List<Double> queryVector, int limit, VectorSearchFilter filter) {
        int distinctParents = Math.max(1, limit);
        List<VectorSearchHit> overFetched = searchCollectionOnce(
                properties.normalizedChildCollectionName(), queryVector, distinctParents * 4, filter, "child_chunks");
        List<VectorSearchHit> collapsed = new ArrayList<>(distinctParents);
        java.util.Set<String> seenParents = new java.util.HashSet<>();
        for (VectorSearchHit hit : overFetched) {
            String parentKey = hit.fileDocumentId() + ":" + hit.blockId();
            if (!seenParents.add(parentKey)) {
                continue;
            }
            collapsed.add(hit);
            if (collapsed.size() >= distinctParents) {
                break;
            }
        }
        return List.copyOf(collapsed);
    }

    private List<VectorSearchHit> searchCollectionOnce(
            String collectionName,
            List<Double> queryVector,
            int limit,
            VectorSearchFilter filter,
            String route) {
        Map<String, Object> body = searchBody(collectionName, queryVector, limit, filter);
        VectorHttpResponse response = milvusPost("/v2/vectordb/entities/search", body);
        JsonNode root = readJson("Milvus search", response);
        if (!response.success2xx() || root.path("code").asInt(-1) != 0) {
            log.error("teacher_resource_milvus_filter_failed route={} filter={} httpStatus={} code={} body={}",
                    route, filterSummary(filter), response.statusCode(), root.path("code").asInt(-1), abbreviate(response.body(), 300));
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
                        metadata.path("rootDocumentId").asText(""),
                        metadata.path("fileDocumentId").asText(""),
                        documentId,
                        blockId,
                        metadata.path("sourcePath").asText(""),
                        metadata.path("providerItemId").asText(""),
                        metadata.path("blockOrder").asInt(0),
                        metadata.path("splitFingerprint").asText(""),
                        item.path("text").asText(""),
                        item.path("distance").asDouble(0.0)));
            }
        }
        return List.copyOf(hits);
    }

    /**
     * Performs the idempotent Milvus control-plane setup once for this process's teacher text search route.
     *
     * <p>Rebuilds deliberately retain their own explicit create/index/load sequence: they change collection contents
     * and must not depend on a search-path observation made before the rebuild.</p>
     */
    private void ensureTeacherSearchReady() {
        if (teacherSearchReady) {
            return;
        }
        synchronized (teacherSearchReadinessLock) {
            if (teacherSearchReady) {
                return;
            }
            ensureCollection();
            ensureVectorIndex();
            loadCollection();
            // The child route is best-effort: an absent/empty child collection simply yields no hits and the caller
            // falls back to the block collection, so its readiness failure must never block block-route search.
            try {
                ensureChildCollectionReady();
            } catch (RuntimeException exception) {
                log.warn("teacher_child_collection_not_ready fallback_to_blocks reason={}",
                        abbreviate(safe(exception.getMessage()), 200));
            }
            teacherSearchReady = true;
        }
    }

    /** Idempotent create/index/load for the derived child-chunk collection; same schema as the block collection. */
    private void ensureChildCollectionReady() {
        String childCollectionName = properties.normalizedChildCollectionName();
        ensureCollection(childCollectionName);
        ensureVectorIndex(childCollectionName);
        loadCollection(childCollectionName);
    }

    /** Clears the optimistic local readiness observation after a Milvus-backed teacher search failure. */
    private void invalidateTeacherSearchReadiness() {
        teacherSearchReady = false;
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

    private Map<String, Object> searchBody(String collectionName, List<Double> vector, int limit, VectorSearchFilter filter) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("collectionName", collectionName);
        body.put("data", List.of(vector));
        body.put("limit", Math.max(1, limit));
        body.put("outputFields", List.of("id", "text", "metadata"));
        String expression = milvusMetadataFilter(filter);
        if (!expression.isBlank()) {
            body.put("filter", expression);
        }
        return body;
    }

    /** Flushes the shared teacher text collection after a batch of set-based deletes. */
    public void flushTeacherResourceVectors() {
        if (!properties.enabled()) {
            return;
        }
        properties.requireFullyConfigured();
        flushCollection();
    }

    /**
     * Removes one document's vectors. Callers deleting several documents should pass {@code false} and flush once
     * after the batch so Milvus control-plane rate limits are not multiplied by the number of archived files.
     */
    public int deleteTeacherResourceVectors(String tenantId, String documentId) {
        return deleteTeacherResourceVectors(tenantId, documentId, true);
    }

    /** Deletes one document's vectors and optionally flushes the shared collection. */
    public int deleteTeacherResourceVectors(String tenantId, String documentId, boolean flush) {
        if (!properties.enabled()) {
            return 0;
        }
        properties.requireFullyConfigured();
        TeacherResourceDocumentResponse document = resourceStore.find(tenantId, documentId);
        if (document == null) {
            return 0;
        }
        deleteExistingDocumentVectors(document);
        // Child chunks share the document lifecycle; leaving them behind would make archived files ghost-recallable.
        try {
            deleteChildVectors(tenantId, documentId);
        } catch (RuntimeException exception) {
            log.warn("teacher_child_delete_failed documentId={} reason={}",
                    documentId, abbreviate(safe(exception.getMessage()), 200));
        }
        if (flush) {
            flushCollection();
        }
        // CLIP is an isolated optional figure-search module and is intentionally excluded from the text RAG lifecycle.
        // The delete endpoint is set-based; do not load every block merely to manufacture a count for the caller.
        return 1;
    }

    /**
     * Removes local parsed text only after vector deletion has completed, preserving the source-document audit row.
     */
    public void purgeTeacherResourceContent(String tenantId, String documentId) {
        blockStore.purgeDocumentContent(tenantId, documentId);
    }

    private EmbeddingBatch embed(List<String> texts) {
        List<List<Double>> vectors = new ArrayList<>();
        int promptTokens = 0;
        int batchSize = properties.normalizedEmbeddingBatchSize();
        for (int start = 0; start < texts.size(); start += batchSize) {
            EmbeddingBatch batch = embedBatch(texts.subList(start, Math.min(start + batchSize, texts.size())));
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
        ensureCollection(properties.normalizedCollectionName());
    }

    private void ensureCollection(String collectionName) {
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
                "collectionName", collectionName,
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
        ensureVectorIndex(properties.normalizedCollectionName());
    }

    private void ensureVectorIndex(String collectionName) {
        Map<String, Object> index = Map.of(
                "fieldName", "vector",
                "indexName", "vector_index",
                "metricType", "COSINE",
                "indexType", "AUTOINDEX");
        VectorHttpResponse response = milvusPost("/v2/vectordb/indexes/create", Map.of(
                "collectionName", collectionName,
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
        loadCollection(properties.normalizedCollectionName());
    }

    private void loadCollection(String collectionName) {
        VectorHttpResponse response = milvusPost("/v2/vectordb/collections/load", Map.of(
                "collectionName", collectionName));
        if (!response.success2xx() || !milvusCodeOk(response.body())) {
            String bodyText = safe(response.body()).toLowerCase();
            if (!bodyText.contains("loaded")) {
                throw new IllegalStateException("Milvus collection load failed: HTTP " + response.statusCode()
                        + " body=" + abbreviate(response.body(), 300));
            }
        }
    }

    private void flushCollection() {
        flushCollection(properties.normalizedCollectionName());
    }

    private void flushCollection(String collectionName) {
        VectorHttpResponse response = milvusPostWithRateLimitRetry("/v2/vectordb/collections/flush", Map.of(
                "collectionName", collectionName));
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

    private static long elapsedMillis(long started) {
        return java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
    }

    private int upsert(
            TeacherResourceStore.TeacherFileDocument file,
            List<TeacherDocumentBlockResponse> blocks,
            List<List<Double>> vectors) {
        int upserted = 0;
        for (int start = 0; start < blocks.size(); start += MILVUS_UPSERT_BATCH_SIZE) {
            List<Map<String, Object>> data = new ArrayList<>();
            int end = Math.min(start + MILVUS_UPSERT_BATCH_SIZE, blocks.size());
            for (int index = start; index < end; index++) {
                data.add(toMilvusEntity(file, blocks.get(index), vectors.get(index)));
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

    private Map<String, Object> toMilvusEntity(
            TeacherResourceStore.TeacherFileDocument file,
            TeacherDocumentBlockResponse block,
            List<Double> vector) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("tenantId", file.document().tenantId());
        metadata.put("documentId", file.documentId());
        metadata.put("fileDocumentId", file.documentId());
        metadata.put("documentKind", "FILE");
        metadata.put("rootDocumentId", file.rootDocumentId());
        metadata.put("blockId", block.blockId());
        metadata.put("title", text(file.document().title()));
        metadata.put("sourceType", text(file.document().sourceType()));
        metadata.put("permissionScope", text(file.document().permissionScope()));
        metadata.put("chapter", text(block.chapter()));
        metadata.put("section", text(block.section()));
        metadata.put("sourcePath", text(file.sourcePath()));
        metadata.put("providerItemId", text(file.providerItemId()));
        metadata.put("blockOrder", block.blockOrder());
        metadata.put("splitFingerprint", text(file.splitFingerprint()));
        metadata.put("blockRole", text(block.blockRole()));
        metadata.put("graphTagsJson", text(block.graphTagNamesJson()));
        metadata.put("checksum", text(block.checksum()));
        return Map.of(
                "id", file.documentId() + ":" + block.blockId(),
                "vector", vector,
                "text", text(block.normalizedText()),
                "metadata", metadata);
    }

    private Map<String, Object> toMilvusEntity(
            TeacherResourceDocumentResponse document,
            TeacherDocumentBlockResponse block,
            List<Double> vector) {
        String sourcePath = text(block.sourcePath());
        String providerItemId = manifestStore == null
                ? ""
                : text(manifestStore.providerItemId(document.tenantId(), document.documentId(), sourcePath));
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("tenantId", document.tenantId());
        metadata.put("documentId", document.documentId());
        metadata.put("blockId", block.blockId());
        metadata.put("title", text(document.title()));
        metadata.put("sourceType", text(document.sourceType()));
        metadata.put("permissionScope", text(document.permissionScope()));
        metadata.put("chapter", text(block.chapter()));
        metadata.put("section", text(block.section()));
        metadata.put("sourcePath", sourcePath);
        metadata.put("providerItemId", providerItemId);
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
        long delayMs = Math.min(
                MILVUS_RATE_LIMIT_RETRY_MAX_DELAY.toMillis(),
                MILVUS_RATE_LIMIT_RETRY_BASE_DELAY.toMillis() * (1L << Math.min(attempt - 1, 2)));
        try {
            Thread.sleep(delayMs);
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
                document.parseMode(),
                document.providerRevision(),
                document.contentChecksum(),
                document.sourceIdentity());
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
        if (!filter.tenantIds().isEmpty()) {
            parts.add("metadata[\"tenantId\"] in ["
                    + filter.tenantIds().stream().map(VectorIndexService::milvusStringLiteral)
                    .collect(java.util.stream.Collectors.joining(",")) + "]");
        }
        if (!filter.documentIds().isEmpty()) {
            parts.add("metadata[\"documentId\"] in ["
                    + filter.documentIds().stream()
                            .map(VectorIndexService::milvusStringLiteral)
                            .collect(java.util.stream.Collectors.joining(","))
                    + "]");
        }
        if (!filter.sourceTypes().isEmpty()) {
            parts.add("metadata[\"sourceType\"] in ["
                    + filter.sourceTypes().stream().map(VectorIndexService::milvusStringLiteral)
                    .collect(java.util.stream.Collectors.joining(",")) + "]");
        }
        if (filter.physicalFilesOnly()) {
            // Existing FILE vectors carry this durable identity; using it keeps the preserved legacy ROOT vectors out
            // without deleting or rebuilding the production collection just to backfill a newer metadata field.
            parts.add("metadata[\"fileDocumentId\"] != \"\"");
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

    private static String filterSummary(VectorSearchFilter filter) {
        if (filter == null) return "null";
        return "tenantIds=" + filter.tenantIds().size()
                + ",documentIds=" + filter.documentIds().size()
                + ",permissionScopes=" + filter.permissionScopes().size()
                + ",sourceTypes=" + filter.sourceTypes().size()
                + ",physicalFilesOnly=" + filter.physicalFilesOnly();
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
