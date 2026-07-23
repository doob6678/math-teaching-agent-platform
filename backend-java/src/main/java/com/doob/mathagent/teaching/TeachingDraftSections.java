package com.doob.mathagent.teaching;

import java.util.List;

/**
 * Structured draft sections collected before review, merge, and rendering.
 *
 * @param teacherExplanation teacher-facing explanation draft
 * @param studentWorksheet student-safe worksheet draft
 * @param lectureCards lecture-card outline derived from the teacher draft until a dedicated lecture agent lands
 * @param exercises structured student exercise prompts
 * @param sourceRefs trace-safe evidence references used by the collected draft
 * @param risks known review risks that later reviewer agents should resolve explicitly
 */
public record TeachingDraftSections(
        String teacherExplanation,
        String studentWorksheet,
        List<String> lectureCards,
        List<String> exercises,
        List<String> sourceRefs,
        List<String> risks) {

    public TeachingDraftSections {
        teacherExplanation = teacherExplanation == null ? "" : teacherExplanation;
        studentWorksheet = studentWorksheet == null ? "" : studentWorksheet;
        lectureCards = lectureCards == null ? List.of() : List.copyOf(lectureCards);
        exercises = exercises == null ? List.of() : List.copyOf(exercises);
        sourceRefs = sourceRefs == null ? List.of() : List.copyOf(sourceRefs);
        risks = risks == null ? List.of() : List.copyOf(risks);
    }
}
