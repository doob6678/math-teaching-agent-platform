package com.doob.mathagent.retrieval;

import static org.assertj.core.api.Assertions.assertThat;

import com.doob.mathagent.infrastructure.security.RequestSubjectResolver;
import com.doob.mathagent.resources.TextbookCatalogReader;
import com.doob.mathagent.resources.TextbookChunkReader;
import com.doob.mathagent.resources.TextbookResourceProperties;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TextbookRetrievalControllerTest {

    @TempDir
    Path tempDir;

    @Test
    void exposesTextbookSearchEndpointContract() throws Exception {
        Path root = tempDir.resolve("processed_books");
        Path bookRoot = root.resolve("book_a");
        Files.createDirectories(bookRoot.resolve("jsonl"));
        Files.writeString(root.resolve("catalog.jsonl"), """
                {"doc_id":"book_a","book_name":"教材A","volume":"必修 第一册","book_root":"%s","manifest":"%s","chunk_count":1,"page_count":1,"ai_ok":false}
                """.formatted(escape(bookRoot), escape(bookRoot.resolve("manifest.json"))));
        Files.writeString(bookRoot.resolve("jsonl/chunks.jsonl"), """
                {"chunk_id":"book_a_p101_text_001","doc_id":"book_a","book_name":"教材A","volume":"必修 第一册","chapter_path":["第三章 函数"],"page_no":101,"printed_page_no":"98","chunk_type":"page_summary","section_title":"分段函数","text":"分段函数是在定义域的不同部分用不同解析式表示的函数。","formula_text":"","image_rel_paths":[],"source_page_image":"pages/p101.png"}
                """);
        TextbookRetrievalService service = com.doob.mathagent.retrieval.TextbookRetrievalServiceFixture.service(
                new TextbookCatalogReader(),
                new TextbookChunkReader(),
                new LocalTextbookBm25SearchEngine(),
                new NoopRetrievalAuditSink());
        TextbookRetrievalController controller = new TextbookRetrievalController(
                service,
                new TextbookResourceProperties(root),
                RequestSubjectResolver.localDevelopment());

        TextbookSearchResponse response = controller.search("分段函数", 3);

        assertThat(response.query()).isEqualTo("分段函数");
        assertThat(response.limit()).isEqualTo(3);
        assertThat(response.hits()).hasSize(1);
    }

    private static String escape(Path path) {
        return path.toString().replace("\\", "\\\\");
    }
}
