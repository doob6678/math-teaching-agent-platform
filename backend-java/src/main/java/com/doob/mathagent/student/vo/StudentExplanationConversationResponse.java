package com.doob.mathagent.student.vo;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Full durable conversation thread used when the frontend opens one historical explanation chat.
 */
public record StudentExplanationConversationResponse(
        String conversationId,
        String title,
        String viewerRole,
        int totalMessages,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        List<Message> messages) {

    /**
     * One persisted question/answer turn.
     */
    public record Message(
            String explanationId,
            String questionText,
            String imageStatus,
            String imageProblemText,
            String imageFileName,
            LocalDateTime createdAt,
            StudentExplanationResponse response) {
    }
}
