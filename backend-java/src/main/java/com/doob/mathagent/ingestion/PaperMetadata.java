package com.doob.mathagent.ingestion;

/**
 * Structured source fields. Internal run and path values are retained only for audit and are never display data.
 */
public record PaperMetadata(
        Integer year,
        String paperName,
        String region,
        String institution,
        String questionNumber,
        String internalRunId,
        String internalSourcePath) {
}
