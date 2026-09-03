package com.doob.mathagent.infrastructure.database.config;

import com.doob.mathagent.infrastructure.database.DatabaseMigrationProperties;
import com.zaxxer.hikari.HikariDataSource;
import javax.sql.DataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

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
    DataSource mathAgentDataSource(DatabaseMigrationProperties properties, Environment environment) {
        properties.validate();
        HikariDataSource dataSource = new HikariDataSource();
        dataSource.setJdbcUrl(properties.url());
        dataSource.setUsername(properties.username());
        dataSource.setPassword(properties.password());
        // Explicit pool sizing replaces Hikari's implicit default of 10. The agent worker consumer runs up to
        // max-concurrency (20) claim/update transactions per instance while web requests, the outbox publisher and
        // the lease-heartbeat scheduler share the same pool, so 10 connections were the measured second bottleneck
        // behind the single GPU. 25 keeps one backend instance well under MySQL's max_connections=151 (verified on
        // the deployed container 2026-08-31); multi-instance deployments must keep pool_size * instances < limit.
        dataSource.setMaximumPoolSize(Math.max(10, environment.getProperty("math-agent.database.pool-size", Integer.class, 25)));
        dataSource.setPoolName("math-agent-hikari");
        return dataSource;
    }
}
