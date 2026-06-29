package com.doob.mathagent.teaching.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

/**
 * Request body for human feedback on a recoverable teaching task.
 *
 * @param rating numeric feedback score from 1 to 5
 * @param decision compact decision code, such as helpful, confusing, or needs_revision
 * @param comment teacher/student free-text feedback used for later human review and revision
 */
public record TeachingHumanFeedbackRequest(
        @Min(1) @Max(5) int rating,
        @Size(max = 40) String decision,
        @Size(max = 1000) String comment) {

    /**
     * Returns a null-safe request with bounded text fields for storage and audit display.
     *
     * @return normalized request
     */
    public TeachingHumanFeedbackRequest normalize() {
        return new TeachingHumanFeedbackRequest(
                Math.max(1, Math.min(5, rating)),
                normalizeText(decision, "needs_review", 40),
                normalizeText(comment, "", 1000));
    }

    /**
     * Strips a text value and limits it to the configured maximum length.
     */
    private static String normalizeText(String value, String defaultValue, int maxLength) {
        String normalized = value == null || value.isBlank() ? defaultValue : value.strip();
        return normalized.length() <= maxLength ? normalized : normalized.substring(0, maxLength);
    }
}
