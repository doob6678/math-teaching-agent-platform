package com.doob.mathagent.infrastructure.database;

import org.springframework.core.env.Environment;

public record DatabaseMigrationProperties(
        boolean enabled,
        String url,
        String username,
        String password) {

    public static DatabaseMigrationProperties from(Environment environment) {
        return new DatabaseMigrationProperties(
                environment.getProperty("math-agent.database.enabled", Boolean.class, false),
                environment.getProperty("math-agent.database.url", ""),
                environment.getProperty("math-agent.database.username", ""),
                environment.getProperty("math-agent.database.password", ""));
    }

    public void validate() {
        if (!enabled) {
            return;
        }
        if (url == null || url.isBlank()) {
            throw new IllegalStateException("MATH_AGENT_DB_URL must be set when MATH_AGENT_DB_ENABLED=true");
        }
        if (username == null || username.isBlank()) {
            throw new IllegalStateException("MATH_AGENT_DB_USERNAME must be set when MATH_AGENT_DB_ENABLED=true");
        }
    }

    public String safePassword() {
        return password == null ? "" : password;
    }
}
