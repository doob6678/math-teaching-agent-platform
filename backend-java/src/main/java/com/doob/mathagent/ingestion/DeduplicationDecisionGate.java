package com.doob.mathagent.ingestion;

/**
 * Converts candidate classification into a safe merge action. Embedding or model output can nominate a pair only;
 * parameters, conditions, figures, options, and requested target are deterministic conflict inputs that veto merging.
 */
public final class DeduplicationDecisionGate {
    private DeduplicationDecisionGate() { }

    /** Applies the plan's narrow automatic-merge rule. */
    public static DeduplicationDecision evaluate(QuestionRelationship relationship, boolean deterministicConflict) {
        if (relationship == null) {
            return new DeduplicationDecision(DeduplicationAction.REQUIRE_REVIEW, "Candidate relationship is missing");
        }
        if (relationship == QuestionRelationship.SAME_QUESTION && !deterministicConflict) {
            return new DeduplicationDecision(DeduplicationAction.AUTO_MERGE, "Same question with no deterministic conflict");
        }
        if (relationship == QuestionRelationship.RELATED_BUT_DISTINCT) {
            return new DeduplicationDecision(DeduplicationAction.KEEP_SEPARATE, "Related questions remain distinct");
        }
        return new DeduplicationDecision(
                DeduplicationAction.REQUIRE_REVIEW,
                deterministicConflict ? "Deterministic fields conflict" : "Variant or undecidable candidate");
    }
}
