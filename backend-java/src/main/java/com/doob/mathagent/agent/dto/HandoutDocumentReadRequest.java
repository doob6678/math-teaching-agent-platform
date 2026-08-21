package com.doob.mathagent.agent.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/** Bounded full-document inspection request using a run-scoped opaque document reference. */
public record HandoutDocumentReadRequest(
        @NotBlank String runId,
        @NotBlank String documentRef,
        @NotNull Integer maxBlocks,
        @NotNull Integer maxChars) {
}
