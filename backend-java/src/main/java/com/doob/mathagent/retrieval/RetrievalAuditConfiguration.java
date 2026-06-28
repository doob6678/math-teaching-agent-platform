package com.doob.mathagent.retrieval;

import com.doob.mathagent.infrastructure.database.DatabaseMigrationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

@Configuration
public class RetrievalAuditConfiguration {

    @Bean
    public RetrievalAuditSink retrievalAuditSink(Environment environment) {
        DatabaseMigrationProperties properties = DatabaseMigrationProperties.from(environment);
        if (!properties.enabled()) {
            return new NoopRetrievalAuditSink();
        }
        properties.validate();
        return new JdbcRetrievalAuditSink(properties);
    }
}
