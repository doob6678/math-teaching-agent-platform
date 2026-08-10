package com.doob.mathagent.ingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** Guards the recoverable two-phase import state machine from skipped or reversed states. */
class ImportRunStateTest {

    @Test
    void acceptsThePlannedTwoPhaseLifecycle() {
        ImportRunState state = ImportRunState.CREATED;
        state = state.transitionTo(ImportRunState.PARSING_ALL_FILES);
        state = state.transitionTo(ImportRunState.PARSED_AWAITING_REVIEW);
        state = state.transitionTo(ImportRunState.PAIRING_AND_DEDUPLICATING);
        state = state.transitionTo(ImportRunState.INDEXING);

        assertThat(state.transitionTo(ImportRunState.COMPLETED)).isEqualTo(ImportRunState.COMPLETED);
    }

    @Test
    void refusesToPublishBeforeParsingAndPairing() {
        assertThatThrownBy(() -> ImportRunState.CREATED.transitionTo(ImportRunState.INDEXING))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("CREATED");
    }
}
