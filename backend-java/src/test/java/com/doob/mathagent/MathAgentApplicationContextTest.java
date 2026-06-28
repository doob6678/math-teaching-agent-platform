package com.doob.mathagent;

import static org.assertj.core.api.Assertions.assertThat;

import com.doob.mathagent.infrastructure.security.ApiAccessControlService;
import com.doob.mathagent.infrastructure.security.ApiRateLimiter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import java.nio.file.Path;

class MathAgentApplicationContextTest {

    @TempDir
    Path tempDir;

    @Test
    void startsApplicationContextWithDefaultLocalRateLimiter() {
        Path processedBooksRoot = tempDir.resolve("processed_books");
        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(MathAgentApplication.class)
                .web(WebApplicationType.NONE)
                .properties(
                        "spring.flyway.enabled=false",
                        "math-agent.database.enabled=false",
                        "math-agent.redis.rate-limit.enabled=false",
                        "math-agent.resources.processed-books-root=" + processedBooksRoot)
                .run()) {
            assertThat(context.getBean(ApiRateLimiter.class)).isNotNull();
            assertThat(context.getBean(ApiAccessControlService.class)).isNotNull();
        }
    }
}
