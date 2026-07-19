package com.doob.mathagent.retrieval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.BeanCreationException;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;

class RetrievalAuditConfigurationTest {

    @Test
    void rejectsDatabaseDisabledInsteadOfUsingMemoryAuditAsProductionFallback() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource(
                    "test-db-disabled",
                    Map.of("MATH_AGENT_DB_ENABLED", "false", "math-agent.database.enabled", "false")));
            context.register(RetrievalAuditConfiguration.class);

            assertThatThrownBy(context::refresh)
                    .isInstanceOf(BeanCreationException.class)
                    .hasMessageContaining("jdbcRetrievalAuditSink")
                    .rootCause()
                    .hasMessageContaining("MATH_AGENT_DB_ENABLED=false is not supported");
        }
    }

    @Test
    void exposesPersistentRetrievalAuditLookupAndCompositeSink() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource(
                    "test-db-configured",
                    Map.of(
                            "math-agent.database.enabled", "true",
                            "math-agent.database.url", "jdbc:mysql://127.0.0.1:13306/math_agent",
                            "math-agent.database.username", "root",
                            "math-agent.database.password", "123456")));
            context.register(RetrievalAuditConfiguration.class);
            context.refresh();

            RetrievalAuditSink sink = context.getBean(RetrievalAuditSink.class);
            RetrievalAuditLookup lookup = context.getBean(RetrievalAuditLookup.class);

            assertThat(sink).isInstanceOf(CompositeRetrievalAuditSink.class);
            assertThat(lookup).isNotInstanceOf(RecentRetrievalAuditStore.class);
            assertThat(context.getBean(JdbcRetrievalAuditSink.class)).isNotNull();
        }
    }
}
