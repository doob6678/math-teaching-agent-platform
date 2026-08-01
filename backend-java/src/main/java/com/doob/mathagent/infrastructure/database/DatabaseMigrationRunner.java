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
 * Optional operator-controlled schema tool. It is deliberately disabled by default so a normal backend restart
 * never mutates MySQL; schema ownership stays with the deployment/bootstrap process.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
@ConditionalOnProperty(prefix = "math-agent.database.migration", name = "runner-enabled", havingValue = "true")
public class DatabaseMigrationRunner implements ApplicationRunner {

    private final Environment environment;

    public DatabaseMigrationRunner(Environment environment) {
        this.environment = environment;
    }

    /** Runs only when an operator explicitly opts in with the migration runner property. */
    @Override
    public void run(ApplicationArguments args) {
        DatabaseMigrationProperties properties = DatabaseMigrationProperties.from(environment);
        properties.validate();
        Flyway.configure()
                .dataSource(properties.url(), properties.username(), properties.safePassword())
                .locations("classpath:db/migration")
                .baselineOnMigrate(true)
                .baselineVersion(MigrationVersion.fromVersion("0"))
                .load()
                .migrate();
    }
}
