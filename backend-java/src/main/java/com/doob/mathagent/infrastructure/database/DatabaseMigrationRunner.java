package com.doob.mathagent.infrastructure.database;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/** Applies every versioned schema change before application seed runners access MyBatis stores. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class DatabaseMigrationRunner implements ApplicationRunner {

    private final Environment environment;

    public DatabaseMigrationRunner(Environment environment) {
        this.environment = environment;
    }

    /** Validates the real database configuration and migrates it idempotently on every backend start. */
    @Override
    public void run(ApplicationArguments args) {
        DatabaseMigrationProperties properties = DatabaseMigrationProperties.from(environment);
        properties.validate();

        Flyway.configure()
                .dataSource(properties.url(), properties.username(), properties.safePassword())
                .locations("classpath:db/migration")
                // Docker initializes operational tables first, so baseline at zero and still execute the V1 core schema.
                .baselineOnMigrate(true)
                .baselineVersion(MigrationVersion.fromVersion("0"))
                .load()
                .migrate();
    }
}
