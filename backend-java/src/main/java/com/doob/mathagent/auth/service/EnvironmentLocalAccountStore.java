package com.doob.mathagent.auth.service;

import java.util.List;
import java.util.Optional;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Repository;

/**
 * Reads local development accounts from Spring configuration.
 */
@Repository
public class EnvironmentLocalAccountStore implements LocalAccountStore {

    private final List<LocalAccount> accounts;

    /**
     * Creates a store from environment properties.
     *
     * @param environment Spring environment
     */
    public EnvironmentLocalAccountStore(Environment environment) {
        this.accounts = List.of(
                account(environment, "student", "local-student", "student", "student", "default"),
                account(environment, "teacher", "local-teacher-console", "teacher", "teacher", "default"),
                account(environment, "admin", "local-admin", "admin", "admin", "default"));
    }

    /**
     * Finds a configured local account by username.
     *
     * @param username login username
     * @return account when present
     */
    @Override
    public Optional<LocalAccount> findByUsername(String username) {
        if (username == null || username.isBlank()) {
            return Optional.empty();
        }
        String normalized = username.strip();
        return accounts.stream()
                .filter(account -> account.username().equals(normalized))
                .findFirst();
    }

    /**
     * Builds one local account from property overrides and safe defaults.
     */
    private static LocalAccount account(
            Environment environment,
            String key,
            String defaultUserId,
            String defaultUsername,
            String defaultRole,
            String defaultTenant) {
        String prefix = "math-agent.auth.local-users." + key + ".";
        return new LocalAccount(
                property(environment, prefix + "user-id", defaultUserId),
                property(environment, prefix + "username", defaultUsername),
                property(environment, prefix + "password", key + "-123456"),
                property(environment, prefix + "role", defaultRole),
                property(environment, prefix + "tenant-id", defaultTenant));
    }

    /**
     * Reads a property with a fallback.
     */
    private static String property(Environment environment, String name, String fallback) {
        String value = environment.getProperty(name);
        return value == null || value.isBlank() ? fallback : value.strip();
    }
}
