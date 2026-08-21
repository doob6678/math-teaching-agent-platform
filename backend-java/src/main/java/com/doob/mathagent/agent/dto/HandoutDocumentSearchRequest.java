package com.doob.mathagent.agent.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/** Keyword lookup scoped to one run-authorized source document; it accepts neither paths nor global search scope. */
public record HandoutDocumentSearchRequest(
        @NotBlank String runId,
        @NotBlank String documentRef,
        @NotBlank String keyword,
        @NotNull Integer maxBlocks,
        @NotNull Integer maxChars) {
}
