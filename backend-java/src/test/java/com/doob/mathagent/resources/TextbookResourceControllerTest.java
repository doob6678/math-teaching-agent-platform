package com.doob.mathagent.resources;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TextbookResourceControllerTest {

    @TempDir
    Path tempDir;

    @Test
    void returnsTextbookSummaryFromConfiguredProcessedBooksRoot() throws Exception {
        Path root = tempDir.resolve("processed_books");
        Files.createDirectories(root);
        Files.writeString(root.resolve("catalog.jsonl"), """
                {"doc_id":"book_a","book_name":"教材A","volume":"必修 第一册","book_root":"%s","manifest":"%s","chunk_count":3,"page_count":2,"ai_ok":false}
                {"doc_id":"book_b","book_name":"教材B","volume":"必修 第二册","book_root":"%s","manifest":"%s","chunk_count":5,"page_count":4,"ai_ok":true}
                """.formatted(
                root.resolve("book_a").toString().replace("\\", "\\\\"),
                root.resolve("book_a/manifest.json").toString().replace("\\", "\\\\"),
                root.resolve("book_b").toString().replace("\\", "\\\\"),
                root.resolve("book_b/manifest.json").toString().replace("\\", "\\\\")));

        TextbookResourceController controller = new TextbookResourceController(
                new TextbookResourceService(new TextbookCatalogReader()),
                new TextbookResourceProperties(root));

        TextbookResourceSummary summary = controller.summary();

        assertThat(summary.bookCount()).isEqualTo(2);
        assertThat(summary.totalChunkCount()).isEqualTo(8);
        assertThat(summary.totalPageCount()).isEqualTo(6);
        assertThat(summary.books()).extracting(TextbookCatalogItem::docId).containsExactly("book_a", "book_b");
    }
}
