package com.doob.mathagent.memory.service;

import java.time.Instant;

/**
 * Domain memory entry used by student memory stores.
 *
 * @param memoryId memory id
 * @param tenantId tenant id
 * @param studentId owner student id
 * @param memoryScope private or public
 * @param knowledgePointName knowledge point label
 * @param questionText canonical question text
 * @param answerText reusable answer text
 * @param status active, stale, archived, or blocked
 * @param createdAt creation instant
 */
public record StudentMemoryEntry(
        String memoryId,
        String tenantId,
        String studentId,
        String memoryScope,
        String knowledgePointName,
        String questionText,
        String answerText,
        String status,
        Instant createdAt) {
}
