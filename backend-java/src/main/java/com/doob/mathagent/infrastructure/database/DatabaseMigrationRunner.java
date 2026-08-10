package com.doob.mathagent.infrastructure.database;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Startup schema gate. The backend must validate and apply the versioned schema before serving requests so a stale
 * application binary cannot silently run against a newer or incompatible database.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
@ConditionalOnProperty(prefix = "math-agent.database.migration", name = "runner-enabled", havingValue = "true")
public class DatabaseMigrationRunner implements ApplicationRunner {

    private final Environment environment;

    public DatabaseMigrationRunner(Environment environment) {
        this.environment = environment;
    }

    /** Validates and applies migrations before the rest of the application is considered ready. */
    @Override
    public void run(ApplicationArguments args) {
        DatabaseMigrationProperties properties = DatabaseMigrationProperties.from(environment);
        properties.validate();
        Flyway.configure()
                .dataSource(properties.url(), properties.username(), properties.safePassword())
                .locations("classpath:db/migration")
                .baselineOnMigrate(true)
                .baselineVersion(MigrationVersion.fromVersion("0"))
                .validateOnMigrate(true)
                .cleanDisabled(true)
                .load()
                .migrate();
    }
}
