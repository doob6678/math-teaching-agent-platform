package com.doob.mathagent.teaching;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Normalizes draft sections collected from the current teaching workflow before reviewer or merge agents run.
 */
public final class TeachingDraftSectionCollector {

    private TeachingDraftSectionCollector() {
    }

    /**
     * Collects one stable draft-section snapshot while deduplicating list fields and dropping blanks.
     */
    public static TeachingDraftSections collect(
            String teacherExplanation,
            String studentWorksheet,
            List<String> lectureCards,
            List<String> exercises,
            List<String> sourceRefs,
            List<String> risks) {
        return new TeachingDraftSections(
                safeText(teacherExplanation),
                safeText(studentWorksheet),
                distinctNonBlank(lectureCards),
                distinctNonBlank(exercises),
                distinctNonBlank(sourceRefs),
                distinctNonBlank(risks));
    }

    private static String safeText(String value) {
        return value == null ? "" : value.strip();
    }

    private static List<String> distinctNonBlank(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        for (String value : values) {
            if (value == null) {
                continue;
            }
            String normalized = value.strip();
            if (!normalized.isBlank()) {
                unique.add(normalized);
            }
        }
        return List.copyOf(new ArrayList<>(unique));
    }
}
