package com.doob.mathagent.teacher.document;

import java.util.Locale;

/**
 * Defines the single durable readiness contract for teacher evidence.
 *
 * <p>Sync writes owned local files, parsing creates active blocks, and indexing proves those blocks are available to
 * retrieval. Consumers must require all three stages so stale or partially processed material cannot enter RAG by a
 * secondary path such as question-bank import.</p>
 */
public final class TeacherResourceReadiness {

    private TeacherResourceReadiness() {
    }

    /**
     * Returns whether the document can safely be used as teacher evidence.
     *
     * @param document source document persisted by the owner-scoped sync flow
     * @return true only after sync, parsing, and indexing have completed
     */
    public static boolean isReady(TeacherResourceDocumentResponse document) {
        if (document == null) {
            return false;
        }
        return completedSync(document.syncStatus())
                && completedParse(document.parseStatus())
                && completedIndex(document.indexStatus());
    }

    private static boolean completedSync(String status) {
        String normalized = normalizedStatus(status);
        return "synced".equals(normalized) || "completed".equals(normalized);
    }

    private static boolean completedParse(String status) {
        String normalized = normalizedStatus(status);
        return "parsed".equals(normalized) || "completed".equals(normalized);
    }

    private static boolean completedIndex(String status) {
        String normalized = normalizedStatus(status);
        return "ready".equals(normalized) || "completed".equals(normalized);
    }

    private static String normalizedStatus(String status) {
        return status == null ? "" : status.strip().toLowerCase(Locale.ROOT);
    }
}
