package com.doob.mathagent.ingestion;

/**
 * Structured, non-negative run counters emitted by the CLI/status surface and saved to evidence. The fields mirror
 * the plan's observable work units, so a dashboard cannot claim an ETA from a hidden or incompatible counter.
 */
public record ImportRunProgress(
        int discoveredFiles,
        int parsedFiles,
        int failedFiles,
        int extractedQuestions,
        int pendingReviewQuestions,
        int pairedQuestions,
        int deduplicatedQuestions,
        long totalTokens,
        long elapsedMilliseconds) {
    private static final int PERCENT_SCALE = 100;

    /** Validates that the displayed file outcome counters describe the discovered input set. */
    public ImportRunProgress {
        if (discoveredFiles < 0 || parsedFiles < 0 || failedFiles < 0 || extractedQuestions < 0
                || pendingReviewQuestions < 0 || pairedQuestions < 0 || deduplicatedQuestions < 0
                || totalTokens < 0 || elapsedMilliseconds < 0) {
            throw new IllegalArgumentException("progress counters must be non-negative");
        }
        if (parsedFiles + failedFiles > discoveredFiles) {
            throw new IllegalArgumentException("file counters cannot exceed discovered files");
        }
    }

    /** Number of files with a terminal outcome; uncounted files are still resumable work. */
    public int completedFiles() {
        return parsedFiles + failedFiles;
    }

    /** Integer status percentage, defined as zero when no supported source file exists. */
    public int completionPercent() {
        return discoveredFiles == 0 ? 0 : completedFiles() * PERCENT_SCALE / discoveredFiles;
    }
}
