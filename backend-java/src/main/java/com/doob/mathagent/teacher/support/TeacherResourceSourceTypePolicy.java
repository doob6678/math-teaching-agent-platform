package com.doob.mathagent.teacher.support;

import java.util.Locale;

/**
 * Centralizes which teacher-resource source types are still valid after textbook retrieval moved to
 * {@code processed_books}.
 *
 * <p>Do not reopen legacy textbook bridge source types here. The old {@code textbook}/{@code textbook_md}/
 * {@code textbook_pdf} registrations created duplicate teacher-store rows for corpus that now has its own dedicated
 * textbook retriever. Keeping those values writable would silently reintroduce the stale branch we just removed.</p>
 */
public final class TeacherResourceSourceTypePolicy {

    private TeacherResourceSourceTypePolicy() {
    }

    /**
     * Normalizes one requested teacher-resource source type.
     *
     * <p>{@code local_docx} is folded into {@code local_path} because browser uploads and server-side local ingestion
     * now share the same recursive parser pipeline. Legacy textbook bridge types are rejected instead of remapped so
     * operators must use the real textbook pipeline rather than unknowingly recreating duplicate textbook rows.</p>
     */
    public static String normalizeForRegistration(String sourceType) {
        String normalized = textOrDefault(sourceType, "local_path").toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "local_docx" -> "local_path";
            case "teacher_resource", "qq_bundle", "gaokao", "mock_exam", "local_path", "feishu" -> normalized;
            case "textbook", "textbook_md", "textbook_pdf", "public_textbook" ->
                    throw new IllegalArgumentException(
                            "Legacy textbook sourceType is no longer accepted here; use the dedicated textbook corpus instead");
            default -> normalized;
        };
    }

    private static String textOrDefault(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value.strip();
    }
}
