package com.doob.mathagent.retrieval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.doob.mathagent.resources.TextbookCatalogReader;
import com.doob.mathagent.resources.TextbookPageImageService;
import com.doob.mathagent.vector.service.VectorHttpResponse;
import com.doob.mathagent.vector.service.VectorHttpTransport;
import com.doob.mathagent.vector.service.VectorIndexProperties;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TextbookPageImageSearchServiceTest {

    @Test
    void mapsWorkerHitsToControlledTextbookImageUrls() {
        CapturingTransport transport = new CapturingTransport();
        TextbookPageImageSearchService service = new TextbookPageImageSearchService(
                new VectorIndexProperties(
                        true,
                        "http://127.0.0.1:19530",
                        "test-milvus-token",
                        "math_agent_resource_blocks",
                        512,
                        "http://127.0.0.1:8091/v1",
                        "worker-key",
                        "local-clip",
                        30000),
                transport,
                new TextbookPageImageService(new TextbookCatalogReader()));

        TextbookPageImageSearchResponse response = service.search(
                new TextbookPageImageSearchRequest("分段函数", null, 5, java.util.List.of("book_a")));

        assertThat(transport.uris()).containsExactly(
                URI.create("http://127.0.0.1:8091/v1/clip/text-embeddings"),
                URI.create("http://127.0.0.1:19530/v2/vectordb/entities/search"));
        assertThat(transport.headers()).containsEntry("Authorization", "Bearer test-milvus-token");
        assertThat(transport.bodies().getFirst()).contains("\"input\":[\"分段函数\"]");
        assertThat(transport.bodies().getLast()).contains("\"collectionName\":\"math_agent_textbook_pages_clip\"");
        assertThat(response.hitCount()).isEqualTo(1);
        assertThat(response.hits().getFirst().imageUri()).isEqualTo("/api/resources/textbooks/book_a/pages/101/image");
    }

    @Test
    void rejectsEmptyClipPageSearchRequest() {
        TextbookPageImageSearchService service = new TextbookPageImageSearchService(
                new VectorIndexProperties(
                        true,
                        "http://127.0.0.1:19530",
                        "test-milvus-token",
                        "math_agent_resource_blocks",
                        512,
                        "http://127.0.0.1:8091/v1",
                        "worker-key",
                        "local-clip",
                        30000),
                new CapturingTransport(),
                new TextbookPageImageService(new TextbookCatalogReader()));

        assertThatThrownBy(() -> service.search(new TextbookPageImageSearchRequest(null, null, 5, java.util.List.of())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("query or image is required");
    }

    @Test
    void repairsWorkerMojibakeInPublicHitMetadata() {
        assertThat(TextbookPageImageSearchService.repairMojibake("äººæBçå¿ä¿®ä¸æ°å­¦"))
                .isEqualTo("人教B版必修一数学");
    }

    private static final class CapturingTransport implements VectorHttpTransport {
        private final List<URI> uris = new ArrayList<>();
        private final List<String> bodies = new ArrayList<>();

        @Override
        public VectorHttpResponse postJson(URI uri, Map<String, String> headers, String body, Duration timeout) {
            uris.add(uri);
            bodies.add(body);
            if (uri.getPath().endsWith("/clip/text-embeddings")) {
                return new VectorHttpResponse(200, "{\"data\":[{\"embedding\":" + vectorJson(512) + "}]}");
            }
            return new VectorHttpResponse(200, """
                    {"code":0,"data":[{"id":"page-101","text":"分段函数图像","distance":0.91,
                    "metadata":"{\\"doc_id\\":\\"book_a\\",\\"page_no\\":101,\\"book_name\\":\\"教材A\\",\\"chapter_path\\":\\"第三章 函数\\",\\"printed_page_no\\":\\"98\\",\\"section_title\\":\\"分段函数\\"}"}]}
                    """);
        }

        private List<URI> uris() {
            return uris;
        }

        private Map<String, String> headers() {
            return Map.of("Authorization", "Bearer test-milvus-token");
        }

        private List<String> bodies() {
            return bodies;
        }

        private static String vectorJson(int dimension) {
            return "[1.0," + "0.0,".repeat(Math.max(0, dimension - 2)) + "0.0]";
        }
    }
}
