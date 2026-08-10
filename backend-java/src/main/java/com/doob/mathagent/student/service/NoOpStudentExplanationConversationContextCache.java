package com.doob.mathagent.student.service;

import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Fallback used when the Redis context cache is disabled in a local test deployment. */
@Component
@ConditionalOnProperty(
        prefix = "math-agent.redis.student-explanation-context-cache",
        name = "enabled",
        havingValue = "false",
        matchIfMissing = true)
public class NoOpStudentExplanationConversationContextCache implements StudentExplanationConversationContextCache {

    @Override
    public Optional<StudentExplanationConversationContext> find(
            String tenantId, String subjectType, String subjectId, String conversationId) {
        return Optional.empty();
    }

    @Override
    public void put(
            String tenantId,
            String subjectType,
            String subjectId,
            String conversationId,
            StudentExplanationConversationContext context) {
        // The durable store remains available when Redis caching is intentionally disabled.
    }

    @Override
    public void invalidate(String tenantId, String subjectType, String subjectId, String conversationId) {
        // The durable store remains available when Redis caching is intentionally disabled.
    }
}
