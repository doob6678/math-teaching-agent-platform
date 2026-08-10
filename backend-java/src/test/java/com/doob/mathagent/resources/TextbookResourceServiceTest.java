package com.doob.mathagent.resources;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TextbookResourceServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void summarizesProcessedBooksWithoutCopyingExternalResources() throws Exception {
        Path root = tempDir.resolve("processed_books");
        Files.createDirectories(root);
        Files.writeString(root.resolve("catalog.jsonl"), """
                {"doc_id":"book_a","book_name":"教材A","volume":"必修 第一册","book_root":"%s","manifest":"%s","chunk_count":3,"page_count":2,"ai_ok":false}
                """.formatted(root.resolve("book_a").toString().replace("\\", "\\\\"), root.resolve("book_a/manifest.json").toString().replace("\\", "\\\\")));

        TextbookResourceService service = new TextbookResourceService(new TextbookCatalogReader());

        TextbookResourceSummary summary = service.summarize(root);

        assertThat(summary.processedBooksRoot()).isEqualTo(root.toAbsolutePath().normalize());
        assertThat(summary.bookCount()).isEqualTo(1);
        assertThat(summary.totalChunkCount()).isEqualTo(3);
        assertThat(summary.totalPageCount()).isEqualTo(2);
        assertThat(summary.books()).extracting(TextbookCatalogItem::bookName).containsExactly("教材A");
    }

    @Test
    void summarizesC2SectionCatalogFields() throws Exception {
        Path root = tempDir.resolve("processed_books_c2");
        Files.createDirectories(root);
        Files.writeString(root.resolve("catalog.jsonl"), """
                {"doc_id":"book_a","book_name":"教材A","volume":"必修 第一册","book_root":"book_a","source_page_rows":2,"section_count":3}
                {"doc_id":"book_b","book_name":"教材B","volume":"必修 第二册","book_root":"book_b","source_page_rows":4,"section_count":5}
                """);

        TextbookResourceSummary summary = new TextbookResourceService(new TextbookCatalogReader()).summarize(root);

        assertThat(summary.bookCount()).isEqualTo(2);
        assertThat(summary.totalChunkCount()).isEqualTo(8);
        assertThat(summary.totalPageCount()).isEqualTo(6);
    }
}
