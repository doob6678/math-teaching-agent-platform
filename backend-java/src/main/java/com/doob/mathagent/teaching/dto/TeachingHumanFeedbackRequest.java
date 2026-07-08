package com.doob.mathagent.teaching.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Request body for human feedback on a recoverable teaching task.
 *
 * @param rating numeric feedback score from 1 to 5
 * @param decision compact decision code, such as helpful, confusing, or needs_revision
 * @param comment teacher/student free-text feedback used for later human review and revision
 * @param reviewContext structured handout review context such as version, PDF renderer, page count, and UI checks
 */
public record TeachingHumanFeedbackRequest(
        @Min(1) @Max(5) int rating,
        @Size(max = 40) String decision,
        @Size(max = 1000) String comment,
        Map<String, Object> reviewContext) {

    private static final int DEFAULT_STRING_LIMIT = 300;
    private static final int IMAGE_DATA_URL_LIMIT = 200_000;

    public TeachingHumanFeedbackRequest(int rating, String decision, String comment) {
        this(rating, decision, comment, Map.of());
    }

    /**
     * Returns a null-safe request with bounded text fields for storage and audit display.
     *
     * @return normalized request
     */
    public TeachingHumanFeedbackRequest normalize() {
        return new TeachingHumanFeedbackRequest(
                Math.max(1, Math.min(5, rating)),
                normalizeText(decision, "needs_review", 40),
                normalizeText(comment, "", 1000),
                normalizeReviewContext(reviewContext));
    }

    /**
     * Strips a text value and limits it to the configured maximum length.
     */
    private static String normalizeText(String value, String defaultValue, int maxLength) {
        String normalized = value == null || value.isBlank() ? defaultValue : value.strip();
        return normalized.length() <= maxLength ? normalized : normalized.substring(0, maxLength);
    }

    private static Map<String, Object> normalizeReviewContext(Map<String, Object> value) {
        if (value == null || value.isEmpty()) {
            return Map.of();
        }
        LinkedHashMap<String, Object> normalized = new LinkedHashMap<>();
        value.entrySet().stream()
                .filter(entry -> entry.getKey() != null && !entry.getKey().isBlank())
                .limit(24)
                .forEach(entry -> {
                    String key = normalizeText(entry.getKey(), "field", 80);
                    normalized.put(key, normalizeValue(key, entry.getValue()));
                });
        return Collections.unmodifiableMap(normalized);
    }

    private static Object normalizeValue(Object value) {
        if (value == null || value instanceof Number || value instanceof Boolean) {
            return value;
        }
        if (value instanceof Iterable<?> iterable) {
            java.util.ArrayList<Object> items = new java.util.ArrayList<>();
            for (Object item : iterable) {
                if (items.size() >= 20) {
                    break;
                }
                items.add(normalizeValue(item));
            }
            return items;
        }
        if (value instanceof Map<?, ?> map) {
            LinkedHashMap<String, Object> nested = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (nested.size() >= 20) {
                    break;
                }
                if (entry.getKey() != null) {
                    String key = normalizeText(String.valueOf(entry.getKey()), "field", 80);
                    nested.put(key, normalizeValue(key, entry.getValue()));
                }
            }
            return nested;
        }
        return normalizeText(String.valueOf(value), "", DEFAULT_STRING_LIMIT);
    }

    private static Object normalizeValue(String key, Object value) {
        if ("previewImageDataUrl".equals(key) || "imageDataUrl".equals(key)) {
            return normalizeText(String.valueOf(value), "", IMAGE_DATA_URL_LIMIT);
        }
        return normalizeValue(value);
    }
}
