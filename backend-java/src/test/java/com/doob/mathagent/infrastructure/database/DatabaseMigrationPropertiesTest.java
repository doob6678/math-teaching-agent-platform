package com.doob.mathagent.infrastructure.database;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class DatabaseMigrationPropertiesTest {

    @Test
    void allowsBlankConnectionSettingsWhenDatabaseMigrationIsDisabled() {
        DatabaseMigrationProperties properties = new DatabaseMigrationProperties(false, "", "", "");

        assertThatCode(properties::validate).doesNotThrowAnyException();
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
