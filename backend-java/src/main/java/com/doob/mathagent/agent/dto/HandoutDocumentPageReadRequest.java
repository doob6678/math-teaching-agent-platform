package com.doob.mathagent.agent.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/** Reads a small page window from one run-authorized textbook source without exposing corpus paths. */
public record HandoutDocumentPageReadRequest(
        @NotBlank String runId,
        @NotBlank String documentRef,
        @NotNull @Min(1) Integer pageNo,
        @NotNull @Min(0) @Max(4) Integer pageRadius,
        @NotNull Integer maxBlocks,
        @NotNull Integer maxChars) {
}
