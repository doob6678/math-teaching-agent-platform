package com.doob.mathagent.resources;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TextbookCatalogReaderTest {

    @TempDir
    Path tempDir;

    @Test
    void readsCatalogJsonlAndKeepsManifestMetadata() throws Exception {
        Path catalog = tempDir.resolve("catalog.jsonl");
        Files.writeString(catalog, """
                {"doc_id":"renjiao_bbixiu1math","book_name":"人教B版必修一数学","volume":"必修 第一册","book_root":"C:/books/renjiao_bbixiu1math","manifest":"C:/books/renjiao_bbixiu1math/manifest.json","chunk_count":163,"page_count":154,"ai_ok":false}
                {"doc_id":"math_b_bixiu_4","book_name":"普通高中教科书·数学（B版）必修 第四册","volume":"必修 第四册","book_root":"C:/books/math_b_bixiu_4","manifest":"C:/books/math_b_bixiu_4/manifest.json","chunk_count":147,"page_count":142,"ai_ok":false}
                """);

        TextbookCatalogReader reader = new TextbookCatalogReader();

        assertThat(reader.read(catalog))
                .extracting(TextbookCatalogItem::docId)
                .containsExactly("renjiao_bbixiu1math", "math_b_bixiu_4");
        assertThat(reader.read(catalog).getFirst())
                .satisfies(item -> {
                    assertThat(item.bookName()).isEqualTo("人教B版必修一数学");
                    assertThat(item.chunkCount()).isEqualTo(163);
                    assertThat(item.pageCount()).isEqualTo(154);
                    assertThat(item.manifest()).endsWith("manifest.json");
                });
    }

    @Test
    void ignoresBlankLinesInCatalogJsonl() throws Exception {
        Path catalog = tempDir.resolve("catalog.jsonl");
        Files.writeString(catalog, "\n{\"doc_id\":\"book_a\",\"book_name\":\"A\",\"volume\":\"必修\",\"book_root\":\"C:/a\",\"manifest\":\"C:/a/manifest.json\",\"chunk_count\":1,\"page_count\":2,\"ai_ok\":true}\n\n");

        TextbookCatalogReader reader = new TextbookCatalogReader();

        assertThat(reader.read(catalog))
                .hasSize(1)
                .first()
                .extracting(TextbookCatalogItem::docId)
                .isEqualTo("book_a");
    }

    @Test
    void toleratesAdditionalFieldsFromGeneratedCatalog() throws Exception {
        Path catalog = tempDir.resolve("catalog.jsonl");
        Files.writeString(catalog, """
                {"doc_id":"book_with_source","book_name":"A","volume":"必修","book_root":"C:/books/a","manifest":"C:/books/a/manifest.json","chunk_count":3,"page_count":4,"ai_ok":true,"source_pdf":"C:/raw/a.pdf","generated_at":"2026-06-27T00:00:00Z"}
                """);

        TextbookCatalogReader reader = new TextbookCatalogReader();

        assertThat(reader.read(catalog))
                .hasSize(1)
                .first()
                .extracting(TextbookCatalogItem::docId)
                .isEqualTo("book_with_source");
    }
}
