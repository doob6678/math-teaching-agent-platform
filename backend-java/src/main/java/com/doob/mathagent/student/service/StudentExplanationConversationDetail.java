package com.doob.mathagent.student.service;

import com.doob.mathagent.student.vo.StudentExplanationResponse;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Full durable conversation payload used when the frontend opens one historical thread.
 */
public record StudentExplanationConversationDetail(
        String conversationId,
        String tenantId,
        String subjectType,
        String subjectId,
        String studentId,
        String viewerRole,
        String title,
        int totalMessages,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        List<Message> messages) {

    /**
     * One persisted question/answer turn inside the conversation.
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
