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
import java.util.Map;
import org.junit.jupiter.api.Test;

class TextbookPageImageSearchServiceTest {

    @Test
    void mapsWorkerHitsToControlledTextbookImageUrls() {
        CapturingTransport transport = new CapturingTransport("""
                {
                  "object":"clip.page_search",
                  "model":"local-clip",
                  "provider":"local_clip",
                  "hits":[
                    {
                      "score":0.91,
                      "docId":"book_a",
                      "bookName":"教材A",
                      "chapterPath":"第三章 函数",
                      "pageNo":101,
                      "printedPageNo":"98",
                      "sectionTitle":"分段函数",
                      "sourcePageImage":"pages/p101.png",
                      "text":"分段函数图像"
                    }
                  ]
                }
                """);
        TextbookPageImageSearchService service = new TextbookPageImageSearchService(
                new VectorIndexProperties(
                        true,
                        "http://127.0.0.1:19530",
                        "",
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

        assertThat(transport.uri().toString()).isEqualTo("http://127.0.0.1:8091/v1/clip/page-search");
        assertThat(transport.headers()).containsEntry("Authorization", "Bearer worker-key");
        assertThat(transport.body()).contains("\"texts\":[\"分段函数\"]");
        assertThat(transport.body()).contains("\"docIds\":[\"book_a\"]");
        assertThat(response.hitCount()).isEqualTo(1);
        assertThat(response.hits().getFirst().imageUri()).isEqualTo("/api/resources/textbooks/book_a/pages/101/image");
    }

    @Test
    void rejectsEmptyClipPageSearchRequest() {
        TextbookPageImageSearchService service = new TextbookPageImageSearchService(
                new VectorIndexProperties(
                        true,
                        "http://127.0.0.1:19530",
                        "",
                        "math_agent_resource_blocks",
                        512,
                        "http://127.0.0.1:8091/v1",
                        "worker-key",
                        "local-clip",
                        30000),
                new CapturingTransport("{}"),
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
        private final String responseBody;
        private URI uri;
        private Map<String, String> headers;
        private String body;

        private CapturingTransport(String responseBody) {
            this.responseBody = responseBody;
        }

        @Override
        public VectorHttpResponse postJson(URI uri, Map<String, String> headers, String body, Duration timeout) {
            this.uri = uri;
            this.headers = headers;
            this.body = body;
            return new VectorHttpResponse(200, responseBody);
        }

        private URI uri() {
            return uri;
        }

        private Map<String, String> headers() {
            return headers;
        }

        private String body() {
            return body;
        }
    }
}
