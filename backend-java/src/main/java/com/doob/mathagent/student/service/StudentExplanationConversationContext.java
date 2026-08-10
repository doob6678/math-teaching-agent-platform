package com.doob.mathagent.student.service;

import java.util.List;

/** Redis-safe projection of one durable conversation context. */
public record StudentExplanationConversationContext(
        List<StudentExplanationConversationContextMessage> messages,
        StudentExplanationContextSummary summary) {

    public StudentExplanationConversationContext {
        messages = List.copyOf(messages == null ? List.of() : messages);
    }
}
