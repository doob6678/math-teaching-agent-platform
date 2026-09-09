package com.doob.mathagent.retrieval;

import com.doob.mathagent.vector.service.VectorHttpResponse;
import com.doob.mathagent.vector.service.VectorHttpTransport;
import com.doob.mathagent.vector.service.VectorIndexProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Performs online textbook coarse recall exclusively through Milvus.
 *
 * <p>The worker remains responsible only for real BGE/CLIP query encoding. Corpus vectors are never loaded into
 * application memory on the online path; the two configured collections preserve BGE and CLIP distance semantics.</p>
 */
final class TextbookMilvusSearchClient {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAX_SEARCH_LIMIT = 50;
    private static final Logger log = LoggerFactory.getLogger(TextbookMilvusSearchClient.class);
    private final VectorIndexProperties properties;
    private final VectorHttpTransport transport;

    TextbookMilvusSearchClient(VectorIndexProperties properties, VectorHttpTransport transport) {
        this.properties = properties;
        this.transport = transport;
    }

    List<MilvusHit> searchText(String query, int limit, List<String> docIds) {
        return searchTextCollection(properties.normalizedTextbookTextCollectionName(), query, limit, docIds);
    }

    /** 统一 BGE/Milvus 通道可注册独立公开语料，调用方不再复制向量 HTTP 实现。 */
    List<MilvusHit> searchTextCollection(String collectionName, String query, int limit, List<String> docIds) {
        List<List<Double>> vectors = workerEmbeddings("/embeddings", Map.of("model", properties.embeddingModel(), "input", List.of(query)),
                properties.normalizedTextbookTextDimension());
        return search(collectionName, vectors, limit, docIds);
    }

    /**
     * Checks the authoritative Milvus catalog before an optional corpus is queried.
     *
     * <p>Callers must not create an empty collection merely to make an optional evidence branch appear available.
     * The owning ingestion pipeline is the only component allowed to create and populate its collection.</p>
     */
    boolean collectionExists(String collectionName) {
        if (collectionName == null || collectionName.isBlank()) {
            return false;
        }
        VectorHttpResponse response = milvusPost("/v2/vectordb/collections/has",
                Map.of("collectionName", collectionName.strip()));
        JsonNode root = responseJson("Milvus collection readiness check", response);
        return root.path("data").path("has").asBoolean(false);
    }

    List<MilvusHit> searchImages(String query, String image, int limit, List<String> docIds) {
        List<List<Double>> vectors = new ArrayList<>();
        if (query != null && !query.isBlank()) {
            vectors.addAll(padClipVectors(workerEmbeddings("/clip/text-embeddings", Map.of("input", List.of(query)),
                    properties.normalizedTextbookImageQueryDimension())));
        }
        if (image != null && !image.isBlank()) {
            vectors.addAll(padClipVectors(workerEmbeddings("/clip/image-embeddings", Map.of("images", List.of(image)),
                    properties.normalizedTextbookImageQueryDimension())));
        }
        return search(properties.normalizedTextbookImageCollectionName(), vectors, limit, docIds);
    }

    private List<MilvusHit> search(String collectionName, List<List<Double>> vectors, int limit, List<String> docIds) {
        if (vectors.isEmpty()) {
            return List.of();
        }
        int requested = Math.max(1, Math.min(MAX_SEARCH_LIMIT, limit));
        // 授权过滤下推为 Milvus 标量表达式（metadata["doc_id"] in [...]），与教师资源路
        // （VectorIndexService.milvusMetadataFilter）口径一致。此前本路是"放大候选 + Java 后置过滤"：
        // 未授权内容会先进入响应体（泄露面），且 topK 被未授权文档占满时过滤后可能凑不齐结果。
        // 线上集合 metadata 为 JSON 字段（2026-09-07 describe 核实），JSON path 过滤直接可用，无需重建。
        List<String> allowed = normalizedDocIds(docIds);
        Map<String, MilvusHit> bestById = new LinkedHashMap<>();
        for (List<Double> vector : vectors) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("collectionName", collectionName);
            body.put("data", List.of(vector));
            body.put("limit", requested);
            body.put("outputFields", List.of("id", "text", "metadata"));
            body.put("searchParams", Map.of("metricType", "COSINE", "params", Map.of()));
            if (!allowed.isEmpty()) {
                body.put("filter", documentFilterExpression(allowed));
            }
            var response = milvusPost("/v2/vectordb/entities/search", body);
            JsonNode root = responseJson("Milvus textbook search", response);
            log.debug("textbook_milvus_search collection={} requested={} responseStatus={} rawHits={}",
                    collectionName, requested, response.statusCode(), root.path("data").isArray() ? root.path("data").size() : -1);
            for (JsonNode item : root.path("data")) {
                JsonNode metadata = metadata(item.path("metadata").asText("{}"));
                // 纵深防御断言：下推生效后不应再有越界命中；出现即 WARN 暴露过滤表达式失效，而不是静默兜底。
                if (!matchesDocumentFilter(metadata, allowed)) {
                    log.warn("textbook_milvus_filter_bypass_detected collection={} hit_id={} pushed_down={}",
                            collectionName, item.path("id").asText(""), !allowed.isEmpty());
                    continue;
                }
                String id = item.path("id").asText("");
                if (id.isBlank()) {
                    continue;
                }
                MilvusHit candidate = new MilvusHit(id, item.path("text").asText(""), metadata, item.path("distance").asDouble());
                bestById.merge(id, candidate, (left, right) -> left.score() >= right.score() ? left : right);
            }
        }
        return bestById.values().stream().sorted(Comparator.comparingDouble(MilvusHit::score).reversed()).limit(requested).toList();
    }

    private List<List<Double>> workerEmbeddings(String path, Map<String, Object> body, int expectedDimension) {
        VectorHttpResponse response = transport.postJson(endpoint(properties.embeddingBaseUrl(), path), workerHeaders(), writeJson(body),
                Duration.ofMillis(properties.normalizedTimeoutMs()));
        JsonNode root = workerJson("textbook query embedding", response);
        List<List<Double>> vectors = new ArrayList<>();
        for (JsonNode item : root.path("data")) {
            List<Double> vector = new ArrayList<>();
            for (JsonNode coordinate : item.path("embedding")) {
                vector.add(coordinate.asDouble());
            }
            if (vector.size() != expectedDimension) {
                throw new IllegalStateException("Textbook query vector dimension mismatch: expected " + expectedDimension + " but got " + vector.size());
            }
            vectors.add(List.copyOf(vector));
        }
        if (vectors.isEmpty()) {
            throw new IllegalStateException("Textbook query embedding returned no vectors");
        }
        return List.copyOf(vectors);
    }

    /**
     * Preserves the legacy NPY common-prefix cosine when old 768-d page rows meet the current 512-d CLIP worker.
     * Both corpus and query vectors are L2-normalized in that 512-d prefix; appending zeroes leaves their cosine
     * unchanged while satisfying the independent 768-d Milvus image collection schema.
     */
    private List<List<Double>> padClipVectors(List<List<Double>> vectors) {
        int storedDimension = properties.normalizedTextbookImageDimension();
        int queryDimension = properties.normalizedTextbookImageQueryDimension();
        if (storedDimension == queryDimension) {
            return vectors;
        }
        if (storedDimension < queryDimension) {
            throw new IllegalStateException("Configured stored CLIP dimension must not be smaller than query dimension");
        }
        List<List<Double>> padded = new ArrayList<>(vectors.size());
        for (List<Double> vector : vectors) {
            double squaredNorm = vector.stream().mapToDouble(value -> value * value).sum();
            if (squaredNorm <= 0.0d) {
                throw new IllegalStateException("CLIP query vector has zero norm");
            }
            double norm = Math.sqrt(squaredNorm);
            List<Double> row = new ArrayList<>(storedDimension);
            for (double value : vector) {
                row.add(value / norm);
            }
            while (row.size() < storedDimension) {
                row.add(0.0d);
            }
            padded.add(List.copyOf(row));
        }
        return List.copyOf(padded);
    }

    private VectorHttpResponse milvusPost(String path, Object body) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Request-Timeout", String.valueOf(Math.max(1, properties.normalizedTimeoutMs() / 1000)));
        if (properties.milvusToken() != null && !properties.milvusToken().isBlank()) {
            headers.put("Authorization", "Bearer " + properties.milvusToken());
        }
        return transport.postJson(endpoint(properties.milvusUri(), path), headers, writeJson(body), Duration.ofMillis(properties.normalizedTimeoutMs()));
    }

    private Map<String, String> workerHeaders() {
        return Map.of("Authorization", "Bearer " + (properties.embeddingApiKey() == null ? "" : properties.embeddingApiKey()));
    }

    /** expected 必须是 {@link #normalizedDocIds} 去空白去重后的集合；空集合表示公共语料不设限。 */
    private static boolean matchesDocumentFilter(JsonNode metadata, List<String> expected) {
        return expected.isEmpty() || expected.contains(metadata.path("doc_id").asText(""));
    }

    /**
     * 拼 Milvus JSON path 标量过滤表达式。doc_id 值来自服务端签发的授权集合而非用户输入，
     * 但仍按教师路同款规则转义（反斜杠/双引号加倍），防表达式注入。
     */
    private static String documentFilterExpression(List<String> allowedDocIds) {
        StringBuilder values = new StringBuilder();
        for (int i = 0; i < allowedDocIds.size(); i++) {
            if (i > 0) {
                values.append(", ");
            }
            values.append(milvusLiteral(allowedDocIds.get(i)));
        }
        return "metadata[\"doc_id\"] in [" + values + "]";
    }

    private static String milvusLiteral(String value) {
        String safeValue = value == null ? "" : value;
        return "\"" + safeValue.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static List<String> normalizedDocIds(List<String> docIds) {
        if (docIds == null) return List.of();
        return new ArrayList<>(new LinkedHashSet<>(docIds.stream().filter(value -> value != null && !value.isBlank()).map(String::strip).toList()));
    }

    private static URI endpoint(String base, String path) {
        return URI.create((base.endsWith("/") ? base.substring(0, base.length() - 1) : base) + path);
    }

    private static JsonNode responseJson(String operation, VectorHttpResponse response) {
        try {
            JsonNode root = JSON.readTree(response.body());
            if (!response.success2xx() || root.path("code").asInt(-1) != 0) {
                throw new IllegalStateException(operation + " failed: HTTP " + response.statusCode() + " body=" + response.body());
            }
            return root;
        } catch (IllegalStateException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException(operation + " returned invalid JSON: HTTP " + response.statusCode(), exception);
        }
    }

    /** The worker is OpenAI-compatible and intentionally has no Milvus {@code code} field. */
    private static JsonNode workerJson(String operation, VectorHttpResponse response) {
        try {
            JsonNode root = JSON.readTree(response.body());
            if (!response.success2xx()) {
                throw new IllegalStateException(operation + " failed: HTTP " + response.statusCode() + " body=" + response.body());
            }
            return root;
        } catch (IllegalStateException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException(operation + " returned invalid JSON: HTTP " + response.statusCode(), exception);
        }
    }

    private static JsonNode metadata(String raw) {
        try { return JSON.readTree(raw); } catch (Exception ignored) { return JSON.createObjectNode(); }
    }

    private static String writeJson(Object value) {
        try { return JSON.writeValueAsString(value); } catch (Exception exception) { throw new IllegalStateException("Cannot serialize Milvus request", exception); }
    }

    record MilvusHit(String id, String text, JsonNode metadata, double score) { }
}
