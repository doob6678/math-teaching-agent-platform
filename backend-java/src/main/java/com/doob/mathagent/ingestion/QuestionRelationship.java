package com.doob.mathagent.ingestion;

/** The only relationship labels a model or reviewer may return for a candidate pair. */
public enum QuestionRelationship {
    SAME_QUESTION,
    SAME_STEM_DIFFERENT_VARIANT,
    RELATED_BUT_DISTINCT,
    UNDECIDABLE
}
