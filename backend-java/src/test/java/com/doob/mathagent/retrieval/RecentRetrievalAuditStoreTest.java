package com.doob.mathagent.retrieval;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class RecentRetrievalAuditStoreTest {

    @Test
    void recordsRecentAuditEventAndFindsItByQueryId() {
        RecentRetrievalAuditStore store = new RecentRetrievalAuditStore(10);
        RetrievalAuditEvent event = auditEvent("query-1");

        store.record(event);

        assertThat(store.findByQueryId("query-1"))
                .isPresent()
                .contains(event);
    }

    @Test
    void evictsOldestAuditEventWhenCapacityIsExceeded() {
        RecentRetrievalAuditStore store = new RecentRetrievalAuditStore(2);

        store.record(auditEvent("query-1"));
        store.record(auditEvent("query-2"));
        store.record(auditEvent("query-3"));

        assertThat(store.findByQueryId("query-1")).isEmpty();
        assertThat(store.findByQueryId("query-2")).isPresent();
        assertThat(store.findByQueryId("query-3")).isPresent();
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
