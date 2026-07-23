package com.doob.mathagent.student.vo;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Sidebar list of durable explanation conversations.
 */
public record StudentExplanationConversationListResponse(List<Item> items) {

    /**
     * One clickable conversation shell for the AI explanation sidebar.
     */
    public record Item(
            String conversationId,
            String title,
            String lastQuestionText,
            String viewerRole,
            int totalMessages,
            LocalDateTime createdAt,
            LocalDateTime updatedAt) {
    }
}
