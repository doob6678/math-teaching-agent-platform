package com.doob.mathagent.ingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** Validates the independent three-layer verification lifecycle that gates publication. */
class ImportVerificationStateTest {

    @Test
    void acceptsRulesGoldenAndLunaReviewInOrder() {
        ImportVerificationState state = ImportVerificationState.NOT_STARTED;
        state = state.transitionTo(ImportVerificationState.RULE_CHECKING);
        state = state.transitionTo(ImportVerificationState.GOLDEN_COMPARING);
        state = state.transitionTo(ImportVerificationState.AI_REVIEWING);

        assertThat(state.transitionTo(ImportVerificationState.VERIFIED)).isEqualTo(ImportVerificationState.VERIFIED);
    }

    @Test
    void refusesVerifiedStatusWhenGoldenAndLunaReviewWereSkipped() {
        assertThatThrownBy(() -> ImportVerificationState.RULE_CHECKING.transitionTo(ImportVerificationState.VERIFIED))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("RULE_CHECKING");
    }
}
