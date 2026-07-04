package com.doob.mathagent.retrieval;

import com.doob.mathagent.infrastructure.database.DatabaseMigrationProperties;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.Environment;

@Configuration
public class RetrievalAuditConfiguration {

    @Bean
    public RecentRetrievalAuditStore recentRetrievalAuditStore() {
        return new RecentRetrievalAuditStore(200);
    }

    @Bean
    public JdbcRetrievalAuditSink jdbcRetrievalAuditSink(Environment environment) {
        DatabaseMigrationProperties properties = DatabaseMigrationProperties.from(environment);
        properties.validate();
        return new JdbcRetrievalAuditSink(properties);
    }

    @Bean
    @Primary
    public RetrievalAuditSink retrievalAuditSink(
            JdbcRetrievalAuditSink persistentAuditStore,
            RecentRetrievalAuditStore recentAuditStore) {
        return new CompositeRetrievalAuditSink(List.of(
                persistentAuditStore,
                recentAuditStore));
    }

    @Bean
    @Primary
    public RetrievalAuditLookup retrievalAuditLookup(JdbcRetrievalAuditSink persistentAuditStore) {
        return persistentAuditStore::findByQueryId;
    }
}
