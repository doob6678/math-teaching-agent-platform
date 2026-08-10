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
import java.util.LinkedHashMap;
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
    void expandsCoarsePoolAndUsesRankOnlyFusionBeforeFinalRerank() {
        Map<String, List<TextbookSearchHit>> semantic = new LinkedHashMap<>();
        semantic.put("book_semantic", List.of(hit("semantic")));
        semantic.put("book_shared", List.of(hit("shared")));
        Map<String, List<TextbookSearchHit>> lexical = new LinkedHashMap<>();
        lexical.put("book_lexical", List.of(hit("lexical")));
        lexical.put("book_shared", List.of(hit("shared")));
        Map<String, List<TextbookSearchHit>> title = new LinkedHashMap<>();
        title.put("book_title", List.of(hit("title")));
        title.put("book_shared", List.of(hit("shared")));

        assertThat(TextbookRetrievalService.rankCoarseDocumentsByRrf(
                List.of(semantic, lexical, title), 3, 60))
                .containsExactly("book_shared", "book_semantic", "book_lexical");
        assertThat(TextbookRetrievalProperties.defaults().rerank().maxCoarseDocumentCandidates()).isEqualTo(5);
        assertThat(TextbookRetrievalProperties.defaults().rerank().coarsePageCandidateLimit()).isEqualTo(25);
    }

    private static TextbookSearchHit hit(String chunkId) {
        return new TextbookSearchHit(
                chunkId,
                chunkId,
                1.0d,
                "test",
                "book",
                "book",
                "volume",
                List.of(),
                1,
                "1",
                "title",
                "text",
                "",
                List.of(),
                "pages/p001.png",
                "content_page",
                null);
    }

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

        assertThat(response.retrievalStrategy()).isEqualTo("two_stage_doc_page_v4_bounded_semantic_first_parent_rerank");
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
    void returnsTheStrongestSamePageBlockForAWorkerSubheadingHit() throws Exception {
        Path root = tempDir.resolve("processed_books");
        Path bookRoot = root.resolve("book_a");
        Files.createDirectories(bookRoot.resolve("jsonl"));
        Files.writeString(root.resolve("catalog.jsonl"), """
                {"doc_id":"book_a","book_name":"教材A","volume":"选择性必修 第三册","book_root":"%s","manifest":"%s","chunk_count":3,"page_count":1,"ai_ok":false}
                """.formatted(escape(bookRoot), escape(bookRoot.resolve("manifest.json"))));
        Files.writeString(bookRoot.resolve("jsonl/chunks.jsonl"), """
                {"chunk_id":"book_a_p113_section_001","section_id":"book_a_section_refraction","doc_id":"book_a","book_name":"教材A","volume":"选择性必修 第三册","chapter_path":["第六章 导数及其应用","利用导数来推导光的折射定律"],"page_no":113,"printed_page_no":"106","chunk_type":"section_heading","section_title":"利用导数来推导光的折射定律","text":"利用导数来推导光的折射定律","formula_text":"","image_rel_paths":[],"source_page_image":"pages/p113.png"}
                {"chunk_id":"book_a_p113_section_002","section_id":"book_a_section_refraction","doc_id":"book_a","book_name":"教材A","volume":"选择性必修 第三册","chapter_path":["第六章 导数及其应用","利用导数来推导光的折射定律"],"page_no":113,"printed_page_no":"106","chunk_type":"section_prose","section_title":"利用导数来推导光的折射定律","text":"光学中的费马原理给出光的折射定律。","formula_text":"","image_rel_paths":[],"source_page_image":"pages/p113.png"}
                {"chunk_id":"book_a_p113_section_003","section_id":"book_a_section_refraction","doc_id":"book_a","book_name":"教材A","volume":"选择性必修 第三册","chapter_path":["第六章 导数及其应用","利用导数来推导光的折射定律"],"page_no":113,"printed_page_no":"106","chunk_type":"section_figure_caption","section_title":"利用导数来推导光的折射定律","text":"图1 图2","formula_text":"","image_rel_paths":[],"source_page_image":"pages/p113.png"}
                """);

        CapturingCandidateCorpusSearchEngine searchEngine = new CapturingCandidateCorpusSearchEngine();
        TextbookRetrievalService service = new TextbookRetrievalService(
                new TextbookCatalogReader(),
                new TextbookChunkReader(),
                searchEngine,
                new NoopRetrievalAuditSink(),
                new DisabledTextbookSearchCache(),
                new RedisTextbookSearchCacheProperties(false, "math-agent:test:disabled", Duration.ofMinutes(10), Duration.ofMinutes(1)),
                TeacherResourceGraphAlignmentService.disabled(),
                new com.doob.mathagent.resources.TextbookPageImageService(new TextbookCatalogReader()),
                null,
                new GroupedSectionPageTextSearchService(),
                com.doob.mathagent.vector.service.TestVectorIndexService.successful(
                        new com.doob.mathagent.teacher.service.InMemoryTeacherResourceStore(),
                        new com.doob.mathagent.teacher.service.InMemoryTeacherDocumentBlockStore()),
                TextbookRetrievalProperties.defaults());

        TextbookSearchResponse response = service.search(root, new TextbookSearchRequest("利用导数推导折射定律", 5));

        // Heading, prose, and caption are technical siblings of one visible small-heading block.
        // The result keeps the prose representative so one block does not consume three result slots.
        assertThat(response.hits()).extracting(TextbookSearchHit::chunkId)
                .containsExactly("book_a_p113_section_002");
        assertThat(response.hits()).extracting(TextbookSearchHit::sectionId)
                .containsOnly("book_a_section_refraction");
        // The on-disk section library owns the source text.  BM25 must search those physical records directly;
        // a cached concatenated block corpus would duplicate every textbook paragraph in memory.
        assertThat(searchEngine.candidateChunkIds()).containsExactly(
                "book_a_p113_section_001",
                "book_a_p113_section_002",
                "book_a_p113_section_003");
    }

    @Test
    void keepsTheActuallyRecalledCrossPageChildAsTheReturnedEvidencePage() throws Exception {
        Path root = tempDir.resolve("processed_books-cross-page-child");
        Path bookRoot = root.resolve("book_a");
        Files.createDirectories(bookRoot.resolve("jsonl"));
        Files.writeString(root.resolve("catalog.jsonl"), """
                {"doc_id":"book_a","book_name":"教材A","volume":"选择性必修","book_root":"%s","manifest":"%s","chunk_count":2,"page_count":2,"ai_ok":false}
                """.formatted(escape(bookRoot), escape(bookRoot.resolve("manifest.json"))));
        Files.writeString(bookRoot.resolve("jsonl/chunks.jsonl"), """
                {"chunk_id":"book_a_p012_section_001","section_id":"book_a_section_cross_page","doc_id":"book_a","book_name":"教材A","volume":"选择性必修","chapter_path":["第一章","同一小标题"],"page_no":12,"printed_page_no":"9","chunk_type":"section_prose","section_title":"同一小标题","text":"这是较长的背景正文，用于模拟跨页小标题的非命中 sibling 内容。","formula_text":"","image_rel_paths":[],"source_page_image":"pages/p012.png"}
                {"chunk_id":"book_a_p014_section_001","section_id":"book_a_section_cross_page","doc_id":"book_a","book_name":"教材A","volume":"选择性必修","chapter_path":["第一章","同一小标题"],"page_no":14,"printed_page_no":"11","chunk_type":"section_prose","section_title":"同一小标题","text":"独有检索术语只出现在这一页的真实正文中。","formula_text":"","image_rel_paths":[],"source_page_image":"pages/p014.png"}
                """);
        TextbookRetrievalService service = com.doob.mathagent.retrieval.TextbookRetrievalServiceFixture.service(
                new TextbookCatalogReader(),
                new TextbookChunkReader(),
                new LocalTextbookBm25SearchEngine(),
                new NoopRetrievalAuditSink());

        TextbookSearchResponse response = service.search(root, new TextbookSearchRequest("独有检索术语", 3));

        assertThat(response.hits()).isNotEmpty();
        assertThat(response.hits().getFirst().chunkId()).isEqualTo("book_a_p014_section_001");
        assertThat(response.hits().getFirst().pageNo()).isEqualTo(14);
    }

    @Test
    void indexesCrossPageHeadingMembersByReferenceWithoutMergingDifferentVisibleHeadings() {
        TextbookChunk firstPage = sectionChunk(
                "book_a_p050_section_001", 50, "book_a_section_sequence", "5.2 等差数列17", "等差数列的定义。");
        TextbookChunk continuation = sectionChunk(
                "book_a_p051_section_001", 51, "book_a_section_sequence", "5.2 等差数列19", "等差数列的通项公式。");
        TextbookChunk differentHeading = sectionChunk(
                "book_a_p052_section_001", 52, "book_a_section_sequence", "5.3 等比数列", "等比数列的定义。");

        TextbookRetrievalService.LogicalBlockIndex index = TextbookRetrievalService.logicalSectionIndex(
                List.of(firstPage, continuation, differentHeading));

        assertThat(index.membersByBlockKey()).hasSize(2);
        assertThat(index.membersByBlockKey().values())
                .anySatisfy(members -> {
                    assertThat(members).hasSize(2);
                    assertThat(members.getFirst()).isSameAs(firstPage);
                    assertThat(members.get(1)).isSameAs(continuation);
                })
                .anySatisfy(members -> {
                    assertThat(members).hasSize(1);
                    assertThat(members.getFirst()).isSameAs(differentHeading);
                });
        // The index retains the exact loaded records rather than copying their full text into another corpus.
        assertThat(index.representativeByBlockKey().values())
                .anySatisfy(representative -> assertThat(representative).isSameAs(continuation))
                .anySatisfy(representative -> assertThat(representative).isSameAs(differentHeading));
    }

    @Test
    void recordsRetrievalAuditEventWithQueryAndRankedHits() throws Exception {
        Path root = tempDir.resolve("processed_books");
        Path bookRoot = root.resolve("book_a");
        Files.createDirectories(bookRoot.resolve("jsonl"));
        Files.writeString(root.resolve("catalog.jsonl"), """
                {"doc_id":"book_a","book_name":"教材A","volume":"必修 第一册","book_root":"%s","manifest":"%s","chunk_count":2,"page_count":2,"ai_ok":false}
                """.formatted(escape(bookRoot), escape(bookRoot.resolve("manifest.json"))));
        Files.writeString(bookRoot.resolve("jsonl/chunks.jsonl"), """
                {"chunk_id":"book_a_p010_text_001","doc_id":"book_a","book_name":"教材A","volume":"必修 第一册","chapter_path":["函数"],"page_no":10,"printed_page_no":"8","chunk_type":"page_summary","section_title":"函数定义","text":"函数中的对应关系涉及定义域和值域等基本概念。","formula_text":"","image_rel_paths":[],"source_page_image":"pages/p010.png"}
                {"chunk_id":"book_a_p020_text_001","doc_id":"book_a","book_name":"教材A","volume":"必修 第一册","chapter_path":["几何"],"page_no":20,"printed_page_no":"18","chunk_type":"page_summary","section_title":"三角形","text":"三角形的边角关系可以用于完成几何证明。","formula_text":"","image_rel_paths":[],"source_page_image":"pages/p020.png"}
                """);
        CapturingRetrievalAuditSink auditSink = new CapturingRetrievalAuditSink();
        TextbookRetrievalService service = com.doob.mathagent.retrieval.TextbookRetrievalServiceFixture.service(
                new TextbookCatalogReader(),
                new TextbookChunkReader(),
                new LocalTextbookBm25SearchEngine(),
                auditSink);

        TextbookSearchResponse response = service.search(root, new TextbookSearchRequest("函数 对应关系", 5));

        assertThat(response.queryId()).isNotBlank();
        assertThat(response.hits())
                .first()
                .extracting(TextbookSearchHit::pageImageUri)
                .isEqualTo("/api/resources/textbooks/book_a/pages/10/image");
        assertThat(auditSink.event()).isNotNull();
        assertThat(auditSink.event().queryId()).isEqualTo(response.queryId());
        assertThat(auditSink.event().tenantId()).isEqualTo("default");
        assertThat(auditSink.event().queryText()).isEqualTo("函数 对应关系");
        assertThat(auditSink.event().retrievalStrategy()).isEqualTo("two_stage_doc_page_v4_bounded_semantic_first_parent_rerank");
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
                {"doc_id":"book_a","book_name":"教材A","volume":"必修 第一册","book_root":"%s","manifest":"%s","chunk_count":1,"page_count":1,"ai_ok":false}
                """.formatted(escape(bookRoot), escape(bookRoot.resolve("manifest.json"))));
        Files.writeString(bookRoot.resolve("jsonl/chunks.jsonl"), """
                {"chunk_id":"book_a_p101_text_001","doc_id":"book_a","book_name":"教材A","volume":"必修 第一册","chapter_path":["函数"],"page_no":101,"printed_page_no":"98","chunk_type":"page_summary","section_title":"分段函数","text":"分段函数在定义域的不同部分使用不同解析式表示。","formula_text":"","image_rel_paths":[],"source_page_image":"pages/p101.png"}
                """);
        CountingSearchEngine searchEngine = new CountingSearchEngine();
        InMemoryTextbookSearchCache cache = new InMemoryTextbookSearchCache();
        TextbookRetrievalService service = com.doob.mathagent.retrieval.TextbookRetrievalServiceFixture.service(
                new TextbookCatalogReader(),
                new TextbookChunkReader(),
                searchEngine,
                new NoopRetrievalAuditSink(),
                cache,
                new RedisTextbookSearchCacheProperties(true, "math-agent:test:retrieval", Duration.ofMinutes(5), Duration.ofMinutes(1)));

        TextbookSearchResponse first = service.search(root, new TextbookSearchRequest("分段函数", 5));
        TextbookSearchResponse second = service.search(root, new TextbookSearchRequest("分段函数", 5));

        assertThat(first.retrievalStrategy()).isEqualTo("two_stage_doc_page_v4_bounded_semantic_first_parent_rerank");
        assertThat(second.retrievalStrategy()).isEqualTo("redis_cache_two_stage_doc_page_v4_bounded_semantic_first_parent_rerank");
        assertThat(second.queryId()).isNotEqualTo(first.queryId());
        assertThat(second.hits()).extracting(TextbookSearchHit::chunkId).containsExactly("book_a_p101_text_001");
        assertThat(searchEngine.searchCount()).isEqualTo(1);
        assertThat(cache.putCount()).isEqualTo(1);
    }

    @Test
    void cachesEmptyResultsWithShortNullValueTtlToPreventRepeatedCachePenetration() throws Exception {
        Path root = tempDir.resolve("processed-books-empty-result");
        Path bookRoot = root.resolve("book-a");
        Files.createDirectories(bookRoot.resolve("jsonl"));
        Files.writeString(root.resolve("catalog.jsonl"), """
                {"doc_id":"book_a","book_name":"Textbook A","volume":"Required One","book_root":"%s","manifest":"%s","chunk_count":1,"page_count":1,"ai_ok":false}
                """.formatted(escape(bookRoot), escape(bookRoot.resolve("manifest.json"))));
        Files.writeString(bookRoot.resolve("jsonl/chunks.jsonl"), """
                {"chunk_id":"book_a_p101_text_001","doc_id":"book_a","book_name":"Textbook A","volume":"Required One","chapter_path":["functions"],"page_no":101,"printed_page_no":"98","chunk_type":"page_summary","section_title":"Piecewise function","text":"A piecewise function uses different expressions on different parts of its domain.","formula_text":"","image_rel_paths":[],"source_page_image":"pages/p101.png"}
                """);
        CountingSearchEngine searchEngine = new CountingSearchEngine();
        CapturingTextbookSearchCache cache = new CapturingTextbookSearchCache();
        Duration nullValueTtl = Duration.ofSeconds(30);
        TextbookRetrievalService service = com.doob.mathagent.retrieval.TextbookRetrievalServiceFixture.service(
                new TextbookCatalogReader(),
                new TextbookChunkReader(),
                searchEngine,
                new NoopRetrievalAuditSink(),
                cache,
                new RedisTextbookSearchCacheProperties(
                        true, "math-agent:test:empty-result", Duration.ofMinutes(5), nullValueTtl));

        TextbookSearchRequest request = new TextbookSearchRequest(
                "unmatched-query-token", 5, List.of("missing-book"));
        TextbookSearchResponse first = service.search(root, request);
        TextbookSearchResponse second = service.search(root, request);

        assertThat(first.hits()).isEmpty();
        assertThat(second.retrievalStrategy()).isEqualTo("redis_cache_two_stage_doc_page_v4_bounded_semantic_first_parent_rerank");
        assertThat(cache.putCount()).isEqualTo(1);
        assertThat(cache.lastTtl()).isEqualTo(nullValueTtl);
    }

    @Test
    void favorsTheRightBookBeforeRerankingPagesInsideIt() throws Exception {
        Path root = tempDir.resolve("processed_books");
        Path geometryRoot = root.resolve("geometry_book");
        Path statsRoot = root.resolve("stats_book");
        Files.createDirectories(geometryRoot.resolve("jsonl"));
        Files.createDirectories(statsRoot.resolve("jsonl"));
        Files.writeString(root.resolve("catalog.jsonl"), """
                {"doc_id":"geometry_book","book_name":"几何教材","volume":"选择性必修","book_root":"%s","manifest":"%s","chunk_count":1,"page_count":1,"ai_ok":false}
                {"doc_id":"stats_book","book_name":"统计教材","volume":"必修","book_root":"%s","manifest":"%s","chunk_count":2,"page_count":2,"ai_ok":false}
                """.formatted(
                escape(geometryRoot),
                escape(geometryRoot.resolve("manifest.json")),
                escape(statsRoot),
                escape(statsRoot.resolve("manifest.json"))));
        Files.writeString(geometryRoot.resolve("jsonl/chunks.jsonl"), """
                {"chunk_id":"geometry_p134","doc_id":"geometry_book","book_name":"几何教材","volume":"选择性必修","chapter_path":["立体几何"],"page_no":134,"printed_page_no":"134","chunk_type":"page_summary","section_title":"证明方法","text":"空间中的直线与平面关系可以通过角度和位置进行证明。","formula_text":"","image_rel_paths":[],"source_page_image":"pages/p134.png"}
                """);
        Files.writeString(statsRoot.resolve("jsonl/chunks.jsonl"), """
                {"chunk_id":"stats_p134","doc_id":"stats_book","book_name":"统计教材","volume":"必修","chapter_path":["统计与概率"],"page_no":134,"printed_page_no":"134","chunk_type":"page_summary","section_title":"样本是否可靠","text":"利用样本频率估计概率并判断统计结论是否可靠。","formula_text":"","image_rel_paths":[],"source_page_image":"pages/p134.png"}
                {"chunk_id":"stats_p135","doc_id":"stats_book","book_name":"统计教材","volume":"必修","chapter_path":["统计与概率"],"page_no":135,"printed_page_no":"135","chunk_type":"page_summary","section_title":"概率应用","text":"概率应用需要结合数据估计结果并判断结论可靠性。","formula_text":"","image_rel_paths":[],"source_page_image":"pages/p135.png"}
                """);

        TextbookRetrievalService service = com.doob.mathagent.retrieval.TextbookRetrievalServiceFixture.service(
                new TextbookCatalogReader(),
                new TextbookChunkReader(),
                new LocalTextbookBm25SearchEngine(),
                new NoopRetrievalAuditSink());

        TextbookSearchResponse response = service.search(root, new TextbookSearchRequest("概率 可靠性 判断", 3));

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
                new RedisTextbookSearchCacheProperties(false, "math-agent:test:disabled", Duration.ofMinutes(10), Duration.ofMinutes(1)),
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
    void keepsDistinctEvidenceClauseAlongsideGraphMatchedChapterClause() throws Exception {
        Path root = tempDir.resolve("processed_books-preserve-clause");
        Path bookRoot = root.resolve("book_a");
        Files.createDirectories(bookRoot.resolve("jsonl"));
        Files.writeString(root.resolve("catalog.jsonl"), """
                {"doc_id":"book_a","book_name":"教材A","volume":"选择性必修 第三册","book_root":"%s","manifest":"%s","chunk_count":2,"page_count":2,"ai_ok":false}
                """.formatted(escape(bookRoot), escape(bookRoot.resolve("manifest.json"))));
        Files.writeString(bookRoot.resolve("jsonl/chunks.jsonl"), """
                {"chunk_id":"book_a_p083","doc_id":"book_a","book_name":"教材A","volume":"选择性必修 第三册","chapter_path":["第六章导数及其应用"],"page_no":83,"printed_page_no":"76","chunk_type":"page_summary","section_title":"第六章导数及其应用","text":"这就说明，导函数存在时，可以把某一点的导数看成导函数在该点的取值。","formula_text":"","image_rel_paths":[],"source_page_image":"pages/p083.png"}
                {"chunk_id":"book_a_p102","doc_id":"book_a","book_name":"教材A","volume":"选择性必修 第三册","chapter_path":["第六章导数及其应用","6.2 利用导数研究函数的性质"],"page_no":102,"printed_page_no":"95","chunk_type":"page_summary","section_title":"6.2 利用导数研究函数的性质","text":"已知函数f(x)的导函数为f′(x)，且f′(x)>0在区间(-1,2)上恒成立，判断单调区间。","formula_text":"","image_rel_paths":[],"source_page_image":"pages/p102.png"}
                """);
        CapturingSearchEngine searchEngine = new CapturingSearchEngine();
        CountingGraphAlignmentService graphAlignmentService = new CountingGraphAlignmentService(
                new TeacherResourceGraphAlignmentService.QueryGraphContext(
                        List.of("topic-derivative"),
                        List.of("topic-derivative", "topic-monotonicity"),
                        List.of("导数"),
                        List.of("导数", "单调性")));
        TextbookRetrievalService service = new TextbookRetrievalService(
                new TextbookCatalogReader(),
                new TextbookChunkReader(),
                searchEngine,
                new NoopRetrievalAuditSink(),
                new DisabledTextbookSearchCache(),
                new RedisTextbookSearchCacheProperties(false, "math-agent:test:disabled", Duration.ofMinutes(10), Duration.ofMinutes(1)),
                graphAlignmentService,
                new com.doob.mathagent.resources.TextbookPageImageService(new TextbookCatalogReader()),
                com.doob.mathagent.vector.service.TestVectorIndexService.successful(
                        new com.doob.mathagent.teacher.service.InMemoryTeacherResourceStore(),
                        new com.doob.mathagent.teacher.service.InMemoryTeacherDocumentBlockStore()));

        service.search(
                root,
                new TextbookSearchRequest("备课时只查公共教材库，我想找第六章导数及其应用这一页里关于第六章导数及其应用这就说明的课本原文依据。", 3));

        assertThat(searchEngine.lastQuery()).contains("第六章导数及其应用");
        assertThat(searchEngine.lastQuery()).contains("这就说明");
        assertThat(graphAlignmentService.callCount()).isZero();
    }

    @Test
    void loadsCorpusOnceWhenConcurrentRequestsMissCacheTogether() throws Exception {
        Path root = tempDir.resolve("processed_books");
        Path bookRoot = root.resolve("book_a");
        Files.createDirectories(bookRoot.resolve("jsonl"));
        Files.writeString(root.resolve("catalog.jsonl"), """
                {"doc_id":"book_a","book_name":"教材A","volume":"必修 第一册","book_root":"%s","manifest":"%s","chunk_count":1,"page_count":1,"ai_ok":false}
                """.formatted(escape(bookRoot), escape(bookRoot.resolve("manifest.json"))));
        Files.writeString(bookRoot.resolve("jsonl/chunks.jsonl"), """
                {"chunk_id":"book_a_p101_text_001","doc_id":"book_a","book_name":"教材A","volume":"必修 第一册","chapter_path":["函数"],"page_no":101,"printed_page_no":"98","chunk_type":"page_summary","section_title":"分段函数","text":"分段函数在定义域的不同部分使用不同解析式表示。","formula_text":"","image_rel_paths":[],"source_page_image":"pages/p101.png"}
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
            futures.add(executor.submit(() -> service.search(root, new TextbookSearchRequest("分段函数", 5))));
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
                {"doc_id":"book_a","book_name":"教材A","volume":"必修 第一册","book_root":"%s","manifest":"%s","chunk_count":1,"page_count":1,"ai_ok":false}
                """.formatted(escape(bookRoot), escape(bookRoot.resolve("manifest.json"))));
        Path chunksPath = bookRoot.resolve("jsonl/chunks.jsonl");
        Files.writeString(chunksPath, """
                {"chunk_id":"book_a_p101_text_001","doc_id":"book_a","book_name":"教材A","volume":"必修 第一册","chapter_path":["函数"],"page_no":101,"printed_page_no":"98","chunk_type":"page_summary","section_title":"分段函数","text":"分段函数在定义域的不同部分使用不同解析式表示。","formula_text":"","image_rel_paths":[],"source_page_image":"pages/p101.png"}
                """);
        TextbookRetrievalService service = com.doob.mathagent.retrieval.TextbookRetrievalServiceFixture.service(
                new TextbookCatalogReader(),
                new TextbookChunkReader(),
                new LocalTextbookBm25SearchEngine(),
                new NoopRetrievalAuditSink());

        TextbookSearchResponse warmed = service.search(root, new TextbookSearchRequest("分段函数", 5));
        Files.delete(chunksPath);
        TextbookSearchResponse fallback = service.search(root, new TextbookSearchRequest("分段函数", 5));

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
                {"doc_id":"book_a","book_name":"教材A","volume":"必修 第一册","book_root":"%s","manifest":"%s","chunk_count":1,"page_count":1,"ai_ok":false}
                """.formatted(escape(bookRoot), escape(bookRoot.resolve("manifest.json"))));
        Files.writeString(bookRoot.resolve("jsonl/chunks.jsonl"), """
                {"chunk_id":"book_a_p101_text_001","doc_id":"book_a","book_name":"教材A","volume":"必修 第一册","chapter_path":["函数"],"page_no":101,"printed_page_no":"98","chunk_type":"page_summary","section_title":"分段函数","text":"分段函数在定义域的不同部分使用不同解析式表示。","formula_text":"","image_rel_paths":[],"source_page_image":"pages/p101.png"}
                """);
        AlwaysFailingTextbookChunkReader chunkReader = new AlwaysFailingTextbookChunkReader();
        TextbookRetrievalService service = com.doob.mathagent.retrieval.TextbookRetrievalServiceFixture.service(
                new TextbookCatalogReader(),
                chunkReader,
                new LocalTextbookBm25SearchEngine(),
                new NoopRetrievalAuditSink());

        assertThatThrownBy(() -> service.search(root, new TextbookSearchRequest("分段函数", 5)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Failed to load textbook corpus");
        assertThatThrownBy(() -> service.search(root, new TextbookSearchRequest("分段函数", 5)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cooldown");

        assertThat(chunkReader.readCount()).isEqualTo(1);
    }

    private static String escape(Path path) {
        return path.toString().replace("\\", "\\\\");
    }

    private static TextbookChunk sectionChunk(
            String chunkId,
            int pageNo,
            String sectionId,
            String sectionTitle,
            String text) {
        return new TextbookChunk(
                chunkId,
                "book_a",
                "教材A",
                "必修 第一册",
                List.of("第五章 数列", sectionTitle),
                pageNo,
                String.valueOf(pageNo),
                "section_prose",
                sectionTitle,
                text,
                "",
                List.of(),
                "pages/p%03d.png".formatted(pageNo),
                sectionId);
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

    /** Captures the actual lexical corpus so the test detects accidental full-text logical-block copies. */
    private static final class CapturingCandidateCorpusSearchEngine extends LocalTextbookBm25SearchEngine {
        private List<String> candidateChunkIds = List.of();

        @Override
        public List<TextbookSearchHit> search(
                String query,
                List<TextbookChunk> chunks,
                int limit,
                TeacherResourceGraphAlignmentService.QueryGraphContext queryGraph) {
            candidateChunkIds = chunks.stream().map(TextbookChunk::chunkId).toList();
            return super.search(query, chunks, limit, queryGraph);
        }

        private List<String> candidateChunkIds() {
            return candidateChunkIds;
        }
    }

    private static final class GroupedSectionPageTextSearchService extends TextbookPageTextSearchService {

        private GroupedSectionPageTextSearchService() {
            super(
                    new com.doob.mathagent.vector.service.VectorIndexProperties(
                            true,
                            "http://test-milvus.local:19530",
                            "test-token",
                            "test-collection",
                            512,
                            "http://test-worker.local/v1",
                            "test-key",
                            "test-bge",
                            1000),
                    (uri, headers, body, timeout) -> new com.doob.mathagent.vector.service.VectorHttpResponse(500, "unused"),
                    new com.doob.mathagent.resources.TextbookPageImageService(new TextbookCatalogReader()));
        }

        @Override
        public TextbookPageTextSearchResponse search(TextbookPageTextSearchRequest request) {
            return new TextbookPageTextSearchResponse(
                    request.query(),
                    request.limit(),
                    "local_bge_embedding",
                    "test-bge",
                    1,
                    List.of(new TextbookPageTextSearchHit(
                            0.95d,
                            "book_a_p113_section_002",
                            "book_a_section_refraction",
                            "book_a_p113_ai_001",
                            "book_a",
                            "教材A",
                            "第六章 导数及其应用 / 利用导数来推导光的折射定律",
                            113,
                            "106",
                            "利用导数来推导光的折射定律",
                            "光学中的费马原理给出光的折射定律。",
                            "")));
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

    private static final class CapturingTextbookSearchCache extends InMemoryTextbookSearchCache {
        private Duration lastTtl;

        @Override
        public void put(String cacheKey, CachedTextbookSearch value, Duration ttl) {
            super.put(cacheKey, value, ttl);
            lastTtl = ttl;
        }

        Duration lastTtl() {
            return lastTtl;
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

    /** Verifies that public-textbook retrieval no longer invokes shared teacher-resource graph expansion. */
    private static final class CountingGraphAlignmentService extends TeacherResourceGraphAlignmentService {
        private final TeacherResourceGraphAlignmentService.QueryGraphContext context;
        private int callCount;

        private CountingGraphAlignmentService(TeacherResourceGraphAlignmentService.QueryGraphContext context) {
            super(new StubKnowledgeQuestionBankStore());
            this.context = context;
        }

        @Override
        public TeacherResourceGraphAlignmentService.QueryGraphContext alignQuery(
                String tenantId,
                String viewerRole,
                String viewerSubjectId,
                String query) {
            callCount++;
            return context;
        }

        private int callCount() {
            return callCount;
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
