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
}
