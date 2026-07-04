package com.doob.mathagent;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.doob.mathagent.infrastructure.database.DatabaseMigrationProperties;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class MathAgentApplicationContextTest {

    @Test
    void databaseDisabledFlagIsRejectedBeforeAnyNoopStoresCanBeUsed() {
        DatabaseMigrationProperties properties = DatabaseMigrationProperties.from(
                new MockEnvironment().withProperty("math-agent.database.enabled", "false"));

        assertThatThrownBy(properties::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("MATH_AGENT_DB_ENABLED=false is not supported");
    }
}
