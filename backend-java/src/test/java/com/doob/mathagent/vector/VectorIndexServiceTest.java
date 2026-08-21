package com.doob.mathagent.vector;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.doob.mathagent.teacher.service.InMemoryTeacherDocumentBlockStore;
import com.doob.mathagent.teacher.service.InMemoryTeacherResourceStore;
import com.doob.mathagent.teacher.block.TeacherDocumentBlockResponse;
import com.doob.mathagent.teacher.document.TeacherResourceDocumentResponse;
import com.doob.mathagent.vector.service.VectorHttpResponse;
import com.doob.mathagent.vector.service.VectorHttpTransport;
import com.doob.mathagent.vector.service.VectorIndexProperties;
import com.doob.mathagent.vector.service.VectorIndexRebuildResponse;
import com.doob.mathagent.vector.service.VectorIndexService;
import com.doob.mathagent.vector.service.VectorSearchFilter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class VectorIndexServiceTest {

    @Test
    void statusReportsSearchableWhenMilvusCollectionHasLoadedFinishedIndexAndRows() {
        VectorIndexService service = new VectorIndexService(
                new VectorIndexProperties(
                        true,
                        "http://milvus.local:19530",
                        "token",
                        "math_agent_resource_blocks",
                        3,
                        "https://embedding.local/v1",
                        "embedding-key",
                        "text-embedding-3-small",
                        10000),
                new CapturingTransport(),
                new InMemoryTeacherResourceStore(),
                new InMemoryTeacherDocumentBlockStore());

        var response = service.status();

        assertThat(response.status()).isEqualTo("searchable");
        assertThat(response.collectionState()).isEqualTo("exists");
        assertThat(response.indexState()).isEqualTo("Finished");
        assertThat(response.loadState()).isEqualTo("LoadStateLoaded");
        assertThat(response.rowCount()).isEqualTo(925);
    }

    @Test
    void rebuildFailsWhenRealEmbeddingOrMilvusIsNotConfigured() {
        InMemoryTeacherResourceStore resources = new InMemoryTeacherResourceStore();
        InMemoryTeacherDocumentBlockStore blocks = new InMemoryTeacherDocumentBlockStore();
        resources.save(document("doc-1", "waiting_rebuild", "waiting_rebuild"));
        blocks.replaceActiveBlocks("school-a", "doc-1", List.of(block("block-1", "space vector angle")));
        CapturingTransport transport = new CapturingTransport();
        VectorIndexService service = new VectorIndexService(
                new VectorIndexProperties(false, "", "", "math_agent_resource_blocks", 3, "", "", "", 10000),
                transport,
                resources,
                blocks);

        VectorIndexRebuildResponse response =
                service.rebuildTeacherResource("school-a", "teacher", "teacher-1", "doc-1");

        assertThat(response.status()).isEqualTo("failed");
        assertThat(response.message()).contains("MATH_AGENT_VECTOR_INDEX_ENABLED");
        assertThat(resources.find("school-a", "doc-1").embeddingStatus()).isEqualTo("failed");
        assertThat(resources.find("school-a", "doc-1").indexStatus()).isEqualTo("failed");
        assertThat(transport.requests).isEmpty();
    }

    @Test
    void localMilvusWithoutAuthenticationIsFullyConfigured() {
        VectorIndexProperties properties = new VectorIndexProperties(
                true,
                "http://milvus.local:19530",
                "",
                "math_agent_resource_blocks",
                3,
                "https://embedding.local/v1",
                "embedding-key",
                "text-embedding-3-small",
                10000);

        assertThat(properties.fullyConfigured()).isTrue();
        properties.requireFullyConfigured();
    }

    @Test
    void rebuildEmbedsBlocksUpsertsMilvusAndMarksDocumentReady() {
        InMemoryTeacherResourceStore resources = new InMemoryTeacherResourceStore();
        InMemoryTeacherDocumentBlockStore blocks = new InMemoryTeacherDocumentBlockStore();
        resources.save(document("doc-1", "waiting_rebuild", "waiting_rebuild"));
        blocks.replaceActiveBlocks("school-a", "doc-1", List.of(block("block-1", "space vector angle")));
        CapturingTransport transport = new CapturingTransport();
        VectorIndexService service = new VectorIndexService(
                new VectorIndexProperties(
                        true,
                        "http://milvus.local:19530",
                        "token",
                        "math_agent_resource_blocks",
                        3,
                        "https://embedding.local/v1",
                        "embedding-key",
                        "text-embedding-3-small",
                        10000),
                transport,
                resources,
                blocks);

        VectorIndexRebuildResponse response =
                service.rebuildTeacherResource("school-a", "teacher", "teacher-1", "doc-1");

        assertThat(response.status()).isEqualTo("indexed");
        assertThat(response.embeddedCount()).isEqualTo(1);
        assertThat(response.upsertedCount()).isEqualTo(1);
        assertThat(response.promptTokens()).isEqualTo(7);
        assertThat(transport.requests).extracting(Request::uri)
                .containsExactly(
                        URI.create("https://embedding.local/v1/embeddings"),
                        URI.create("http://milvus.local:19530/v2/vectordb/collections/create"),
                        URI.create("http://milvus.local:19530/v2/vectordb/indexes/create"),
                        URI.create("http://milvus.local:19530/v2/vectordb/collections/load"),
                        URI.create("http://milvus.local:19530/v2/vectordb/entities/delete"),
                        URI.create("http://milvus.local:19530/v2/vectordb/entities/upsert"),
                        URI.create("http://milvus.local:19530/v2/vectordb/collections/flush"),
                        URI.create("http://milvus.local:19530/v2/vectordb/collections/load"));
        assertThat(transport.requests.get(4).body()).contains("metadata[\\\"tenantId\\\"] == \\\"school-a\\\"")
                .contains("metadata[\\\"documentId\\\"] == \\\"doc-1\\\"");
        assertThat(transport.requests.get(5).body()).contains("space vector angle", "doc-1:block-1")
                .contains("sourceType", "sourcePath", "blockRole", "graphTagsJson");
        TeacherResourceDocumentResponse updated = resources.find("school-a", "doc-1");
        assertThat(updated.embeddingStatus()).isEqualTo("ready");
        assertThat(updated.indexStatus()).isEqualTo("ready");
    }

    @Test
    void rebuildSplitsEmbeddingRequestsAtConfiguredProviderLimit() {
        InMemoryTeacherResourceStore resources = new InMemoryTeacherResourceStore();
        InMemoryTeacherDocumentBlockStore blocks = new InMemoryTeacherDocumentBlockStore();
        resources.save(document("doc-1", "waiting_rebuild", "waiting_rebuild"));
        List<TeacherDocumentBlockResponse> manyBlocks = new ArrayList<>();
        for (int index = 1; index <= 25; index++) {
            manyBlocks.add(block("block-" + index, "batch text " + index));
        }
        blocks.replaceActiveBlocks("school-a", "doc-1", manyBlocks);
        CapturingTransport transport = new CapturingTransport();
        VectorIndexService service = new VectorIndexService(
                new VectorIndexProperties(
                        true,
                        "http://milvus.local:19530",
                        "token",
                        "math_agent_resource_blocks",
                        3,
                        "https://embedding.local/v1",
                        "embedding-key",
                        "text-embedding-v4",
                        10000),
                transport,
                resources,
                blocks);

        VectorIndexRebuildResponse response =
                service.rebuildTeacherResource("school-a", "teacher", "teacher-1", "doc-1");

        assertThat(response.status()).isEqualTo("indexed");
        assertThat(response.embeddedCount()).isEqualTo(25);
        assertThat(transport.requests.stream()
                .filter(request -> request.uri().toString().endsWith("/embeddings"))
                .map(request -> inputCount(request.body())))
                .containsExactly(10, 10, 5);
    }

    @Test
    void teacherSearchSendsTenantDocumentScopeAndCategoryToMilvus() {
        CapturingTransport transport = new CapturingTransport();
        VectorIndexService service = new VectorIndexService(
                new VectorIndexProperties(true, "http://milvus.local:19530", "token", "math_agent_resource_blocks", 3,
                        "https://embedding.local/v1", "embedding-key", "text-embedding-v4", 10000),
                transport,
                new InMemoryTeacherResourceStore(),
                new InMemoryTeacherDocumentBlockStore());

        service.searchTeacherResourceBlocks("triangle sine rule", 3,
                new VectorSearchFilter(List.of("tenant-a"), List.of("document-a"),
                        List.of("TEACHER_PRIVATE"), List.of("feishu")));

        String body = transport.requests.stream()
                .filter(request -> request.uri().toString().endsWith("/entities/search"))
                .findFirst()
                .orElseThrow()
                .body();
        assertThat(body)
                .contains("tenantId", "tenant-a", "documentId", "document-a", "permissionScope", "TEACHER_PRIVATE",
                        "sourceType", "feishu");
    }

    @Test
    void warmTeacherSearchSkipsRedundantMilvusReadinessCalls() {
        CapturingTransport transport = new CapturingTransport();
        VectorIndexService service = teacherSearchService(transport);

        service.searchTeacherResourceBlocks("triangle sine rule", 3);
        service.searchTeacherResourceBlocks("cosine rule", 3);

        assertThat(requestCount(transport, "/collections/create")).isEqualTo(1);
        assertThat(requestCount(transport, "/indexes/create")).isEqualTo(1);
        assertThat(requestCount(transport, "/collections/load")).isEqualTo(1);
        assertThat(requestCount(transport, "/entities/search")).isEqualTo(2);
    }

    @Test
    void failedTeacherSearchInvalidatesReadinessBeforeRetry() {
        CapturingTransport transport = new CapturingTransport(false, 2);
        VectorIndexService service = teacherSearchService(transport);

        service.searchTeacherResourceBlocks("triangle sine rule", 3);
        service.searchTeacherResourceBlocks("cosine rule", 3);

        assertThat(requestCount(transport, "/collections/create")).isEqualTo(2);
        assertThat(requestCount(transport, "/indexes/create")).isEqualTo(2);
        assertThat(requestCount(transport, "/collections/load")).isEqualTo(2);
        assertThat(requestCount(transport, "/entities/search")).isEqualTo(3);
    }

    @Test
    void teacherSearchDoesNotRetryOrWidenWhenMilvusRejectsTheFilter() {
        CapturingTransport transport = new CapturingTransport(true);
        VectorIndexService service = new VectorIndexService(
                new VectorIndexProperties(true, "http://milvus.local:19530", "token", "math_agent_resource_blocks", 3,
                        "https://embedding.local/v1", "embedding-key", "text-embedding-v4", 10000),
                transport,
                new InMemoryTeacherResourceStore(),
                new InMemoryTeacherDocumentBlockStore());

        assertThatThrownBy(() -> service.searchTeacherResourceBlocks("triangle sine rule", 3,
                new VectorSearchFilter(List.of("tenant-a"), List.of("document-a"),
                        List.of("TEACHER_PRIVATE"), List.of("feishu"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Milvus search failed: HTTP 400");

        assertThat(transport.requests.stream()
                .filter(request -> request.uri().toString().endsWith("/entities/search")))
                .hasSize(1);
        assertThat(transport.requests.stream()
                .filter(request -> request.uri().toString().endsWith("/entities/search"))
                .findFirst().orElseThrow().body())
                .contains("tenant-a", "document-a", "TEACHER_PRIVATE", "feishu");
    }

    private static VectorIndexService teacherSearchService(CapturingTransport transport) {
        return new VectorIndexService(
                new VectorIndexProperties(true, "http://milvus.local:19530", "token", "math_agent_resource_blocks", 3,
                        "https://embedding.local/v1", "embedding-key", "text-embedding-v4", 10000),
                transport,
                new InMemoryTeacherResourceStore(),
                new InMemoryTeacherDocumentBlockStore());
    }

    private static long requestCount(CapturingTransport transport, String pathSuffix) {
        return transport.requests.stream()
                .filter(request -> request.uri().getPath().endsWith(pathSuffix))
                .count();
    }

    private static TeacherResourceDocumentResponse document(
            String documentId,
            String embeddingStatus,
            String indexStatus) {
        return new TeacherResourceDocumentResponse(
                documentId,
                "school-a",
                "teacher-1",
                "feishu",
                "Vector handout",
                "https://example.feishu.cn/docx/abc",
                null,
                "TEACHER_PRIVATE",
                "synced",
                "parsed",
                embeddingStatus,
                indexStatus,
                "md",
                List.of());
    }

    private static TeacherDocumentBlockResponse block(String blockId, String normalizedText) {
        return new TeacherDocumentBlockResponse(
                blockId,
                "doc-1",
                "external-" + blockId,
                "markdown",
                1,
                "space vector",
                "angle",
                12,
                "12",
                "lesson/vector-angle.md",
                "lesson",
                normalizedText,
                normalizedText,
                "[]",
                "[]",
                "[]",
                "[]",
                "checksum-1",
                0.99,
                "active");
    }

    private static class CapturingTransport implements VectorHttpTransport {
        private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
        private final List<Request> requests = new ArrayList<>();
        private final boolean failTeacherSearch;
        private final int transientTeacherSearchFailureAt;
        private int teacherSearchRequests;

        private CapturingTransport() {
            this(false, -1);
        }

        private CapturingTransport(boolean failTeacherSearch) {
            this(failTeacherSearch, -1);
        }

        private CapturingTransport(boolean failTeacherSearch, int transientTeacherSearchFailureAt) {
            this.failTeacherSearch = failTeacherSearch;
            this.transientTeacherSearchFailureAt = transientTeacherSearchFailureAt;
        }

        @Override
        public VectorHttpResponse postJson(
                URI uri,
                Map<String, String> headers,
                String body,
                Duration timeout) {
            requests.add(new Request(uri, headers, body, timeout));
            if (uri.toString().endsWith("/embeddings")) {
                int count = inputCount(body);
                String vector = "{\"embedding\":[0.1,0.2,0.3]}";
                return new VectorHttpResponse(200,
                        "{\"data\":[" + String.join(",", java.util.Collections.nCopies(count, vector))
                                + "],\"usage\":{\"prompt_tokens\":7}}");
            }
            if (uri.toString().endsWith("/collections/create")) {
                return new VectorHttpResponse(200, "{\"code\":0}");
            }
            if (uri.toString().endsWith("/collections/describe")) {
                return new VectorHttpResponse(200, "{\"code\":0,\"data\":{\"state\":\"exists\"}}");
            }
            if (uri.toString().endsWith("/indexes/create")) {
                return new VectorHttpResponse(200, "{\"code\":0}");
            }
            if (uri.toString().endsWith("/indexes/describe")) {
                return new VectorHttpResponse(200, """
                        {"code":0,"data":[{"indexName":"vector_index","fieldName":"vector","indexState":"Finished"}]}
                        """);
            }
            if (uri.toString().endsWith("/collections/get_load_state")) {
                return new VectorHttpResponse(200, "{\"code\":0,\"data\":{\"loadState\":\"LoadStateLoaded\",\"loadProgress\":100}}");
            }
            if (uri.toString().endsWith("/entities/query")) {
                return new VectorHttpResponse(200, "{\"code\":0,\"data\":[{\"count(*)\":925}]}");
            }
            if (uri.toString().endsWith("/entities/search")) {
                teacherSearchRequests += 1;
                if (teacherSearchRequests == transientTeacherSearchFailureAt) {
                    return new VectorHttpResponse(503, "{\"code\":1,\"message\":\"temporarily unavailable\"}");
                }
                return failTeacherSearch
                        ? new VectorHttpResponse(400, "{\"code\":1100,\"message\":\"invalid filter\"}")
                        : new VectorHttpResponse(200, "{\"code\":0,\"data\":[]}");
            }
            if (uri.toString().endsWith("/entities/delete")) {
                return new VectorHttpResponse(200, "{\"code\":0,\"data\":{\"deleteCount\":1}}");
            }
            if (uri.toString().endsWith("/entities/upsert")) {
                return new VectorHttpResponse(200, "{\"code\":0,\"data\":{\"upsertCount\":1}}");
            }
            if (uri.toString().endsWith("/collections/flush")) {
                return new VectorHttpResponse(200, "{\"code\":0}");
            }
            if (uri.toString().endsWith("/collections/load")) {
                return new VectorHttpResponse(200, "{\"code\":0}");
            }
            return new VectorHttpResponse(404, "{}");
        }
    }

    private static int inputCount(String body) {
        try {
            JsonNode input = CapturingTransport.OBJECT_MAPPER.readTree(body).path("input");
            return input.isArray() ? input.size() : 1;
        } catch (Exception exception) {
            throw new IllegalArgumentException("Embedding request body must be valid JSON", exception);
        }
    }

    private record Request(URI uri, Map<String, String> headers, String body, Duration timeout) {
    }
}
