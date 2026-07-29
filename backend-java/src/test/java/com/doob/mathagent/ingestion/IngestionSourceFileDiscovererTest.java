package com.doob.mathagent.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Exercises real filesystem discovery rather than simulating a batch manifest. */
class IngestionSourceFileDiscovererTest {

    @TempDir
    Path input;

    @Test
    void discoversSupportedFilesRecursivelyInStableOrderAndHashesTheirBytes() throws Exception {
        Files.writeString(input.resolve("2024.pdf"), "paper-a");
        Files.createDirectory(input.resolve("nested"));
        Files.writeString(input.resolve("nested/2023.DOCX"), "paper-b");
        Files.writeString(input.resolve("ignored.txt"), "not an exam");

        var files = new IngestionSourceFileDiscoverer().discover(input);

        assertThat(files).extracting(DiscoveredSourceFile::fileName)
                .containsExactly("2024.pdf", "2023.DOCX");
        assertThat(files).allSatisfy(file -> assertThat(file.sha256()).hasSize(64));
    }
}
