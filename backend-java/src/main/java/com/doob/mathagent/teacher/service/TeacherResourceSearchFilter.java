package com.doob.mathagent.teacher.service;

import java.util.List;

/**
 * Optional metadata filters for teacher-resource RAG.
 *
 * <p>The filter is intentionally generic: callers may route by resource scope, concrete document ids, or
 * user/AI-provided topic tags. It must not contain benchmark-specific keywords or test-set assumptions.</p>
 *
 * @param permissionScopes allowed resource scopes, such as TEACHER_PRIVATE or MATH_VIP
 * @param documentIds concrete source document ids to search
 * @param sourceTypes normalized source-library selectors such as feishu, qq_bundle, gaokao, or mock_exam
 * @param tags loose topic labels matched against document title, chapter, section, and block text
 */
public record TeacherResourceSearchFilter(
        List<String> permissionScopes,
        List<String> documentIds,
        List<String> sourceTypes,
        List<String> tags) {

    public static final TeacherResourceSearchFilter EMPTY =
            new TeacherResourceSearchFilter(List.of(), List.of(), List.of(), List.of());

    /**
     * Builds a normalized immutable filter from request parameters.
     */
    public static TeacherResourceSearchFilter of(
            List<String> permissionScopes,
            List<String> documentIds,
            List<String> sourceTypes,
            List<String> tags) {
        return new TeacherResourceSearchFilter(
                normalizeList(permissionScopes, true),
                normalizeList(documentIds, false),
                normalizeList(sourceTypes, false, true),
                normalizeList(tags, false));
    }

    /**
     * Returns whether no filter dimension is active.
     */
    public boolean empty() {
        return permissionScopes.isEmpty()
                && documentIds.isEmpty()
                && sourceTypes.isEmpty()
                && tags.isEmpty();
    }

    private static List<String> normalizeList(List<String> values, boolean upperCase) {
        return normalizeList(values, upperCase, false);
    }

    private static List<String> normalizeList(List<String> values, boolean upperCase, boolean lowerCase) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        return values.stream()
                .filter(value -> value != null && !value.isBlank())
                .flatMap(value -> java.util.Arrays.stream(value.split(",")))
                .map(String::strip)
                .filter(value -> !value.isBlank())
                .map(value -> upperCase
                        ? value.toUpperCase(java.util.Locale.ROOT)
                        : lowerCase ? value.toLowerCase(java.util.Locale.ROOT) : value)
                .distinct()
                .toList();
    }
}
