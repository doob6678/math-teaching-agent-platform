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
 * <p>New records must carry a stable resource category.  Historical {@code local_path} rows remain readable through
 * the legacy resolver, but no registration path may create another one because a renamed file must never change its
 * retrieval category.</p>
     */
    public static String normalizeForRegistration(String sourceType) {
        String normalized = textOrDefault(sourceType, "teacher_resource").toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "local_docx", "local_path" -> "teacher_resource";
            case "teacher_resource", "gaokao", "mock_exam", "feishu" -> normalized;
            case "qq_bundle" -> throw new IllegalArgumentException(
                    "qq_bundle is a legacy category; register new teacher material as teacher_resource");
            case "textbook", "textbook_md", "textbook_pdf", "public_textbook" ->
                    throw new IllegalArgumentException(
                            "Legacy textbook sourceType is no longer accepted here; use the dedicated textbook corpus instead");
            // Keeping the writable vocabulary closed is what makes a renamed file's category stable.  An unknown
            // token cannot be safely inferred from a title/path, so reject it at the registration boundary.
            default -> throw new IllegalArgumentException(
                    "Unsupported teacher-resource sourceType: " + normalized
                            + "; allowed values are feishu, teacher_resource, gaokao, mock_exam");
        };
    }

    private static String textOrDefault(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value.strip();
    }
}
