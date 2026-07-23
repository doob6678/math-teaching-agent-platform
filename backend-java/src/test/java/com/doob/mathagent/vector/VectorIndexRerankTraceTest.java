package com.doob.mathagent.vector;

import static org.assertj.core.api.Assertions.assertThat;

import com.doob.mathagent.teacher.service.InMemoryTeacherDocumentBlockStore;
import com.doob.mathagent.teacher.service.InMemoryTeacherResourceStore;
import com.doob.mathagent.vector.service.VectorHttpResponse;
import com.doob.mathagent.vector.service.VectorIndexProperties;
import com.doob.mathagent.vector.service.VectorIndexService;
import org.junit.jupiter.api.Test;

/** Proves that callers can distinguish a real cross-encoder result from embedding fallback. */
class VectorIndexRerankTraceTest {

    @Test
    void reportsCrossEncoderWhenTheDedicatedRerankEndpointSucceeds() {
        VectorIndexService service = service((uri, headers, body, timeout) ->
                new VectorHttpResponse(200, "{\"data\":[{\"score\":0.75}]}"));

        var result = service.rerankTextsWithTrace("query", java.util.List.of("candidate"));

        assertThat(result.strategy()).isEqualTo("cross_encoder");
        assertThat(result.scores()).containsExactly(0.75d);
    }

    @Test
    void reportsEmbeddingFallbackWhenTheDedicatedRerankEndpointFails() {
        VectorIndexService service = service((uri, headers, body, timeout) -> {
            if (uri.getPath().endsWith("/rerank")) {
                return new VectorHttpResponse(500, "{\"error\":\"unavailable\"}");
            }
            return new VectorHttpResponse(200, """
                    {"data":[
                      {"embedding":[1.0,0.0]},
                      {"embedding":[1.0,0.0]}
                    ],"usage":{"prompt_tokens":2}}
                    """);
        });

        var result = service.rerankTextsWithTrace("query", java.util.List.of("candidate"));

        assertThat(result.strategy()).isEqualTo("embedding_fallback");
        assertThat(result.scores()).containsExactly(1.0d);
    }

    private static VectorIndexService service(com.doob.mathagent.vector.service.VectorHttpTransport transport) {
        return new VectorIndexService(
                new VectorIndexProperties(
                        true,
                        "http://milvus.local:19530",
                        "token",
                        "collection",
                        2,
                        "http://worker.local/v1",
                        "key",
                        "embedding-model",
                        1000),
                transport,
                new InMemoryTeacherResourceStore(),
                new InMemoryTeacherDocumentBlockStore());
    }
}
