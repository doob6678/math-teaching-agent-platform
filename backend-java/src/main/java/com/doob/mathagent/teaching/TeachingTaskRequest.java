package com.doob.mathagent.teaching;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

/**
 * 教学任务提交请求。
 *
 * @param clientRequestId 前端生成的幂等请求号；同一用户重复提交时用于恢复已有任务，避免失败后从头开始。
 * @param questionText 用户输入的题目或学习问题。
 * @param learningGoal 用户想学什么，例如“理解函数新定义题”。
 * @param evidenceLimit 教材证据召回上限。
 */
public record TeachingTaskRequest(
        @NotBlank String clientRequestId,
        @NotBlank String questionText,
        @NotBlank String learningGoal,
        @Min(1) @Max(10) int evidenceLimit) {
}
