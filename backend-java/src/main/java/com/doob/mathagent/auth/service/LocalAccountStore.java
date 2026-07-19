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

    /**
     * Finds an account by backend user id.
     *
     * @param userId backend user id stored in session and domain records
     * @return account when present
     */
    Optional<LocalAccount> findByUserId(String userId);

    /**
     * Creates a new student account for public registration.
     *
     * @param username unique login username
     * @param encodedPassword encoded password
     * @param tenantId tenant id
     * @return created account
     */
    LocalAccount createStudent(String username, String encodedPassword, String tenantId);
}
