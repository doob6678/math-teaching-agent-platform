package com.doob.mathagent.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Covers the real filesystem preflight shared by the future Docker command and resumable run creator. */
class IngestionPreflightServiceTest {

    @TempDir
    Path input;

    @Test
    void createsAZeroWorkProgressSnapshotFromDiscoveredFiles() throws Exception {
        Files.writeString(input.resolve("2024.pdf"), "real bytes");
        IngestionCommandArguments arguments = IngestionCommandArguments.parse(List.of(
                "gaokao:ingest-and-verify", "--input", input.toString(), "--paper-type", "GAOKAO", "--model", "gpt-5.6-luna"));

        IngestionPreflight preflight = new IngestionPreflightService(new IngestionSourceFileDiscoverer()).prepare(arguments);

        assertThat(preflight.files()).hasSize(1);
        assertThat(preflight.progress().discoveredFiles()).isEqualTo(1);
        assertThat(preflight.progress().completedFiles()).isZero();
    }
}
