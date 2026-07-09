package com.doob.mathagent.retrieval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.doob.mathagent.knowledge.service.KnowledgePointRecord;
import com.doob.mathagent.knowledge.service.KnowledgeQuestionBankStore;
import com.doob.mathagent.knowledge.service.KnowledgeRelationRecord;
import com.doob.mathagent.knowledge.service.QuestionBankItemRecord;
import com.doob.mathagent.resources.TextbookCatalogReader;
import com.doob.mathagent.resources.TextbookChunk;
import com.doob.mathagent.resources.TextbookChunkReader;
import com.doob.mathagent.teacher.search.TeacherResourceGraphAlignmentService;
import java.nio.file.Path;
import java.nio.file.Files;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TextbookRetrievalServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void searchesChunksDeclaredByCatalogWithTwoStageRetrieval() throws Exception {
        Path root = tempDir.resolve("processed_books");
        Path bookRoot = root.resolve("book_a");
        Files.createDirectories(bookRoot.resolve("jsonl"));
        Files.writeString(root.resolve("catalog.jsonl"), """
                {"doc_id":"book_a","book_name":"教材A","volume":"必修 第一册","book_root":"%s","manifest":"%s","chunk_count":2,"page_count":2,"ai_ok":false}
                """.formatted(escape(bookRoot), escape(bookRoot.resolve("manifest.json"))));
        Files.writeString(bookRoot.resolve("jsonl/chunks.jsonl"), """
                {"chunk_id":"book_a_p080_text_001","doc_id":"book_a","book_name":"教材A","volume":"必修 第一册","chapter_path":["第三章 函数"],"page_no":80,"printed_page_no":"77","chunk_type":"page_summary","section_title":"函数概念","text":"函数 函数 函数 映射 对应关系 定义域 值域。","formula_text":"","image_rel_paths":[],"source_page_image":"pages/p080.png"}
                {"chunk_id":"book_a_p101_text_001","doc_id":"book_a","book_name":"教材A","volume":"必修 第一册","chapter_path":["第三章 函数"],"page_no":101,"printed_page_no":"98","chunk_type":"page_summary","section_title":"分段函数","text":"分段函数是在定义域的不同部分用不同解析式表示的函数。","formula_text":"","image_rel_paths":[],"source_page_image":"pages/p101.png"}
                """);

        TextbookRetrievalService service = com.doob.mathagent.retrieval.TextbookRetrievalServiceFixture.service(
                new TextbookCatalogReader(),
                new TextbookChunkReader(),
                new LocalTextbookBm25SearchEngine(),
                new NoopRetrievalAuditSink());

        TextbookSearchResponse response = service.search(root, new TextbookSearchRequest("分段函数的定义", 5));

        assertThat(response.retrievalStrategy()).isEqualTo("two_stage_doc_page_v2_rerank");
        assertThat(response.total()).isEqualTo(2);
        assertThat(response.hits())
                .isNotEmpty()
                .first()
                .satisfies(hit -> {
                    assertThat(hit.chunkId()).isEqualTo("book_a_p101_text_001");
                    assertThat(hit.pageImageUri()).isEqualTo("/api/resources/textbooks/book_a/pages/101/image");
                });
    }

    @Test
    void recordsRetrievalAuditEventWithQueryAndRankedHits() throws Exception {
        Path root = tempDir.resolve("processed_books");
        Path bookRoot = root.resolve("book_a");
        Files.createDirectories(bookRoot.resolve("jsonl"));
        Files.writeString(root.resolve("catalog.jsonl"), """
                {"doc_id":"book_a","book_name":"Textbook A","volume":"required 1","book_root":"%s","manifest":"%s","chunk_count":2,"page_count":2,"ai_ok":false}
                """.formatted(escape(bookRoot), escape(bookRoot.resolve("manifest.json"))));
        Files.writeString(bookRoot.resolve("jsonl/chunks.jsonl"), """
                {"chunk_id":"book_a_p010_text_001","doc_id":"book_a","book_name":"Textbook A","volume":"required 1","chapter_path":["Functions"],"page_no":10,"printed_page_no":"8","chunk_type":"page_summary","section_title":"Function definition","text":"function relation mapping domain range","formula_text":"","image_rel_paths":[],"source_page_image":"pages/p010.png"}
                {"chunk_id":"book_a_p020_text_001","doc_id":"book_a","book_name":"Textbook A","volume":"required 1","chapter_path":["Geometry"],"page_no":20,"printed_page_no":"18","chunk_type":"page_summary","section_title":"Triangle","text":"triangle angle side proof","formula_text":"","image_rel_paths":[],"source_page_image":"pages/p020.png"}
                """);
        CapturingRetrievalAuditSink auditSink = new CapturingRetrievalAuditSink();
        TextbookRetrievalService service = com.doob.mathagent.retrieval.TextbookRetrievalServiceFixture.service(
                new TextbookCatalogReader(),
                new TextbookChunkReader(),
                new LocalTextbookBm25SearchEngine(),
                auditSink);

        TextbookSearchResponse response = service.search(root, new TextbookSearchRequest("function mapping", 5));

        assertThat(response.queryId()).isNotBlank();
        assertThat(response.hits())
                .first()
                .extracting(TextbookSearchHit::pageImageUri)
                .isEqualTo("/api/resources/textbooks/book_a/pages/10/image");
        assertThat(auditSink.event()).isNotNull();
        assertThat(auditSink.event().queryId()).isEqualTo(response.queryId());
        assertThat(auditSink.event().tenantId()).isEqualTo("default");
        assertThat(auditSink.event().queryText()).isEqualTo("function mapping");
        assertThat(auditSink.event().retrievalStrategy()).isEqualTo("two_stage_doc_page_v2_rerank");
        assertThat(auditSink.event().requestedLimit()).isEqualTo(5);
        assertThat(auditSink.event().hitCount()).isEqualTo(response.hits().size());
        assertThat(auditSink.event().elapsedMs()).isGreaterThanOrEqualTo(0);
        assertThat(auditSink.event().hits())
                .isNotEmpty()
                .first()
                .satisfies(hit -> {
                    assertThat(hit.rankNo()).isEqualTo(1);
                    assertThat(hit.chunkId()).isEqualTo("book_a_p010_text_001");
                    assertThat(hit.docId()).isEqualTo("book_a");
                    assertThat(hit.pageNo()).isEqualTo(10);
                    assertThat(hit.sourcePageImage()).isEqualTo("pages/p010.png");
                });
    }

    @Test
    void reusesLoadedChunksWhenSourceFilesAreUnchanged() throws Exception {
        Path root = tempDir.resolve("processed_books");
        Path bookRoot = root.resolve("book_a");
        Files.createDirectories(bookRoot.resolve("jsonl"));
        Files.writeString(root.resolve("catalog.jsonl"), """
                {"doc_id":"book_a","book_name":"教材A","volume":"必修 第一册","book_root":"%s","manifest":"%s","chunk_count":1,"page_count":1,"ai_ok":false}
                """.formatted(escape(bookRoot), escape(bookRoot.resolve("manifest.json"))));
        Files.writeString(bookRoot.resolve("jsonl/chunks.jsonl"), """
                {"chunk_id":"book_a_p101_text_001","doc_id":"book_a","book_name":"教材A","volume":"必修 第一册","chapter_path":["第三章 函数"],"page_no":101,"printed_page_no":"98","chunk_type":"page_summary","section_title":"分段函数","text":"分段函数是在定义域的不同部分用不同解析式表示的函数。","formula_text":"","image_rel_paths":[],"source_page_image":"pages/p101.png"}
                """);
        CountingTextbookChunkReader chunkReader = new CountingTextbookChunkReader();
        TextbookRetrievalService service = com.doob.mathagent.retrieval.TextbookRetrievalServiceFixture.service(
                new TextbookCatalogReader(),
                chunkReader,
                new LocalTextbookBm25SearchEngine(),
                new NoopRetrievalAuditSink());

        service.search(root, new TextbookSearchRequest("分段函数", 5));
        service.search(root, new TextbookSearchRequest("函数", 5));

        assertThat(chunkReader.readCount()).isEqualTo(1);
    }

    @Test
    void usesDistributedSearchCacheWhenCorpusQueryAndLimitAreUnchanged() throws Exception {
        Path root = tempDir.resolve("processed_books");
        Path bookRoot = root.resolve("book_a");
        Files.createDirectories(bookRoot.resolve("jsonl"));
        Files.writeString(root.resolve("catalog.jsonl"), """
                {"doc_id":"book_a","book_name":"Textbook A","volume":"required 1","book_root":"%s","manifest":"%s","chunk_count":1,"page_count":1,"ai_ok":false}
                """.formatted(escape(bookRoot), escape(bookRoot.resolve("manifest.json"))));
        Files.writeString(bookRoot.resolve("jsonl/chunks.jsonl"), """
                {"chunk_id":"book_a_p101_text_001","doc_id":"book_a","book_name":"Textbook A","volume":"required 1","chapter_path":["Functions"],"page_no":101,"printed_page_no":"98","chunk_type":"page_summary","section_title":"Piecewise function","text":"piecewise function mapping","formula_text":"","image_rel_paths":[],"source_page_image":"pages/p101.png"}
                """);
        CountingSearchEngine searchEngine = new CountingSearchEngine();
        InMemoryTextbookSearchCache cache = new InMemoryTextbookSearchCache();
        TextbookRetrievalService service = com.doob.mathagent.retrieval.TextbookRetrievalServiceFixture.service(
                new TextbookCatalogReader(),
                new TextbookChunkReader(),
                searchEngine,
                new NoopRetrievalAuditSink(),
                cache,
                new RedisTextbookSearchCacheProperties(true, "math-agent:test:retrieval", Duration.ofMinutes(5)));

        TextbookSearchResponse first = service.search(root, new TextbookSearchRequest("piecewise function", 5));
        TextbookSearchResponse second = service.search(root, new TextbookSearchRequest("piecewise function", 5));

        assertThat(first.retrievalStrategy()).isEqualTo("two_stage_doc_page_v2_rerank");
        assertThat(second.retrievalStrategy()).isEqualTo("redis_cache_two_stage_doc_page_v2_rerank");
        assertThat(second.queryId()).isNotEqualTo(first.queryId());
        assertThat(second.hits()).extracting(TextbookSearchHit::chunkId).containsExactly("book_a_p101_text_001");
        assertThat(searchEngine.searchCount()).isEqualTo(1);
        assertThat(cache.putCount()).isEqualTo(1);
    }

    @Test
    void favorsTheRightBookBeforeRerankingPagesInsideIt() throws Exception {
        Path root = tempDir.resolve("processed_books");
        Path geometryRoot = root.resolve("geometry_book");
        Path statsRoot = root.resolve("stats_book");
        Files.createDirectories(geometryRoot.resolve("jsonl"));
        Files.createDirectories(statsRoot.resolve("jsonl"));
        Files.writeString(root.resolve("catalog.jsonl"), """
                {"doc_id":"geometry_book","book_name":"Geometry Textbook","volume":"selective","book_root":"%s","manifest":"%s","chunk_count":1,"page_count":1,"ai_ok":false}
                {"doc_id":"stats_book","book_name":"Statistics Textbook","volume":"required","book_root":"%s","manifest":"%s","chunk_count":2,"page_count":2,"ai_ok":false}
                """.formatted(
                escape(geometryRoot),
                escape(geometryRoot.resolve("manifest.json")),
                escape(statsRoot),
                escape(statsRoot.resolve("manifest.json"))));
        Files.writeString(geometryRoot.resolve("jsonl/chunks.jsonl"), """
                {"chunk_id":"geometry_p134","doc_id":"geometry_book","book_name":"Geometry Textbook","volume":"selective","chapter_path":["Solid Geometry"],"page_no":134,"printed_page_no":"134","chunk_type":"page_summary","section_title":"Proof check","text":"method check proof angle line plane check relation","formula_text":"","image_rel_paths":[],"source_page_image":"pages/p134.png"}
                """);
        Files.writeString(statsRoot.resolve("jsonl/chunks.jsonl"), """
                {"chunk_id":"stats_p134","doc_id":"stats_book","book_name":"Statistics Textbook","volume":"required","chapter_path":["Statistics And Probability"],"page_no":134,"printed_page_no":"134","chunk_type":"page_summary","section_title":"Is the sample reliable","text":"sample frequency estimate reliability judgement probability background","formula_text":"","image_rel_paths":[],"source_page_image":"pages/p134.png"}
                {"chunk_id":"stats_p135","doc_id":"stats_book","book_name":"Statistics Textbook","volume":"required","chapter_path":["Statistics And Probability"],"page_no":135,"printed_page_no":"135","chunk_type":"page_summary","section_title":"Probability application","text":"probability application data estimate reliability judgement and conclusion","formula_text":"","image_rel_paths":[],"source_page_image":"pages/p135.png"}
                """);

        TextbookRetrievalService service = com.doob.mathagent.retrieval.TextbookRetrievalServiceFixture.service(
                new TextbookCatalogReader(),
                new TextbookChunkReader(),
                new LocalTextbookBm25SearchEngine(),
                new NoopRetrievalAuditSink());

        TextbookSearchResponse response = service.search(root, new TextbookSearchRequest("probability reliability judgement", 3));

        assertThat(response.hits())
                .isNotEmpty()
                .first()
                .extracting(TextbookSearchHit::docId)
                .isEqualTo("stats_book");
        assertThat(response.hits())
                .extracting(TextbookSearchHit::chunkId)
                .contains("stats_p134", "stats_p135");
    }

    @Test
    void focusesInstructionHeavyQueryBeforeTextbookTwoStageRetrieval() throws Exception {
        Path root = tempDir.resolve("processed_books");
        Path bookRoot = root.resolve("book_a");
        Files.createDirectories(bookRoot.resolve("jsonl"));
        Files.writeString(root.resolve("catalog.jsonl"), """
                {"doc_id":"book_a","book_name":"教材A","volume":"必修 第二册","book_root":"%s","manifest":"%s","chunk_count":2,"page_count":2,"ai_ok":false}
                """.formatted(escape(bookRoot), escape(bookRoot.resolve("manifest.json"))));
        Files.writeString(bookRoot.resolve("jsonl/chunks.jsonl"), """
                {"chunk_id":"book_a_p120","doc_id":"book_a","book_name":"教材A","volume":"必修 第二册","chapter_path":["导数及其应用"],"page_no":120,"printed_page_no":"116","chunk_type":"page_summary","section_title":"导数参数题","text":"在含参数的不等式与函数题中，需要结合闭区间端点取值和单调性判断确定最值范围。","formula_text":"","image_rel_paths":[],"source_page_image":"pages/p120.png"}
                {"chunk_id":"book_a_p012","doc_id":"book_a","book_name":"教材A","volume":"必修 第二册","chapter_path":["阅读提示"],"page_no":12,"printed_page_no":"12","chunk_type":"page_summary","section_title":"教材原文引用说明","text":"教材原文依据 引用说明 参考写法 证据格式 资料要求。","formula_text":"","image_rel_paths":[],"source_page_image":"pages/p012.png"}
                """);
        CapturingSearchEngine searchEngine = new CapturingSearchEngine();
        TextbookRetrievalService service = new TextbookRetrievalService(
                new TextbookCatalogReader(),
                new TextbookChunkReader(),
                searchEngine,
                new NoopRetrievalAuditSink(),
                new DisabledTextbookSearchCache(),
                new RedisTextbookSearchCacheProperties(false, "math-agent:test:disabled", Duration.ofMinutes(10)),
                new FixedQueryGraphAlignmentService(new TeacherResourceGraphAlignmentService.QueryGraphContext(
                        List.of("topic-derivative", "topic-monotonicity"),
                        List.of("topic-derivative", "topic-monotonicity", "topic-interval"),
                        List.of("导数", "单调性"),
                        List.of("导数", "单调性", "闭区间"))),
                new com.doob.mathagent.resources.TextbookPageImageService(new TextbookCatalogReader()),
                com.doob.mathagent.vector.service.TestVectorIndexService.successful(
                        new com.doob.mathagent.teacher.service.InMemoryTeacherResourceStore(),
                        new com.doob.mathagent.teacher.service.InMemoryTeacherDocumentBlockStore()));

        TextbookSearchResponse response = service.search(
                root,
                new TextbookSearchRequest("指定库是textbook，目标角色是reference，围绕导数参数题闭区间端点与单调性判断，只要教材原文依据", 3));

        assertThat(searchEngine.lastQuery()).contains("导数参数题闭区间端点与单调性判断");
        assertThat(searchEngine.lastQuery()).contains("导数");
        assertThat(searchEngine.lastQuery()).doesNotContain("指定库是textbook");
        assertThat(response.hits())
                .isNotEmpty()
                .first()
                .extracting(TextbookSearchHit::chunkId)
                .isEqualTo("book_a_p120");
    }

    @Test
    void loadsCorpusOnceWhenConcurrentRequestsMissCacheTogether() throws Exception {
        Path root = tempDir.resolve("processed_books");
        Path bookRoot = root.resolve("book_a");
        Files.createDirectories(bookRoot.resolve("jsonl"));
        Files.writeString(root.resolve("catalog.jsonl"), """
                {"doc_id":"book_a","book_name":"Textbook A","volume":"required 1","book_root":"%s","manifest":"%s","chunk_count":1,"page_count":1,"ai_ok":false}
                """.formatted(escape(bookRoot), escape(bookRoot.resolve("manifest.json"))));
        Files.writeString(bookRoot.resolve("jsonl/chunks.jsonl"), """
                {"chunk_id":"book_a_p101_text_001","doc_id":"book_a","book_name":"Textbook A","volume":"required 1","chapter_path":["Functions"],"page_no":101,"printed_page_no":"98","chunk_type":"page_summary","section_title":"Piecewise function","text":"piecewise function mapping","formula_text":"","image_rel_paths":[],"source_page_image":"pages/p101.png"}
                """);
        SlowCountingTextbookChunkReader chunkReader = new SlowCountingTextbookChunkReader(4);
        TextbookRetrievalService service = com.doob.mathagent.retrieval.TextbookRetrievalServiceFixture.service(
                new TextbookCatalogReader(),
                chunkReader,
                new LocalTextbookBm25SearchEngine(),
                new NoopRetrievalAuditSink());
        ExecutorService executor = Executors.newFixedThreadPool(4);
        List<Future<TextbookSearchResponse>> futures = new ArrayList<>();

        for (int index = 0; index < 4; index++) {
            futures.add(executor.submit(() -> service.search(root, new TextbookSearchRequest("piecewise function", 5))));
        }
        for (Future<TextbookSearchResponse> future : futures) {
            assertThat(future.get().hits()).hasSize(1);
        }
        executor.shutdownNow();

        assertThat(chunkReader.readCount()).isEqualTo(1);
    }

    @Test
    void servesStaleCorpusWhenReloadFailsAfterSuccessfulCacheWarmup() throws Exception {
        Path root = tempDir.resolve("processed_books");
        Path bookRoot = root.resolve("book_a");
        Files.createDirectories(bookRoot.resolve("jsonl"));
        Files.writeString(root.resolve("catalog.jsonl"), """
                {"doc_id":"book_a","book_name":"Textbook A","volume":"required 1","book_root":"%s","manifest":"%s","chunk_count":1,"page_count":1,"ai_ok":false}
                """.formatted(escape(bookRoot), escape(bookRoot.resolve("manifest.json"))));
        Path chunksPath = bookRoot.resolve("jsonl/chunks.jsonl");
        Files.writeString(chunksPath, """
                {"chunk_id":"book_a_p101_text_001","doc_id":"book_a","book_name":"Textbook A","volume":"required 1","chapter_path":["Functions"],"page_no":101,"printed_page_no":"98","chunk_type":"page_summary","section_title":"Piecewise function","text":"piecewise function mapping","formula_text":"","image_rel_paths":[],"source_page_image":"pages/p101.png"}
                """);
        TextbookRetrievalService service = com.doob.mathagent.retrieval.TextbookRetrievalServiceFixture.service(
                new TextbookCatalogReader(),
                new TextbookChunkReader(),
                new LocalTextbookBm25SearchEngine(),
                new NoopRetrievalAuditSink());

        TextbookSearchResponse warmed = service.search(root, new TextbookSearchRequest("piecewise function", 5));
        Files.delete(chunksPath);
        TextbookSearchResponse fallback = service.search(root, new TextbookSearchRequest("piecewise function", 5));

        assertThat(warmed.hits()).hasSize(1);
        assertThat(fallback.hits()).hasSize(1);
        assertThat(fallback.hits().getFirst().chunkId()).isEqualTo("book_a_p101_text_001");
    }

    @Test
    void coolsDownRepeatedReloadFailuresWhenNoStaleCorpusExists() throws Exception {
        Path root = tempDir.resolve("processed_books");
        Path bookRoot = root.resolve("book_a");
        Files.createDirectories(bookRoot.resolve("jsonl"));
        Files.writeString(root.resolve("catalog.jsonl"), """
                {"doc_id":"book_a","book_name":"Textbook A","volume":"required 1","book_root":"%s","manifest":"%s","chunk_count":1,"page_count":1,"ai_ok":false}
                """.formatted(escape(bookRoot), escape(bookRoot.resolve("manifest.json"))));
        Files.writeString(bookRoot.resolve("jsonl/chunks.jsonl"), """
                {"chunk_id":"book_a_p101_text_001","doc_id":"book_a","book_name":"Textbook A","volume":"required 1","chapter_path":["Functions"],"page_no":101,"printed_page_no":"98","chunk_type":"page_summary","section_title":"Piecewise function","text":"piecewise function mapping","formula_text":"","image_rel_paths":[],"source_page_image":"pages/p101.png"}
                """);
        AlwaysFailingTextbookChunkReader chunkReader = new AlwaysFailingTextbookChunkReader();
        TextbookRetrievalService service = com.doob.mathagent.retrieval.TextbookRetrievalServiceFixture.service(
                new TextbookCatalogReader(),
                chunkReader,
                new LocalTextbookBm25SearchEngine(),
                new NoopRetrievalAuditSink());

        assertThatThrownBy(() -> service.search(root, new TextbookSearchRequest("piecewise function", 5)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Failed to load textbook corpus");
        assertThatThrownBy(() -> service.search(root, new TextbookSearchRequest("piecewise function", 5)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cooldown");

        assertThat(chunkReader.readCount()).isEqualTo(1);
    }

    private static String escape(Path path) {
        return path.toString().replace("\\", "\\\\");
    }

    private static class CountingTextbookChunkReader extends TextbookChunkReader {
        private int readCount;

        @Override
        public List<TextbookChunk> read(Path chunksJsonl) {
            readCount++;
            return super.read(chunksJsonl);
        }

        int readCount() {
            return readCount;
        }
    }

    private static class SlowCountingTextbookChunkReader extends TextbookChunkReader {
        private final long delayMillis;
        private final AtomicInteger readCount = new AtomicInteger();

        SlowCountingTextbookChunkReader(int expectedConcurrentReaders) {
            this.delayMillis = expectedConcurrentReaders * 60L;
        }

        @Override
        public List<TextbookChunk> read(Path chunksJsonl) {
            readCount.incrementAndGet();
            try {
                Thread.sleep(delayMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while waiting for concurrent cache load test", e);
            }
            return super.read(chunksJsonl);
        }

        int readCount() {
            return readCount.get();
        }
    }

    private static class AlwaysFailingTextbookChunkReader extends TextbookChunkReader {
        private int readCount;

        @Override
        public List<TextbookChunk> read(Path chunksJsonl) {
            readCount++;
            throw new IllegalStateException("simulated chunk read failure");
        }

        int readCount() {
            return readCount;
        }
    }

    private static class CountingSearchEngine extends LocalTextbookBm25SearchEngine {
        private int searchCount;

        @Override
        public List<TextbookSearchHit> search(String query, List<TextbookChunk> chunks, int limit) {
            searchCount++;
            return super.search(query, chunks, limit);
        }

        @Override
        public List<TextbookSearchHit> search(
                String query,
                List<TextbookChunk> chunks,
                int limit,
                com.doob.mathagent.teacher.search.TeacherResourceGraphAlignmentService.QueryGraphContext queryGraph) {
            searchCount++;
            return super.search(query, chunks, limit, queryGraph);
        }

        int searchCount() {
            return searchCount;
        }
    }

    private static final class CapturingSearchEngine extends LocalTextbookBm25SearchEngine {
        private String lastQuery = "";

        @Override
        public List<TextbookSearchHit> search(
                String query,
                List<TextbookChunk> chunks,
                int limit,
                TeacherResourceGraphAlignmentService.QueryGraphContext queryGraph) {
            lastQuery = query;
            return super.search(query, chunks, limit, queryGraph);
        }

        private String lastQuery() {
            return lastQuery;
        }
    }

    private static class InMemoryTextbookSearchCache implements TextbookSearchCache {
        private final Map<String, CachedTextbookSearch> entries = new HashMap<>();
        private int putCount;

        @Override
        public boolean distributed() {
            return true;
        }

        @Override
        public Optional<CachedTextbookSearch> find(String cacheKey) {
            return Optional.ofNullable(entries.get(cacheKey));
        }

        @Override
        public void put(String cacheKey, CachedTextbookSearch value, Duration ttl) {
            putCount++;
            entries.put(cacheKey, value);
        }

        int putCount() {
            return putCount;
        }
    }

    private static class CapturingRetrievalAuditSink implements RetrievalAuditSink {
        private RetrievalAuditEvent event;

        @Override
        public void record(RetrievalAuditEvent event) {
            this.event = event;
        }

        RetrievalAuditEvent event() {
            return event;
        }
    }

    private static final class DisabledTextbookSearchCache implements TextbookSearchCache {
        @Override
        public Optional<CachedTextbookSearch> find(String cacheKey) {
            return Optional.empty();
        }

        @Override
        public void put(String cacheKey, CachedTextbookSearch value, Duration ttl) {
            // Explicitly disabled for focused-query tests.
        }
    }

    private static final class FixedQueryGraphAlignmentService extends TeacherResourceGraphAlignmentService {
        private final TeacherResourceGraphAlignmentService.QueryGraphContext context;

        private FixedQueryGraphAlignmentService(TeacherResourceGraphAlignmentService.QueryGraphContext context) {
            super(new StubKnowledgeQuestionBankStore());
            this.context = context;
        }

        @Override
        public TeacherResourceGraphAlignmentService.QueryGraphContext alignQuery(
                String tenantId,
                String viewerRole,
                String viewerSubjectId,
                String query) {
            return context;
        }
    }

    private static final class StubKnowledgeQuestionBankStore implements KnowledgeQuestionBankStore {
        @Override
        public com.doob.mathagent.knowledge.service.KnowledgePointRecord saveKnowledgePoint(KnowledgePointRecord record) {
            throw new UnsupportedOperationException();
        }

        @Override
        public com.doob.mathagent.knowledge.service.KnowledgeRelationRecord saveKnowledgeRelation(KnowledgeRelationRecord record) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<KnowledgePointRecord> findKnowledgePoint(
                String tenantId,
                String ownerSubjectId,
                String permissionScope,
                String knowledgePointName,
                String chapterPath) {
            return Optional.empty();
        }

        @Override
        public QuestionBankItemRecord saveQuestion(QuestionBankItemRecord record) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<QuestionBankItemRecord> findQuestionBySource(
                String tenantId,
                String sourceResourceDocumentId,
                String sourceBlockId,
                String sourceChecksum) {
            return Optional.empty();
        }

        @Override
        public int archiveQuestionsBySourceDocumentExcept(
                String tenantId,
                String sourceResourceDocumentId,
                Set<String> activeSourceKeys) {
            return 0;
        }

        @Override
        public List<KnowledgePointRecord> listKnowledgePoints(String tenantId, String viewerRole, String viewerSubjectId) {
            return List.of();
        }

        @Override
        public List<KnowledgeRelationRecord> listKnowledgeRelations(String tenantId, String viewerRole, String viewerSubjectId) {
            return List.of();
        }

        @Override
        public List<QuestionBankItemRecord> searchQuestions(
                String tenantId,
                String viewerRole,
                String viewerSubjectId,
                String query,
                int limit) {
            return List.of();
        }
    }
}
