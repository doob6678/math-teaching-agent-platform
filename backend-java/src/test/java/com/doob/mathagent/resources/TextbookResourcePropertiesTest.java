package com.doob.mathagent.resources;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TextbookResourcePropertiesTest {

    @Test
    void readsProcessedBooksRootFromEnvironmentMap() {
        TextbookResourceProperties properties = TextbookResourceProperties.fromEnvironment(
                Map.of("MATH_AGENT_PROCESSED_BOOKS_ROOT", "C:/books/processed_books"));

        assertThat(properties.processedBooksRoot())
                .isEqualTo(Path.of("C:/books/processed_books").toAbsolutePath().normalize());
    }

    @Test
    void failsFastWhenProcessedBooksRootIsMissing() {
        assertThatThrownBy(() -> TextbookResourceProperties.fromEnvironment(Map.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("MATH_AGENT_PROCESSED_BOOKS_ROOT");
    }
}
