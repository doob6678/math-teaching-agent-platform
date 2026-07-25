package com.doob.mathagent.teaching.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Explicit publication decision for a quality-gated handout awaiting human review. */
public record TeachingReviewDecisionRequest(
        @NotBlank String decision,
        @Size(max = 1000) String comment) {
    public String normalizedDecision() {
        return decision == null ? "" : decision.strip().toUpperCase(java.util.Locale.ROOT);
    }

    public String normalizedComment() {
        return comment == null ? "" : comment.strip();
    }
}
