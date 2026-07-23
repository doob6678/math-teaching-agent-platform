package com.doob.mathagent.learning.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

/** Request for a student-owned practice task grounded in one or more diagnosed weak points. */
public record TargetedPracticeRequest(
        @NotBlank String clientRequestId,
        String knowledgePointId,
        @Min(1) int exerciseCount,
        @Min(1) int evidenceLimit) {
}
