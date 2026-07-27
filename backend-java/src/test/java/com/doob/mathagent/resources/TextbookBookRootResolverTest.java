package com.doob.mathagent.resources;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Verifies that generated textbook catalogs remain portable without weakening the corpus path boundary. */
class TextbookBookRootResolverTest {

    @TempDir
    Path tempDir;

    @Test
    void fallsBackToDocIdWhenCatalogContainsWindowsHostPath() throws Exception {
        Path mountedBook = Files.createDirectories(tempDir.resolve("math_b_bixiu_4"));
        TextbookCatalogItem item = item(
                "math_b_bixiu_4",
                "C:\\Users\\indexer\\processed_books\\math_b_bixiu_4");

        assertThat(TextbookBookRootResolver.resolve(tempDir, item)).isEqualTo(mountedBook.toAbsolutePath().normalize());
    }

    @Test
    void keepsRelativeCatalogPathInsideConfiguredRoot() throws Exception {
        Path mountedBook = Files.createDirectories(tempDir.resolve("nested/book_a"));
        TextbookCatalogItem item = item("book_a", "nested/book_a");

        assertThat(TextbookBookRootResolver.resolve(tempDir, item)).isEqualTo(mountedBook.toAbsolutePath().normalize());
    }

    @Test
    void rejectsTraversalWhenNeitherCatalogPathNorDocIdIsSafe() {
        TextbookCatalogItem item = item("../outside", "../outside");

        assertThatThrownBy(() -> TextbookBookRootResolver.resolve(tempDir, item))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("docId");
    }

    private static TextbookCatalogItem item(String docId, String bookRoot) {
        return new TextbookCatalogItem(docId, "测试教材", "必修", bookRoot, "manifest.json", 1, 1, true);
    }
}
