package com.doob.mathagent.teacher.support;

import com.doob.mathagent.teacher.document.TeacherResourceDocumentResponse;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Resolves the logical teacher-resource library for one source document.
 *
 * <p>Many existing rows were ingested as {@code local_path}, even when the folder is clearly a QQ bundle, Feishu
 * method export, gaokao paper, or mock exam. Retrieval filters must therefore infer a stable logical library from
 * persisted document metadata. Keep this resolver source-oriented: it must classify libraries from title/path/type
 * metadata, not from benchmark query wording or one-off test phrases.</p>
 */
public final class TeacherResourceLibraryResolver {

    private static final Set<String> EXPLICIT_LIBRARY_TYPES = Set.of(
            "feishu",
            "qq_bundle",
            "gaokao",
            "mock_exam",
            "public_textbook",
            "textbook",
            "teacher_resource",
            "system_reference");

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
        /*
         * New registrations persist a canonical category at creation time.  Do not inspect a title, path, or URL for
         * those records: a user rename must be incapable of moving a source between Feishu, gaokao, and mock_exam.
         * Only old blank/local_path rows retain heuristic compatibility until their next real synchronization.
         */
        if (!normalizedSourceType.isBlank() && !"local_path".equals(normalizedSourceType)) {
            return normalizedSourceType;
        }
        String haystack = metadataHaystack(document);
        /*
         * Live runtime-authored eval documents may be created under output/benchmarks/... during real ingestion. Those
         * files are still genuine teacher-owned resources if the title/path says runtime teacher pack rather than a
         * seeded benchmark corpus. Do not collapse them into system_reference or library-scoped retrieval will never
         * see the teacher's own pack in stage one.
         */
        if (containsAny(
                haystack,
                "runtime-teacher-resource-pack",
                "/runtime-authored/teacher-resource-pack",
                "\\runtime-authored\\teacher-resource-pack")) {
            return "teacher_resource";
        }
        /*
         * Runtime-authored live-eval corpora are stored under output/benchmarks/... but they still represent the real
         * logical source library chosen during ingestion. Keep specialized corpus inference ahead of the generic
         * benchmark/system fallback so QQ/Feishu/gaokao/mock packages remain searchable when AI passes `library`.
         */
        /*
         * Logical library keys use underscores, while older runtime packs used hyphens. Accept both title forms so
         * ingestion naming cannot make a parsed and indexed QQ resource invisible during the candidate filter stage.
         */
        if (containsAny(
                haystack,
                "runtime-qq-bundle",
                "runtime-qq_bundle",
                "/runtime-authored/02-qq-bundle",
                "\\runtime-authored\\02-qq-bundle")) {
            return "qq_bundle";
        }
        if (containsAny(haystack, "runtime-feishu-method", "/runtime-authored/03-feishu", "\\runtime-authored\\03-feishu")) {
            return "feishu";
        }
        if (containsAny(haystack, "runtime-gaokao", "/runtime-authored/04-gaokao", "\\runtime-authored\\04-gaokao")) {
            return "gaokao";
        }
        if (containsAny(haystack, "runtime-mock", "/runtime-authored/05-mock", "\\runtime-authored\\05-mock")) {
            return "mock_exam";
        }
        if (containsAny(
                haystack,
                "design-system",
                "development-knowledge",
                "knowledge-graph-spine",
                "synthetic-natural-math-benchmark",
                "benchmark-high-school-math",
                "/output/benchmarks/",
                "/benchmark-math-resources")) {
            return "system_reference";
        }
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
        return "teacher_resource";
    }

    /**
     * Returns all selectors that should match this document.
     *
     * <p>The raw {@code source_type} remains selectable for legacy callers, so {@code sourceType=local_path} can still
     * inspect old local-folder rows. The broad {@code teacher_resource} selector is narrower by design: it only matches
     * generic teacher-owned material after specialized libraries have been inferred. Otherwise an AI request for one
     * library would still pull in QQ, Feishu, gaokao, and mock-exam local folders and stage-one recall would be noisy.</p>
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
        } else if ("teacher_resource".equals(effectiveLibrary)) {
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
            String normalizedNeedle = normalize(needle);
            if (!normalizedNeedle.isBlank() && haystack.contains(normalizedNeedle)) {
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

