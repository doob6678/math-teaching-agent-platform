package com.doob.mathagent.resources;

import java.nio.file.Path;
import java.util.Map;

public record TextbookResourceProperties(Path processedBooksRoot) {

    public TextbookResourceProperties {
        processedBooksRoot = processedBooksRoot.toAbsolutePath().normalize();
    }

    public static TextbookResourceProperties fromEnvironment() {
        return fromEnvironment(System.getenv());
    }

    public static TextbookResourceProperties fromEnvironment(Map<String, String> environment) {
        String value = environment.get("MATH_AGENT_PROCESSED_BOOKS_ROOT");
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing required environment variable: MATH_AGENT_PROCESSED_BOOKS_ROOT");
        }
        return new TextbookResourceProperties(Path.of(value));
    }
}
