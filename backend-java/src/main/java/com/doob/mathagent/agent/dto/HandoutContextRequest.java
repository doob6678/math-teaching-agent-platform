package com.doob.mathagent.agent.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import java.util.List;

/** One permission-scoped batch context request from the Python handout graph. */
public record HandoutContextRequest(
        @NotBlank String runId,
        @NotBlank String query,
        List<String> evidenceRefs,
        @Min(1) @Max(20) int limit) {
}
