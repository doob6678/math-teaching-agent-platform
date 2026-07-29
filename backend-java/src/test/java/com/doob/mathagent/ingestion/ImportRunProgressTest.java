package com.doob.mathagent.ingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** Ensures structured CLI/dashboard progress cannot report impossible counters. */
class ImportRunProgressTest {

    @Test
    void computesCompletionFromRealFileCounts() {
        ImportRunProgress progress = new ImportRunProgress(10, 7, 1, 20, 3, 2, 1, 100, 1_500L);

        assertThat(progress.completedFiles()).isEqualTo(8);
        assertThat(progress.completionPercent()).isEqualTo(80);
    }

    @Test
    void rejectsCountersBeyondTheDiscoveredInputSet() {
        assertThatThrownBy(() -> new ImportRunProgress(2, 2, 1, 0, 0, 0, 0, 0, 0L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("file counters");
    }
}
