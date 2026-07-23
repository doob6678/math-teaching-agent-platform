package com.doob.mathagent.learning;

import java.time.Instant;

/**
 * Derived mastery snapshot for one student and one knowledge point.
 *
 * @param masteryPercent smoothed accuracy in the inclusive range 0..100
 * @param weaknessLevel 0 when healthy, otherwise 1..5
 */
public record StudentKnowledgeMastery(
        String tenantId,
        String studentId,
        String knowledgePointId,
        int masteryPercent,
        int attemptCount,
        int correctCount,
        int incorrectCount,
        int weaknessLevel,
        Instant lastAttemptAt,
        String evidenceSummary) {
}
