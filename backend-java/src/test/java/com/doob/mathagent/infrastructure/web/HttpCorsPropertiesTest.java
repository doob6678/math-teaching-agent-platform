package com.doob.mathagent.infrastructure.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class HttpCorsPropertiesTest {

    @Test
    void usesLocalFrontendOriginsByDefault() {
        HttpCorsProperties properties = HttpCorsProperties.localDefaults();

        assertThat(properties.allowedOrigins())
                .contains(
                        "http://127.0.0.1:5173",
                        "http://127.0.0.1:5174",
                        "http://localhost:5173",
                        "http://localhost:5174");
    }

    @Test
    void keepsTheCheckedInLocalOriginContract() {
        HttpCorsProperties properties = HttpCorsProperties.localDefaults();

        assertThat(properties.allowedOrigins()).contains("http://127.0.0.1:5173");
    }
}
