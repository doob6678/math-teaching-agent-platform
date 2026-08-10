package com.doob.mathagent.student.service;

import java.time.LocalDateTime;

/** Minimal model-visible projection of one persisted explanation turn. */
public record StudentExplanationConversationContextMessage(
        String explanationId,
        String questionText,
        String answerText,
        LocalDateTime createdAt) {
}
