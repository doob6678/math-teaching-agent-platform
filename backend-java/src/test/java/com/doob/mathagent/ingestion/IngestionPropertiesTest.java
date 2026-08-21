package com.doob.mathagent.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Verifies the ingestion directory contract is explicit configuration, not an environment-variable convention. */
class IngestionPropertiesTest {

    @Test
    void usesFixedDockerPathsUntilAConfigurationFileChangesThem() {
        IngestionProperties properties = new IngestionProperties();

        assertThat(properties.getInputRoot()).isEqualTo("/app/data/gaokao-input");
        assertThat(properties.getEvidenceRoot()).isEqualTo("/app/data/math-paper-corpus");
    }

    @Test
    void normalizesConfiguredSourceWhitelistWithoutEnvironmentVariables() {
        IngestionProperties properties = new IngestionProperties();
        properties.setSelectedSourceFileNames(java.util.List.of(" 北京.pdf ", "", "新课标Ⅰ.pdf"));

        assertThat(properties.getSelectedSourceFileNames()).containsExactly("北京.pdf", "新课标Ⅰ.pdf");
    }
}
