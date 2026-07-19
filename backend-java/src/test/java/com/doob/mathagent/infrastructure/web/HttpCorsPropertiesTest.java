package com.doob.mathagent.infrastructure.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

class HttpCorsPropertiesTest {

    @Test
    void usesLocalFrontendOriginsByDefault() {
        HttpCorsProperties properties = HttpCorsProperties.fromEnvironment(Map.of());

        assertThat(properties.allowedOrigins())
                .contains(
                        "http://127.0.0.1:5173",
                        "http://127.0.0.1:5174",
                        "http://localhost:5173",
                        "http://localhost:5174");
    }

    @Test
    void readsCommaSeparatedAllowedOriginsFromEnvironment() {
        HttpCorsProperties properties = HttpCorsProperties.fromEnvironment(Map.of(
                "MATH_AGENT_CORS_ALLOWED_ORIGINS",
                "http://127.0.0.1:5173, https://admin.example.com "));

        assertThat(properties.allowedOrigins())
                .containsExactly("http://127.0.0.1:5173", "https://admin.example.com");
    }
}
