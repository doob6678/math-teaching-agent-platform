package com.doob.mathagent.retrieval;

import static org.assertj.core.api.Assertions.assertThat;

import com.doob.mathagent.resources.TextbookCatalogReader;
import com.doob.mathagent.resources.TextbookChunk;
import com.doob.mathagent.resources.TextbookChunkReader;
import java.nio.file.Path;
import java.nio.file.Files;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TextbookRetrievalServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void searchesChunksDeclaredByCatalogWithBm25First() throws Exception {
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

        TextbookRetrievalService service = new TextbookRetrievalService(
                new TextbookCatalogReader(),
                new TextbookChunkReader(),
                new LocalTextbookBm25SearchEngine(),
                new NoopRetrievalAuditSink());

        TextbookSearchResponse response = service.search(root, new TextbookSearchRequest("分段函数的定义", 5));

        assertThat(response.retrievalStrategy()).isEqualTo("local_bm25_first");
        assertThat(response.total()).isEqualTo(2);
        assertThat(response.hits())
                .isNotEmpty()
                .first()
                .extracting(TextbookSearchHit::chunkId)
                .isEqualTo("book_a_p101_text_001");
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
        TextbookRetrievalService service = new TextbookRetrievalService(
                new TextbookCatalogReader(),
                new TextbookChunkReader(),
                new LocalTextbookBm25SearchEngine(),
                auditSink);

        TextbookSearchResponse response = service.search(root, new TextbookSearchRequest("function mapping", 5));

        assertThat(response.queryId()).isNotBlank();
        assertThat(auditSink.event()).isNotNull();
        assertThat(auditSink.event().queryId()).isEqualTo(response.queryId());
        assertThat(auditSink.event().tenantId()).isEqualTo("default");
        assertThat(auditSink.event().queryText()).isEqualTo("function mapping");
        assertThat(auditSink.event().retrievalStrategy()).isEqualTo("local_bm25_first");
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
        TextbookRetrievalService service = new TextbookRetrievalService(
                new TextbookCatalogReader(),
                chunkReader,
                new LocalTextbookBm25SearchEngine(),
                new NoopRetrievalAuditSink());

        service.search(root, new TextbookSearchRequest("分段函数", 5));
        service.search(root, new TextbookSearchRequest("函数", 5));

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
}
