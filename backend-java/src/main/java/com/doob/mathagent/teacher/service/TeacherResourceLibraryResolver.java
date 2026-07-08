package com.doob.mathagent.teacher.service;

import com.doob.mathagent.teacher.vo.TeacherResourceDocumentResponse;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Resolves the logical teacher-resource library for one source document.
 *
 * <p>The same ingestion pipeline serves real Feishu downloads, local QQ bundles, gaokao folders, and runtime-authored
 * evaluation packages. Older rows were often stored as {@code local_path}, which is a transport detail rather than the
 * library that teachers or AI tools care about. Library-aware retrieval must therefore infer a stable logical library
 * from persisted metadata instead of trusting {@code source_type} literally, or "specify library" silently stops
 * working for perfectly valid existing data.</p>
 */
public final class TeacherResourceLibraryResolver {

    private static final Set<String> EXPLICIT_LIBRARY_TYPES = Set.of(
            "feishu",
            "qq_bundle",
            "gaokao",
            "mock_exam",
            "public_textbook",
            "textbook");

    private TeacherResourceLibraryResolver() {
    }

    /**
     * Returns the best logical library key for retrieval output and filter matching.
     */
    public static String effectiveLibrary(TeacherResourceDocumentResponse document) {
        if (document == null) {
            return "";
        }
        String normalizedSourceType = normalize(text(document.sourceType()));
        if (EXPLICIT_LIBRARY_TYPES.contains(normalizedSourceType)) {
            return "textbook".equals(normalizedSourceType) ? "public_textbook" : normalizedSourceType;
        }
        if ("PUBLIC_TEXTBOOK".equalsIgnoreCase(text(document.permissionScope()))) {
            return "public_textbook";
        }
        String haystack = metadataHaystack(document);
        if (containsAny(haystack, "qq_bundle", "qq-bundle", "qq bundle", "专题", "答案解析", "点评")) {
            return "qq_bundle";
        }
        if (containsAny(haystack, "feishu", "飞书", "lark")) {
            return "feishu";
        }
        if (containsAny(haystack, "gaokao", "高考", "真题")) {
            return "gaokao";
        }
        if (containsAny(haystack, "mock_exam", "mock-exam", "mock exam", "mock", "模拟")) {
            return "mock_exam";
        }
        if (containsAny(haystack, "textbook", "教材", "chapter")) {
            return "public_textbook";
        }
        return normalizedSourceType;
    }

    /**
     * Returns all selectors that should match this document.
     *
     * <p>We keep both the raw {@code source_type} and the inferred logical library so old {@code local_path} rows stay
     * reachable during migration while newer explicitly typed rows continue to work.</p>
     */
    public static Set<String> selectors(TeacherResourceDocumentResponse document) {
        LinkedHashSet<String> selectors = new LinkedHashSet<>();
        String rawSourceType = normalize(text(document == null ? null : document.sourceType()));
        if (!rawSourceType.isBlank()) {
            selectors.add(rawSourceType);
        }
        String effectiveLibrary = effectiveLibrary(document);
        if (!effectiveLibrary.isBlank()) {
            selectors.add(effectiveLibrary);
        }
        if ("PUBLIC_TEXTBOOK".equalsIgnoreCase(text(document == null ? null : document.permissionScope()))) {
            selectors.add("textbook");
            selectors.add("public_textbook");
        } else if (!effectiveLibrary.isBlank()) {
            selectors.add("teacher_resource");
        }
        return selectors;
    }

    /**
     * Returns whether any requested selector matches this document after logical-library inference.
     */
    public static boolean matchesAny(TeacherResourceDocumentResponse document, List<String> requestedSelectors) {
        if (requestedSelectors == null || requestedSelectors.isEmpty()) {
            return true;
        }
        Set<String> documentSelectors = selectors(document);
        return requestedSelectors.stream()
                .map(TeacherResourceLibraryResolver::normalize)
                .filter(value -> !value.isBlank())
                .anyMatch(documentSelectors::contains);
    }

    private static String metadataHaystack(TeacherResourceDocumentResponse document) {
        return normalize(String.join(
                " ",
                text(document == null ? null : document.sourceType()),
                text(document == null ? null : document.title()),
                text(document == null ? null : document.originalUrl()),
                text(document == null ? null : document.localPath()),
                text(document == null ? null : document.permissionScope())));
    }

    private static boolean containsAny(String haystack, String... needles) {
        for (String needle : needles) {
            if (!normalize(needle).isBlank() && haystack.contains(normalize(needle))) {
                return true;
            }
        }
        return false;
    }

    private static String normalize(String value) {
        return text(value).toLowerCase(Locale.ROOT).replace('\\', '/').replaceAll("\\s+", " ").strip();
    }

    private static String text(String value) {
        return value == null ? "" : value.strip();
    }
}
