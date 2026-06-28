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
    @Primary
    public RetrievalAuditSink retrievalAuditSink(Environment environment, RecentRetrievalAuditStore recentAuditStore) {
        DatabaseMigrationProperties properties = DatabaseMigrationProperties.from(environment);
        if (!properties.enabled()) {
            return recentAuditStore;
        }
        properties.validate();
        return new CompositeRetrievalAuditSink(List.of(
                recentAuditStore,
                new JdbcRetrievalAuditSink(properties)));
    }
}
