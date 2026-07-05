package com.doob.mathagent.teaching.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 教学任务提交请求。
 *
 * @param clientRequestId 前端生成的幂等请求号；同一用户重复提交时用于恢复已有任务，避免失败后从头开始。
 * @param questionText 用户输入的题目或学习问题。
 * @param learningGoal 用户想学什么，例如“理解函数新定义题”。
 * @param evidenceLimit 教材证据召回上限。
 * @param handoutTemplateCode optional backend-owned handout template code selected by the frontend
 */
public record TeachingTaskRequest(
        @NotBlank String clientRequestId,
        @Size(max = 4000) String questionText,
        @NotBlank String learningGoal,
        @Min(1) @Max(10) int evidenceLimit,
        @Size(max = 128) String handoutTemplateCode) {

    /**
     * Backward-compatible constructor for callers that do not select a template explicitly.
     */
    public TeachingTaskRequest(
            String clientRequestId,
            String questionText,
            String learningGoal,
            int evidenceLimit) {
        this(clientRequestId, questionText, learningGoal, evidenceLimit, null);
    }

    /**
     * Normalizes a handout request. A handout can be created from only a teaching goal/topic; in that case the backend
     * uses the goal as the searchable question text instead of forcing the frontend to duplicate fields.
     */
    public TeachingTaskRequest normalize() {
        String normalizedGoal = requireText(learningGoal, "learningGoal is required");
        String normalizedQuestion = questionText == null || questionText.isBlank()
                ? normalizedGoal
                : questionText.strip();
        return new TeachingTaskRequest(
                requireText(clientRequestId, "clientRequestId is required"),
                normalizedQuestion,
                normalizedGoal,
                evidenceLimit,
                handoutTemplateCode == null || handoutTemplateCode.isBlank() ? null : handoutTemplateCode.strip());
    }

    private static String requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value.strip();
    }
}
