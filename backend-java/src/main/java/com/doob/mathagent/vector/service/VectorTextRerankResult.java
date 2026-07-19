package com.doob.mathagent.vector.service;

import java.util.List;

/**
 * Scores and the real mechanism that produced them.
 * The strategy is part of internal execution evidence so callers cannot label
 * embedding cosine fallback as a successful cross-encoder rerank.
 */
public record VectorTextRerankResult(List<Double> scores, String strategy) {

    public static final String CROSS_ENCODER = "cross_encoder";
    public static final String EMBEDDING_FALLBACK = "embedding_fallback";

    public VectorTextRerankResult {
        scores = scores == null ? List.of() : List.copyOf(scores);
        strategy = strategy == null ? "" : strategy.strip();
    }
}
