package com.doob.mathagent.ingestion;

import java.util.List;

/**
 * Immutable result of the command's no-model preflight. It exposes the discovered inputs and initial counters so the
 * durable run creator can store exactly what it inspected before any page rendering or remote request begins.
 */
public record IngestionPreflight(
        IngestionCommandArguments arguments,
        List<DiscoveredSourceFile> files,
        ImportRunProgress progress) {
    public IngestionPreflight {
        files = List.copyOf(files);
    }
}
