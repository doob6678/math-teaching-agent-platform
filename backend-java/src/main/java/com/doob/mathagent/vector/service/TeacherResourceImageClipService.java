package com.doob.mathagent.vector.service;

import com.doob.mathagent.infrastructure.security.RequestSubject;
import com.doob.mathagent.teacher.document.TeacherResourceDocumentResponse;
import com.doob.mathagent.teacher.document.TeacherResourceStore;
import com.doob.mathagent.teacher.service.TeacherResourceAssetService;
import com.doob.mathagent.teacher.vo.TeacherResourceAssetResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Indexes and searches rendered teacher-resource pages with the real local CLIP worker.
 *
 * <p>This collection is deliberately separate from public textbook CLIP rows.  Asset bytes are opened through the
 * same tenant/owner check as normal resource delivery, and every Milvus row carries the source PDF path and page.</p>
 */
@Service
public class TeacherResourceImageClipService {
    private static final Logger LOGGER = LoggerFactory.getLogger(TeacherResourceImageClipService.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int UPSERT_BATCH_SIZE = 32;

    private final VectorIndexProperties properties;
    private final VectorHttpTransport transport;
    private final TeacherResourceStore resourceStore;
    private final TeacherResourceAssetService assetService;

    public TeacherResourceImageClipService(
            VectorIndexProperties properties,
            VectorHttpTransport transport,
            TeacherResourceStore resourceStore,
            TeacherResourceAssetService assetService) {
        this.properties = properties;
        this.transport = transport;
        this.resourceStore = resourceStore;
        this.assetService = assetService;
    }

    /** Rebuilds all active rendered image rows for one authorized teacher resource. */
    public TeacherResourceImageClipIndexResponse indexDocument(
            String tenantId, String subjectType, String subjectId, String documentId) {
        requireEnabled();
        properties.requireFullyConfigured();
        TeacherResourceDocumentResponse document = requireVisibleDocument(tenantId, subjectType, subjectId, documentId);
        RequestSubject subject = new RequestSubject(tenantId, subjectType, subjectId, "teacher-image-clip").normalize();
        int invalidatedAssets = assetService.deactivateInvalidImageAssets(tenantId, documentId);
        List<TeacherResourceAssetResponse> assets = assetService.listActiveImageAssets(tenantId, documentId);
        LOGGER.info("teacher_image_clip_asset_audit document={} invalidatedAssets={} activeImageAssets={}",
                documentId, invalidatedAssets, assets.size());
        ensureCollection();
        ensureIndex();
        loadCollection();
        deleteDocument(document);
        int embedded = 0;
        int upserted = 0;
        int skipped = 0;
        List<String> failedAssetIds = new ArrayList<>();
        List<ClipInput> inputs = new ArrayList<>();
        for (TeacherResourceAssetResponse asset : assets) {
            try {
                inputs.add(new ClipInput(asset, readDataUri(asset, subject)));
            } catch (RuntimeException exception) {
                if (assetService.deactivateAsset(tenantId, asset.assetId(), clipFailureReason(exception))) {
                    skipped++;
                    failedAssetIds.add(asset.assetId());
                }
            }
        }
        for (int start = 0; start < inputs.size(); start += UPSERT_BATCH_SIZE) {
            int end = Math.min(start + UPSERT_BATCH_SIZE, inputs.size());
            ClipBatchResult result = embedBatchWithIsolation(
                    document, tenantId, inputs.subList(start, end), failedAssetIds);
            embedded += result.embeddedCount();
            upserted += result.upsertedCount();
            skipped += result.skippedCount();
        }
        if (!assets.isEmpty() && embedded == 0) {
            throw new IllegalStateException("CLIP embedding failed for every active teacher image asset");
        }
        flushCollection();
        loadCollection();
        LOGGER.info(
                "teacher_image_clip_index_complete document={} activeAssets={} embedded={} upserted={} skipped={} failedAssetIds={}",
                documentId, assets.size(), embedded, upserted, skipped, failedAssetIds);
        return new TeacherResourceImageClipIndexResponse(documentId, assets.size(), embedded, upserted,
                properties.normalizedTeacherImageCollectionName(), skipped, List.copyOf(failedAssetIds));
    }

    /**
     * Removes every private CLIP row belonging to one archived resource.
     *
     * <p>Text-vector deletion already happens in {@code VectorIndexService}; keeping this operation beside the
     * CLIP collection contract prevents an archived Feishu resource from remaining discoverable through image
     * search after its MySQL assets and text blocks have been purged.</p>
     *
     * @param tenantId tenant owning the resource
     * @param documentId source-document id whose image rows must be removed
     * @return number of active image assets that were the deletion target
     */
    public int deleteDocumentVectors(String tenantId, String documentId) {
        return deleteDocumentVectors(tenantId, documentId, true);
    }

    /** Deletes one document's CLIP rows and optionally flushes the shared image collection. */
    public int deleteDocumentVectors(String tenantId, String documentId, boolean flush) {
        if (!properties.teacherImageClipEnabled()) {
            return 0;
        }
        TeacherResourceDocumentResponse document = resourceStore.find(tenantId, documentId);
        if (document == null) {
            return 0;
        }
        int targetCount = assetService.listActiveImageAssets(tenantId, documentId).size();
        ensureCollection();
        ensureIndex();
        loadCollection();
        deleteDocument(document);
        if (flush) {
            flushCollection();
        }
        return targetCount;
    }

    /** Flushes the shared CLIP collection after a batch of set-based deletes. */
    public void flushTeacherResourceImageVectors() {
        if (!properties.teacherImageClipEnabled()) {
            return;
        }
        flushCollection();
    }

    /** Searches teacher page images with text or an image query and enforces visibility after Milvus recall. */
    public TeacherResourceImageClipSearchResponse search(
            String tenantId,
            String subjectType,
            String subjectId,
            String query,
            String image,
            int limit,
            List<String> documentIds) {
        requireEnabled();
        properties.requireFullyConfigured();
        String normalizedQuery = text(query).strip();
        String normalizedImage = text(image).strip();
        if (normalizedQuery.isBlank() && normalizedImage.isBlank()) {
            throw new IllegalArgumentException("query or image is required");
        }
        List<String> visibleDocumentIds = visibleDocumentIds(tenantId, subjectType, subjectId, documentIds);
        List<List<Double>> vectors = new ArrayList<>();
        if (!normalizedQuery.isBlank()) {
            vectors.addAll(embedText(List.of(normalizedQuery)));
        }
        if (!normalizedImage.isBlank()) {
            vectors.addAll(embedImages(List.of(normalizedImage)));
        }
        ensureCollection();
        ensureIndex();
        loadCollection();
        Map<String, TeacherResourceImageClipHit> best = new LinkedHashMap<>();
        for (List<Double> vector : vectors) {
            JsonNode root = milvus("image CLIP search", "/v2/vectordb/entities/search", Map.of(
                    "collectionName", properties.normalizedTeacherImageCollectionName(),
                    "data", List.of(vector),
                    "limit", Math.max(1, Math.min(50, limit * 3)),
                    "outputFields", List.of("id", "metadata"),
                    "searchParams", Map.of("metricType", "COSINE", "params", Map.of())));
            for (JsonNode row : root.path("data")) {
                JsonNode metadata = parseMetadata(row.path("metadata").asText("{}"));
                String documentId = metadata.path("documentId").asText("");
                if (!visibleDocumentIds.contains(documentId)) {
                    continue;
                }
                String assetId = metadata.path("assetId").asText("");
                if (assetId.isBlank()) {
                    continue;
                }
                TeacherResourceImageClipHit hit = new TeacherResourceImageClipHit(
                        row.path("distance").asDouble(0.0d), documentId, assetId,
                        metadata.path("title").asText(""), metadata.path("sourcePath").asText(""),
                        metadata.path("pageNo").asInt(0), metadata.path("permissionScope").asText(""));
                best.merge(assetId, hit, (left, right) -> left.score() >= right.score() ? left : right);
            }
        }
        List<TeacherResourceImageClipHit> hits = best.values().stream()
                .sorted(Comparator.comparingDouble(TeacherResourceImageClipHit::score).reversed())
                .limit(Math.max(1, Math.min(50, limit)))
                .toList();
        return new TeacherResourceImageClipSearchResponse(normalizedQuery, properties.normalizedTeacherImageCollectionName(),
                hits.size(), List.copyOf(hits));
    }

    private TeacherResourceDocumentResponse requireVisibleDocument(
            String tenantId, String subjectType, String subjectId, String documentId) {
        // CLIP indexing must use the same searchable-resource permission boundary as text indexing;
        // the old list method was removed from the store contract and could not enforce that boundary.
        return resourceStore.listSearchable(tenantId, subjectType, subjectId).stream()
                .filter(document -> document.documentId().equals(documentId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Teacher resource not visible for CLIP indexing"));
    }

    private ClipBatchResult embedBatchWithIsolation(
            TeacherResourceDocumentResponse document,
            String tenantId,
            List<ClipInput> batch,
            List<String> failedAssetIds) {
        try {
            List<List<Double>> vectors = embedImages(batch.stream().map(ClipInput::dataUri).toList());
            if (vectors.size() != batch.size()) {
                throw new IllegalStateException("CLIP image batch size mismatch: expected "
                        + batch.size() + " but got " + vectors.size());
            }
            int upserted = upsert(document, batch.stream().map(ClipInput::asset).toList(), vectors);
            return new ClipBatchResult(vectors.size(), upserted, 0);
        } catch (RuntimeException batchFailure) {
            List<ClipInput> failedInputs = new ArrayList<>();
            int embedded = 0;
            int upserted = 0;
            for (ClipInput input : batch) {
                try {
                    List<List<Double>> vectors = embedImages(List.of(input.dataUri()));
                    if (vectors.size() != 1) {
                        throw new IllegalStateException("CLIP single-image response size mismatch");
                    }
                    embedded += 1;
                    upserted += upsert(document, List.of(input.asset()), vectors);
                } catch (RuntimeException singleFailure) {
                    failedInputs.add(input);
                }
            }
            // A complete single-image failure is a worker or transport outage, not evidence that every source asset is
            // corrupt. Preserve active assets and fail the document index instead of permanently suppressing good images.
            if (embedded == 0) {
                throw batchFailure;
            }
            for (ClipInput failed : failedInputs) {
                if (assetService.deactivateAsset(tenantId, failed.asset().assetId(), clipFailureReason(batchFailure))) {
                    failedAssetIds.add(failed.asset().assetId());
                }
            }
            LOGGER.warn(
                    "teacher_image_clip_batch_isolated document={} batchSize={} embedded={} skipped={} reason={}",
                    document.documentId(), batch.size(), embedded, failedInputs.size(), clipFailureReason(batchFailure));
            return new ClipBatchResult(embedded, upserted, failedInputs.size());
        }
    }

    private static String clipFailureReason(RuntimeException exception) {
        String message = text(exception.getMessage());
        return message.length() <= 500 ? message : message.substring(0, 500);
    }
    private List<String> visibleDocumentIds(
            String tenantId, String subjectType, String subjectId, List<String> requested) {
        // Keep requested-document filtering after the store applies tenant and viewer visibility rules.
        List<String> visible = resourceStore.listSearchable(tenantId, subjectType, subjectId).stream()
                .map(TeacherResourceDocumentResponse::documentId).toList();
        if (requested == null || requested.isEmpty()) {
            return visible;
        }
        return visible.stream().filter(requested::contains).toList();
    }

    private String readDataUri(TeacherResourceAssetResponse asset, RequestSubject subject) {
        try {
            TeacherResourceAssetService.VisibleAsset visible = assetService.openVisibleAsset(asset.assetId(), subject);
            byte[] bytes = visible.resource().getInputStream().readAllBytes();
            return "data:" + visible.mimeType() + ";base64," + Base64.getEncoder().encodeToString(bytes);
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to read rendered teacher page asset", exception);
        }
    }

    private List<List<Double>> embedText(List<String> texts) {
        return embed("/clip/text-embeddings", Map.of("input", texts));
    }

    private List<List<Double>> embedImages(List<String> images) {
        return embed("/clip/image-embeddings", Map.of("images", images));
    }

    private List<List<Double>> embed(String path, Map<String, Object> body) {
        VectorHttpResponse response = transport.postJson(endpoint(properties.embeddingBaseUrl(), path),
                Map.of("Authorization", "Bearer " + properties.embeddingApiKey()), writeJson(body),
                Duration.ofMillis(properties.normalizedTimeoutMs()));
        JsonNode root = readJson("CLIP embedding", response);
        List<List<Double>> vectors = new ArrayList<>();
        for (JsonNode item : root.path("data")) {
            List<Double> source = new ArrayList<>();
            for (JsonNode value : item.path("embedding")) {
                source.add(value.asDouble());
            }
            vectors.add(pad(source));
        }
        if (vectors.isEmpty()) {
            throw new IllegalStateException("CLIP embedding returned no vectors");
        }
        return List.copyOf(vectors);
    }

    private List<Double> pad(List<Double> vector) {
        int queryDimension = properties.normalizedTeacherImageQueryDimension();
        int storedDimension = properties.normalizedTeacherImageDimension();
        if (vector.size() != queryDimension) {
            throw new IllegalStateException("Teacher CLIP dimension mismatch: expected " + queryDimension + " but got " + vector.size());
        }
        double squaredNorm = vector.stream().mapToDouble(value -> value * value).sum();
        if (squaredNorm <= 0.0d) {
            throw new IllegalStateException("Teacher CLIP vector has zero norm");
        }
        double norm = Math.sqrt(squaredNorm);
        List<Double> result = new ArrayList<>(storedDimension);
        vector.forEach(value -> result.add(value / norm));
        while (result.size() < storedDimension) {
            result.add(0.0d);
        }
        return List.copyOf(result);
    }

    private record ClipInput(TeacherResourceAssetResponse asset, String dataUri) {
    }

    private record ClipBatchResult(int embeddedCount, int upsertedCount, int skippedCount) {
    }
    private int upsert(
            TeacherResourceDocumentResponse document,
            List<TeacherResourceAssetResponse> assets,
            List<List<Double>> vectors) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int index = 0; index < assets.size(); index++) {
            TeacherResourceAssetResponse asset = assets.get(index);
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("tenantId", document.tenantId());
            metadata.put("documentId", document.documentId());
            metadata.put("assetId", asset.assetId());
            metadata.put("sourcePath", text(asset.sourcePath()));
            metadata.put("pageNo", asset.pageNo() == null ? 0 : asset.pageNo());
            metadata.put("permissionScope", text(document.permissionScope()));
            metadata.put("title", text(document.title()));
            rows.add(Map.of("id", document.documentId() + ":" + asset.assetId(), "vector", vectors.get(index),
                    "text", text(asset.sourcePath()), "metadata", metadata));
        }
        if (rows.isEmpty()) {
            return 0;
        }
        JsonNode root = milvus("CLIP image upsert", "/v2/vectordb/entities/upsert", Map.of(
                "collectionName", properties.normalizedTeacherImageCollectionName(), "data", rows));
        return root.path("data").path("upsertCount").asInt(rows.size());
    }

    private void ensureCollection() {
        JsonNode ignored = milvusAllowExists("create CLIP collection", "/v2/vectordb/collections/create", Map.of(
                "collectionName", properties.normalizedTeacherImageCollectionName(),
                "schema", Map.of("autoID", false, "enableDynamicField", true, "fields", List.of(
                        Map.of("fieldName", "id", "dataType", "VarChar", "isPrimary", true, "autoID", false,
                                "elementTypeParams", Map.of("max_length", "512")),
                        Map.of("fieldName", "vector", "dataType", "FloatVector", "elementTypeParams",
                                Map.of("dim", String.valueOf(properties.normalizedTeacherImageDimension()))),
                        Map.of("fieldName", "text", "dataType", "VarChar", "elementTypeParams", Map.of("max_length", "4096")),
                        Map.of("fieldName", "metadata", "dataType", "JSON", "isNullable", true)))));
    }

    private void ensureIndex() {
        milvusAllowExists("create CLIP index", "/v2/vectordb/indexes/create", Map.of(
                "collectionName", properties.normalizedTeacherImageCollectionName(),
                "indexParams", List.of(Map.of("fieldName", "vector", "indexName", "vector_index",
                        "metricType", "COSINE", "indexType", "AUTOINDEX"))));
    }

    private void loadCollection() {
        milvusAllowLoaded("load CLIP collection", "/v2/vectordb/collections/load",
                Map.of("collectionName", properties.normalizedTeacherImageCollectionName()));
    }

    private void flushCollection() {
        milvus("flush CLIP collection", "/v2/vectordb/collections/flush",
                Map.of("collectionName", properties.normalizedTeacherImageCollectionName()));
    }

    private void deleteDocument(TeacherResourceDocumentResponse document) {
        milvus("delete teacher CLIP rows", "/v2/vectordb/entities/delete", Map.of(
                "collectionName", properties.normalizedTeacherImageCollectionName(),
                "filter", "metadata[\"tenantId\"] == " + literal(document.tenantId())
                        + " and metadata[\"documentId\"] == " + literal(document.documentId())));
    }

    private JsonNode milvusAllowExists(String operation, String path, Object body) {
        try {
            return milvus(operation, path, body);
        } catch (IllegalStateException exception) {
            if (text(exception.getMessage()).toLowerCase().contains("exist")) {
                return JSON.createObjectNode();
            }
            throw exception;
        }
    }

    private void milvusAllowLoaded(String operation, String path, Object body) {
        try {
            milvus(operation, path, body);
        } catch (IllegalStateException exception) {
            if (!text(exception.getMessage()).toLowerCase().contains("loaded")) {
                throw exception;
            }
        }
    }

    private JsonNode milvus(String operation, String path, Object body) {
        Map<String, String> headers = new LinkedHashMap<>();
        if (!text(properties.milvusToken()).isBlank()) {
            headers.put("Authorization", "Bearer " + properties.milvusToken());
        }
        VectorHttpResponse response = transport.postJson(endpoint(properties.milvusUri(), path), headers, writeJson(body),
                Duration.ofMillis(properties.normalizedTimeoutMs()));
        JsonNode root = readJson(operation, response);
        if (!response.success2xx() || root.path("code").asInt(-1) != 0) {
            throw new IllegalStateException(operation + " failed: HTTP " + response.statusCode() + " body=" + response.body());
        }
        return root;
    }

    private static JsonNode readJson(String operation, VectorHttpResponse response) {
        try {
            return JSON.readTree(response.body());
        } catch (Exception exception) {
            throw new IllegalStateException(operation + " returned invalid JSON", exception);
        }
    }

    private static JsonNode parseMetadata(String value) {
        try {
            return JSON.readTree(value);
        } catch (Exception exception) {
            return JSON.createObjectNode();
        }
    }

    private static URI endpoint(String base, String path) {
        String normalized = text(base);
        return URI.create((normalized.endsWith("/") ? normalized.substring(0, normalized.length() - 1) : normalized) + path);
    }

    private static String literal(String value) {
        return "\"" + text(value).replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static String writeJson(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to serialize CLIP vector request", exception);
        }
    }

    private void requireEnabled() {
        if (!properties.teacherImageClipEnabled()) {
            throw new IllegalStateException("Teacher image CLIP is disabled");
        }
    }

    private static String text(String value) {
        return value == null ? "" : value;
    }
}
