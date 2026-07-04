package com.doob.mathagent.student.vo;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Recent explanation history visible to the current backend subject.
 */
public record StudentExplanationHistoryResponse(List<Item> items) {

    /**
     * Compact history item for context recovery and frontend display.
     */
    public record Item(
            String explanationId,
            String conversationId,
            String questionText,
            String imageStatus,
            String imageProblemText,
            String aiProviderName,
            String aiModelCode,
            int totalTokens,
            long totalElapsedMs,
            LocalDateTime createdAt) {
    }
}
