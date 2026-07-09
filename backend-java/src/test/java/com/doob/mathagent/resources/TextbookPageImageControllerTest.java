package com.doob.mathagent.resources;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.Resource;
import org.springframework.http.ResponseEntity;

class TextbookPageImageControllerTest {

    @TempDir
    Path tempDir;

    @Test
    void streamsPageImageThroughControlledEndpoint() throws Exception {
        Path root = tempDir.resolve("processed_books");
        Path bookRoot = root.resolve("book_a");
        Files.createDirectories(bookRoot.resolve("pages"));
        Files.writeString(root.resolve("catalog.jsonl"), """
                {"doc_id":"book_a","book_name":"教材A","volume":"必修 第一册","book_root":"%s","manifest":"%s","chunk_count":1,"page_count":1,"ai_ok":false}
                """.formatted(escape(bookRoot), escape(bookRoot.resolve("manifest.json"))));
        Files.createDirectories(root.resolve("_page_image_index"));
        Files.writeString(root.resolve("_page_image_index/manifest.json"), """
                {"kind":"page_image_clip_index","row_count":1,"fingerprint":"test-fp"}
                """);
        Files.writeString(root.resolve("_page_image_index/metadata.jsonl"), """
                {"doc_id":"book_a","page_no":1,"source_page_image":"pages/p001.png"}
                """);
        byte[] imageBytes = new byte[] {(byte) 0x89, 0x50, 0x4e, 0x47};
        Files.write(bookRoot.resolve("pages/p001.png"), imageBytes);
        TextbookPageImageController controller = new TextbookPageImageController(
                new TextbookPageImageService(new TextbookCatalogReader()),
                new TextbookResourceProperties(root));

        ResponseEntity<Resource> response = controller.readPageImage("book_a", 1);

        assertThat(response.getHeaders().getContentDisposition().isInline()).isTrue();
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getInputStream().readAllBytes()).isEqualTo(imageBytes);
    }

    private static String escape(Path path) {
        return path.toString().replace("\\", "\\\\");
    }
}
