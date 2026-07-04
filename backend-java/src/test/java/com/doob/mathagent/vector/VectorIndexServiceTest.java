package com.doob.mathagent.vector;

import static org.assertj.core.api.Assertions.assertThat;

import com.doob.mathagent.teacher.service.InMemoryTeacherDocumentBlockStore;
import com.doob.mathagent.teacher.service.InMemoryTeacherResourceStore;
import com.doob.mathagent.teacher.vo.TeacherDocumentBlockResponse;
import com.doob.mathagent.teacher.vo.TeacherResourceDocumentResponse;
import com.doob.mathagent.vector.service.VectorHttpResponse;
import com.doob.mathagent.vector.service.VectorHttpTransport;
import com.doob.mathagent.vector.service.VectorIndexProperties;
import com.doob.mathagent.vector.service.VectorIndexRebuildResponse;
import com.doob.mathagent.vector.service.VectorIndexService;
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
                        URI.create("http://milvus.local:19530/v2/vectordb/entities/delete"),
                        URI.create("http://milvus.local:19530/v2/vectordb/entities/upsert"),
                        URI.create("http://milvus.local:19530/v2/vectordb/collections/flush"),
                        URI.create("http://milvus.local:19530/v2/vectordb/collections/load"));
        assertThat(transport.requests.get(4).body()).contains("space vector angle", "doc-1:block-1");
        TeacherResourceDocumentResponse updated = resources.find("school-a", "doc-1");
        assertThat(updated.embeddingStatus()).isEqualTo("ready");
        assertThat(updated.indexStatus()).isEqualTo("ready");
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
                normalizedText,
                normalizedText,
                "[]",
                "[]",
                "checksum-1",
                0.99,
                "active");
    }

    private static final class CapturingTransport implements VectorHttpTransport {
        private final List<Request> requests = new ArrayList<>();

        @Override
        public VectorHttpResponse postJson(
                URI uri,
                Map<String, String> headers,
                String body,
                Duration timeout) {
            requests.add(new Request(uri, headers, body, timeout));
            if (uri.toString().endsWith("/embeddings")) {
                return new VectorHttpResponse(200, """
                        {"data":[{"embedding":[0.1,0.2,0.3]}],"usage":{"prompt_tokens":7}}
                        """);
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

    private record Request(URI uri, Map<String, String> headers, String body, Duration timeout) {
    }
}
