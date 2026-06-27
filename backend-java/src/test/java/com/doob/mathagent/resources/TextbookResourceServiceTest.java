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
}
