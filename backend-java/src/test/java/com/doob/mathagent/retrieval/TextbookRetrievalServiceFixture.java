package com.doob.mathagent.retrieval;

import com.doob.mathagent.resources.TextbookCatalogReader;
import com.doob.mathagent.resources.TextbookChunkReader;
import com.doob.mathagent.resources.TextbookPageImageService;
import com.doob.mathagent.teacher.service.TeacherResourceGraphAlignmentService;
import java.time.Duration;
import java.util.Optional;

public final class TextbookRetrievalServiceFixture {

    private TextbookRetrievalServiceFixture() {
    }

    public static TextbookRetrievalService service(
            TextbookCatalogReader catalogReader,
            TextbookChunkReader chunkReader,
            LocalTextbookBm25SearchEngine searchEngine,
            RetrievalAuditSink auditSink) {
        return service(
                catalogReader,
                chunkReader,
                searchEngine,
                auditSink,
                new DisabledTextbookSearchCache(),
                new RedisTextbookSearchCacheProperties(false, "math-agent:test:disabled", Duration.ofMinutes(10)));
    }

    public static TextbookRetrievalService service(
            TextbookCatalogReader catalogReader,
            TextbookChunkReader chunkReader,
            LocalTextbookBm25SearchEngine searchEngine,
            RetrievalAuditSink auditSink,
            TextbookSearchCache searchCache,
            RedisTextbookSearchCacheProperties searchCacheProperties) {
        return new TextbookRetrievalService(
                catalogReader,
                chunkReader,
                searchEngine,
                auditSink,
                searchCache,
                searchCacheProperties,
                TeacherResourceGraphAlignmentService.disabled(),
                new TextbookPageImageService(catalogReader));
    }

    private static final class DisabledTextbookSearchCache implements TextbookSearchCache {
        @Override
        public Optional<CachedTextbookSearch> find(String cacheKey) {
            return Optional.empty();
        }

        @Override
        public void put(String cacheKey, CachedTextbookSearch value, Duration ttl) {
            // Cache is explicitly disabled in this test fixture.
        }
    }
}
