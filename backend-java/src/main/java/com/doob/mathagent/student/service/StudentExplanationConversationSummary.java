package com.doob.mathagent.student.service;

import java.time.LocalDateTime;

/**
 * Sidebar-safe durable conversation summary for the AI explanation workspace.
 */
public record StudentExplanationConversationSummary(
        String conversationId,
        String tenantId,
        String subjectType,
        String subjectId,
        String studentId,
        String viewerRole,
        String title,
        String lastQuestionText,
        int totalMessages,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {
}
