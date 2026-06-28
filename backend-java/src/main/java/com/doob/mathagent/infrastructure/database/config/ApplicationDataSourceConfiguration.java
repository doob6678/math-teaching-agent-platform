package com.doob.mathagent.infrastructure.database.config;

import com.doob.mathagent.infrastructure.database.DatabaseMigrationProperties;
import com.zaxxer.hikari.HikariDataSource;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Project-owned DataSource configuration.
 *
 * <p>Spring Boot's default DataSource auto-configuration is excluded because the application must start in local
 * no-database mode. When MATH_AGENT_DB_ENABLED=true, this configuration creates the MySQL DataSource from environment
 * properties without committing credentials.</p>
 */
@Configuration
@ConditionalOnProperty(prefix = "math-agent.database", name = "enabled", havingValue = "true")
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
