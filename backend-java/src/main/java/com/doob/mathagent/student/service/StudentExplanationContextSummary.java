package com.doob.mathagent.student.service;

import java.time.LocalDateTime;

/** Versioned durable summary for the older part of one conversation. */
public record StudentExplanationContextSummary(
        String fromMessageId,
        String toMessageId,
        int version,
        String contentHash,
        String content,
        LocalDateTime updatedAt) {

    public StudentExplanationContextSummary {
        fromMessageId = text(fromMessageId);
        toMessageId = text(toMessageId);
        contentHash = text(contentHash);
        content = text(content);
        version = Math.max(0, version);
    }

    private static String text(String value) {
        return value == null ? "" : value;
    }
}
