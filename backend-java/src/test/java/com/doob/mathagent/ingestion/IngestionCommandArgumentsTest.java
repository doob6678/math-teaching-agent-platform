package com.doob.mathagent.ingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Verifies the documented one-command syntax before the runner touches the filesystem or database. */
class IngestionCommandArgumentsTest {

    @Test
    void parsesTheDocumentedCommandAndExplicitPaperType() {
        IngestionCommandArguments arguments = IngestionCommandArguments.parse(List.of(
                "gaokao:ingest-and-verify", "--input", "/data/papers", "--paper-type", "GAOKAO", "--model", "gpt-5.6-luna"));

        assertThat(arguments.inputRoot()).isEqualTo("/data/papers");
        assertThat(arguments.paperType()).isEqualTo(PaperType.GAOKAO);
        assertThat(arguments.model()).isEqualTo("gpt-5.6-luna");
    }

    @Test
    void requiresAnExplicitPaperTypeInsteadOfAssumingGaokao() {
        assertThatThrownBy(() -> IngestionCommandArguments.parse(List.of(
                "gaokao:ingest-and-verify", "--input", "/data/papers")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("paper-type");
    }
}
