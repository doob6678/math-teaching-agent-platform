package com.doob.mathagent.student.service;

import java.util.Optional;

/**
 * Ephemeral cache for a subject-authorized, model-safe conversation history projection.
 *
 * <p>MySQL remains the authoritative source for conversation ownership, UI history, and audit data. Implementations
 * must fail open so a transient cache outage never prevents a student explanation from loading durable history.</p>
 */
public interface StudentExplanationConversationContextCache {

    Optional<StudentExplanationConversationContext> find(
            String tenantId, String subjectType, String subjectId, String conversationId);

    void put(
            String tenantId,
            String subjectType,
            String subjectId,
            String conversationId,
            StudentExplanationConversationContext context);

    void invalidate(String tenantId, String subjectType, String subjectId, String conversationId);
}
