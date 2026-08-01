package com.doob.mathagent.student.service;

import java.time.LocalDateTime;

/**
 * Compact history row used for context prompts and frontend history lists.
 */
public record StudentExplanationHistorySummary(
        String explanationId,
        String conversationId,
        String title,
        String tenantId,
        String subjectType,
        String subjectId,
        String studentId,
        String viewerRole,
        String questionText,
        String imageStatus,
        String imageProblemText,
        String cardsJson,
        String aiProviderName,
        String aiModelCode,
        int totalTokens,
        long totalElapsedMs,
        LocalDateTime createdAt) {

    /** Compatibility constructor for legacy callers that only have compact history fields. */
    public StudentExplanationHistorySummary(
            String explanationId, String conversationId, String title, String tenantId, String subjectType,
            String subjectId, String studentId, String viewerRole, String questionText, String imageStatus,
            String imageProblemText, String aiProviderName, String aiModelCode, int totalTokens,
            long totalElapsedMs, LocalDateTime createdAt) {
        this(explanationId, conversationId, title, tenantId, subjectType, subjectId, studentId, viewerRole,
                questionText, imageStatus, imageProblemText, "", aiProviderName, aiModelCode, totalTokens,
                totalElapsedMs, createdAt);
    }
}
