package com.doob.mathagent.ingestion;

/**
 * Pixel-space question boundary on one rendered source page. Coordinates remain numerical source evidence rather
 * than an LLM-generated description, so a later review can reconstruct exactly which visual region was processed.
 */
public record QuestionRegion(int x1, int y1, int x2, int y2) {
    /** Rejects empty or inverted rectangles before they can become a durable idempotency identity. */
    public QuestionRegion {
        if (x1 < 0 || y1 < 0 || x2 <= x1 || y2 <= y1) {
            throw new IllegalArgumentException("question region must be a non-empty non-negative rectangle");
        }
    }

    /** Provides a stable machine representation without locale-sensitive formatting. */
    String canonicalForm() {
        return x1 + "," + y1 + "," + x2 + "," + y2;
    }
}
