package com.doob.mathagent.auth.service;

/**
 * Local account used before the real user table is introduced.
 *
 * @param userId stable backend user id
 * @param username login username
 * @param password configured password for local development
 * @param role role saved into Sa-Token session
 * @param tenantId tenant id saved into Sa-Token session
 */
public record LocalAccount(
        String userId,
        String username,
        String password,
        String role,
        String tenantId) {
}
