package com.doob.mathagent.infrastructure.database;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class DatabaseMigrationPropertiesTest {

    @Test
    void rejectsDisabledDatabaseBecauseRuntimeMustBePersistent() {
        DatabaseMigrationProperties properties = new DatabaseMigrationProperties(false, "", "", "");

        assertThatThrownBy(properties::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("MATH_AGENT_DB_ENABLED=false is not supported");
    }

    @Test
    void enablesDatabaseByDefaultForDeployableRuntime() {
        DatabaseMigrationProperties properties = DatabaseMigrationProperties.from(new MockEnvironment());

        assertThatThrownBy(properties::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("MATH_AGENT_DB_URL");
    }

    @Test
    void requiresJdbcUrlAndUsernameWhenDatabaseMigrationIsEnabled() {
        DatabaseMigrationProperties missingUrl = new DatabaseMigrationProperties(true, "", "math_agent", "");
        DatabaseMigrationProperties missingUsername = new DatabaseMigrationProperties(true, "jdbc:mysql://localhost:3306/math_agent", "", "");

        assertThatThrownBy(missingUrl::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("MATH_AGENT_DB_URL");
        assertThatThrownBy(missingUsername::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("MATH_AGENT_DB_USERNAME");
    }
}
