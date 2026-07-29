package com.doob.mathagent.ingestion;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Runs deterministic local input validation before a database row or model call is created. This separation makes an
 * empty directory an explicit zero-input result rather than an invented successful parsing run.
 */
public final class IngestionPreflightService {
    private final IngestionSourceFileDiscoverer sourceFileDiscoverer;

    /** Creates the service with the real filesystem discoverer. */
    public IngestionPreflightService(IngestionSourceFileDiscoverer sourceFileDiscoverer) {
        this.sourceFileDiscoverer = Objects.requireNonNull(sourceFileDiscoverer, "sourceFileDiscoverer is required");
    }

    /** Discovers supported documents and initializes all work counters at zero. */
    public IngestionPreflight prepare(IngestionCommandArguments arguments) throws IOException {
        Objects.requireNonNull(arguments, "arguments are required");
        var files = sourceFileDiscoverer.discover(Path.of(arguments.inputRoot()));
        return new IngestionPreflight(
                arguments,
                files,
                new ImportRunProgress(files.size(), 0, 0, 0, 0, 0, 0, 0, 0));
    }
}
