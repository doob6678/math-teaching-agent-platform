package com.doob.mathagent.learning.vo;

import com.doob.mathagent.teaching.TeachingTaskStatus;
import java.util.List;

/** Student-safe projection of a practice task; teacher explanations and answer keys are deliberately excluded. */
public record StudentPracticeTaskResponse(
        String taskId,
        String clientRequestId,
        TeachingTaskStatus status,
        String studentId,
        List<String> knowledgePointIds,
        String questionText,
        String learningGoal,
        String studentHandoutLatex,
        List<String> interactiveSuggestions,
        String errorMessage) {
}
