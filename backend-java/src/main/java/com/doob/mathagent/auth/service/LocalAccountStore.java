package com.doob.mathagent.auth.service;

import java.util.Optional;

/**
 * Account lookup abstraction for authentication.
 */
public interface LocalAccountStore {

    /**
     * Finds an account by username.
     *
     * @param username login username
     * @return account when present
     */
    Optional<LocalAccount> findByUsername(String username);
}
