package com.doob.mathagent.ingestion;

/** Immutable behavior differences owned by a paper type instead of duplicated import pipelines. */
public record PaperTypePolicy(
        boolean officialAnswerPreferred,
        boolean automaticCrossSourcePairing,
        boolean allowsParentQuestion,
        boolean conservativeDeduplication) {
}
