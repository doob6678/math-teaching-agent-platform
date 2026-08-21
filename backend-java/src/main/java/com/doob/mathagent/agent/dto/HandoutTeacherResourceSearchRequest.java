package com.doob.mathagent.agent.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

/** Python Writer's bounded, run-scoped request for optional teacher-resource evidence. */
public record HandoutTeacherResourceSearchRequest(
        @NotBlank String runId,
        @NotBlank String query,
        @Min(1) @Max(6) int limit) {
}
