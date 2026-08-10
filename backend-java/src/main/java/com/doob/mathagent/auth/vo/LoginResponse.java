package com.doob.mathagent.auth.vo;

/**
 * Login response returned after the backend creates the HttpOnly cookie session.
 *
 * @param userId authenticated user id
 * @param username account name
 * @param role role stored in backend session
 * @param tenantId tenant id stored in backend session
 */
public record LoginResponse(
        String userId,
        String username,
        String role,
        String tenantId) {
}
