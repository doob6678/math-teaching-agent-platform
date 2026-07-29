package com.doob.mathagent.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Verifies source occurrences do not collide when two regions share page and printed number. */
class QuestionOccurrenceIdentityTest {

    @Test
    void usesSourcePageRegionAndRawQuestionNumberForIdempotency() {
        String first = QuestionOccurrenceIdentity.fingerprint("source-hash", 2, 2, new QuestionRegion(10, 20, 300, 400), "17.(1)");
        String repeated = QuestionOccurrenceIdentity.fingerprint("source-hash", 2, 2, new QuestionRegion(10, 20, 300, 400), "17.(1)");
        String anotherRegion = QuestionOccurrenceIdentity.fingerprint("source-hash", 2, 2, new QuestionRegion(10, 420, 300, 760), "17.(1)");

        assertThat(repeated).isEqualTo(first);
        assertThat(anotherRegion).isNotEqualTo(first);
    }
}
