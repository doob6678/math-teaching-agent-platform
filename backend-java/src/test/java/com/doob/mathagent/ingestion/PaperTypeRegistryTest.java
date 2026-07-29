package com.doob.mathagent.ingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** Verifies source normalization and public citation never expose internal import identifiers. */
class PaperTypeRegistryTest {

    @Test
    void gaokaoCitationUsesStructuredMetadataInsteadOfInternalIdentifiers() {
        PaperCitation citation = PaperTypeRegistry.defaultRegistry().citationFor(
                PaperType.GAOKAO,
                new PaperMetadata(2024, "新课标II卷", "", "", "18", "run-uuid", "C:/secret/source.pdf"));

        assertThat(citation.displayCitation()).isEqualTo("2024 新课标II卷 第18题");
        assertThat(citation.displayCitation()).doesNotContain("uuid").doesNotContain("source.pdf");
    }

    @Test
    void genericDoesNotPermitAutomaticCrossSourcePairing() {
        assertThat(PaperTypeRegistry.defaultRegistry().policyFor(PaperType.GENERIC).automaticCrossSourcePairing())
                .isFalse();
    }

    @Test
    void gaokaoRequiresYearAndPaperName() {
        assertThatThrownBy(() -> PaperTypeRegistry.defaultRegistry().citationFor(
                PaperType.GAOKAO, new PaperMetadata(null, "", "", "", "18", "", "")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("year");
    }
}
