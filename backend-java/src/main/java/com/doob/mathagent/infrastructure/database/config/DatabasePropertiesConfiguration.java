package com.doob.mathagent.infrastructure.database.config;

import com.doob.mathagent.infrastructure.database.DatabaseMigrationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * Provides database connection properties as a first-class Spring bean.
 */
@Configuration
public class DatabasePropertiesConfiguration {

    @Bean
    DatabaseMigrationProperties databaseMigrationProperties(Environment environment) {
        return DatabaseMigrationProperties.from(environment);
    }
}
