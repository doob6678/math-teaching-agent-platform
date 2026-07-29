package com.doob.mathagent.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Guards candidate recall from becoming an unsafe automatic merge decision. */
class DeduplicationDecisionGateTest {

    @Test
    void autoMergesOnlySameQuestionWithoutDeterministicConflict() {
        assertThat(DeduplicationDecisionGate.evaluate(QuestionRelationship.SAME_QUESTION, false).action())
                .isEqualTo(DeduplicationAction.AUTO_MERGE);
        assertThat(DeduplicationDecisionGate.evaluate(QuestionRelationship.SAME_QUESTION, true).action())
                .isEqualTo(DeduplicationAction.REQUIRE_REVIEW);
    }

    @Test
    void neverAutoMergesVariantsDistinctOrUndecidableCandidates() {
        assertThat(DeduplicationDecisionGate.evaluate(QuestionRelationship.SAME_STEM_DIFFERENT_VARIANT, false).action())
                .isEqualTo(DeduplicationAction.REQUIRE_REVIEW);
        assertThat(DeduplicationDecisionGate.evaluate(QuestionRelationship.RELATED_BUT_DISTINCT, false).action())
                .isEqualTo(DeduplicationAction.KEEP_SEPARATE);
        assertThat(DeduplicationDecisionGate.evaluate(QuestionRelationship.UNDECIDABLE, false).action())
                .isEqualTo(DeduplicationAction.REQUIRE_REVIEW);
    }
}
