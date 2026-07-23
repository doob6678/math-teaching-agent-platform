package com.doob.mathagent.teaching;

/**
 * Collected LaTeX handout versions produced from one shared teaching draft.
 *
 * @param teacherHandoutLatex teacher-only handout with answers and review notes
 * @param studentHandoutLatex student-safe worksheet without answers
 * @param lectureHandoutLatex lecture-card handout derived from the teacher version
 */
public record TeachingHandoutVersions(
        String teacherHandoutLatex,
        String studentHandoutLatex,
        String lectureHandoutLatex) {

    public TeachingHandoutVersions {
        teacherHandoutLatex = teacherHandoutLatex == null ? "" : teacherHandoutLatex;
        studentHandoutLatex = studentHandoutLatex == null ? "" : studentHandoutLatex;
        lectureHandoutLatex = lectureHandoutLatex == null ? "" : lectureHandoutLatex;
    }
}
