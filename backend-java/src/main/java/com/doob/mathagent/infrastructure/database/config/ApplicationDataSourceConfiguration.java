package com.doob.mathagent.infrastructure.database.config;

import com.doob.mathagent.infrastructure.database.DatabaseMigrationProperties;
import com.zaxxer.hikari.HikariDataSource;
import javax.sql.DataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Project-owned DataSource configuration.
 *
 * <p>The application is not allowed to start without a real MySQL DataSource. Missing database environment variables
 * fail fast during startup instead of silently switching to in-memory stores.</p>
 */
@Configuration
public class ApplicationDataSourceConfiguration {

    /**
     * Creates the JDBC DataSource used by MyBatis-Plus and future persistence stores.
     *
     * @param properties database connection properties loaded from environment variables
     * @return configured Hikari DataSource
     */
    @Bean
    DataSource mathAgentDataSource(DatabaseMigrationProperties properties) {
        properties.validate();
        HikariDataSource dataSource = new HikariDataSource();
        dataSource.setJdbcUrl(properties.url());
        dataSource.setUsername(properties.username());
        dataSource.setPassword(properties.password());
        return dataSource;
    }
}
