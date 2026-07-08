package com.doob.mathagent.teaching.service;

import com.doob.mathagent.teaching.vo.TeachingHandoutTemplateResponse;

/**
 * Backend-owned handout template profile used to steer prompting and LaTeX rendering.
 *
 * @param summary frontend-visible template metadata
 * @param promptInstructions prompt-side style constraints injected into the teaching draft stage
 * @param studentLectureStyle whether the student handout should prefer lecture-style section rendering
 */
public record TeachingHandoutTemplateProfile(
        TeachingHandoutTemplateResponse summary,
        String promptInstructions,
        boolean studentLectureStyle) {

    /**
     * Template-controlled exercise blank height. Keep this bounded so bad local skill config cannot create unusable PDFs.
     */
    public int blankSpaceEm() {
        return bounded(summary == null ? null : summary.blankSpaceEm(), 5, 12, 6);
    }

    /**
     * Template-controlled gap between continuous questions. This is intentionally separate from answer workspace height.
     */
    public int questionGapEm() {
        return bounded(summary == null ? null : summary.questionGapEm(), 2, 8, 4);
    }

    private static int bounded(Integer value, int min, int max, int fallback) {
        if (value == null) {
            return fallback;
        }
        return Math.max(min, Math.min(max, value));
    }
}
