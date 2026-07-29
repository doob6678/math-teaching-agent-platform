package com.doob.mathagent.ingestion;

/** Result of applying deterministic safeguards to a candidate-pair relationship. */
public enum DeduplicationAction {
    AUTO_MERGE,
    REQUIRE_REVIEW,
    KEEP_SEPARATE
}
