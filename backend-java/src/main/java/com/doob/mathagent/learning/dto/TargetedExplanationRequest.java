package com.doob.mathagent.learning.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Request for a student explanation that is anchored to the student's diagnosed weak point. */
public record TargetedExplanationRequest(
        @NotBlank @Size(max = 4000) String questionText,
        @Size(max = 128) String knowledgePointId,
        @Size(max = 128) String questionId) {
}
