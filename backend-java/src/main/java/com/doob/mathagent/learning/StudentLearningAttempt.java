package com.doob.mathagent.learning;

import java.time.Instant;
import java.util.List;

/**
 * Immutable fact describing one real student answer.
 *
 * <p>The attempt is kept separate from the derived mastery row so that scoring rules can be changed and
 * recomputed without losing the original answer evidence.</p>
 */
public record StudentLearningAttempt(
        String attemptId,
        String tenantId,
        String studentId,
        String questionId,
        String questionText,
        List<String> knowledgePointIds,
        boolean correct,
        long responseTimeMs,
        Instant submittedAt) {
}
