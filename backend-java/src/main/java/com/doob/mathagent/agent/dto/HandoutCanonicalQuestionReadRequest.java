package com.doob.mathagent.agent.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/** Bounded canonical-question inspection using only a run-scoped opaque document reference. */
public record HandoutCanonicalQuestionReadRequest(
        @NotBlank String runId,
        @NotBlank String documentRef,
        @NotNull Integer maxBlocks,
        @NotNull Integer maxChars) {
}
