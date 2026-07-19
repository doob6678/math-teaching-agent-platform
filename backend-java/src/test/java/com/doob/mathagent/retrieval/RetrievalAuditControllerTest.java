package com.doob.mathagent.retrieval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class RetrievalAuditControllerTest {

    @Test
    void exposesAuditDetailByQueryId() {
        RecentRetrievalAuditStore store = new RecentRetrievalAuditStore(10);
        RetrievalAuditEvent event = auditEvent("query-1");
        store.record(event);
        RetrievalAuditController controller = new RetrievalAuditController(store);

        RetrievalAuditEvent response = controller.detail("query-1");

        assertThat(response.queryId()).isEqualTo("query-1");
        assertThat(response.queryText()).isEqualTo("function");
        assertThat(response.hits()).hasSize(1);
        assertThat(response.hits().getFirst().chunkId()).isEqualTo("chunk-1");
    }

    @Test
    void returnsNotFoundWhenQueryIdIsUnknown() {
        RetrievalAuditController controller = new RetrievalAuditController(new RecentRetrievalAuditStore(10));

        assertThatThrownBy(() -> controller.detail("missing-query"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("404");
    }

    private static RetrievalAuditEvent auditEvent(String queryId) {
        return new RetrievalAuditEvent(
                queryId,
                "default",
                "teacher",
                "teacher-1",
                "function",
                "local_bm25_first",
                5,
                1,
                7,
                new RetrievalRequestContext(
                        "default",
                        "teacher",
                        "teacher-1",
                        "127.0.0.1",
                        "device-1",
                        "JUnit",
                        "/api/retrieval/textbooks/search"),
                List.of(new RetrievalAuditHit(
                        1,
                        "chunk-1",
                        "book-1",
                        "Textbook A",
                        12,
                        "10",
                        9.5,
                        "local_bm25",
                        "content_page",
                        "pages/p012.png",
                        "required 1",
                        List.of("Functions"),
                        "Function definition",
                        "function mapping",
                        "")));
    }
}
