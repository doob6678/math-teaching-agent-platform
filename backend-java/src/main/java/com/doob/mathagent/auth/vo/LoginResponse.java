package com.doob.mathagent.auth.vo;

/**
 * Login response returned after Sa-Token creates the backend session.
 *
 * @param userId authenticated user id
 * @param username account name
 * @param role role stored in backend session
 * @param tenantId tenant id stored in backend session
 * @param tokenName Sa-Token token header/cookie name
 * @param tokenValue issued token value
 */
public record LoginResponse(
        String userId,
        String username,
        String role,
        String tenantId,
        String tokenName,
        String tokenValue) {
}
