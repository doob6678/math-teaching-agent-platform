package com.doob.mathagent.auth.vo;

import com.doob.mathagent.auth.service.LocalAccount;

/**
 * Safe administrator-facing result of creating a teacher account.
 *
 * <p>Authentication records retain password hashes internally, but account provisioning must never serialize them to
 * an administrator, browser, log, or MCP client.</p>
 *
 * @param userId stable backend subject id
 * @param username teacher login name
 * @param role fixed teacher role
 * @param tenantId tenant inherited from the administrator session
 */
public record TeacherAccountProvisionResponse(String userId, String username, String role, String tenantId) {

    /**
     * Converts an internal account record to the narrow response allowed at the management boundary.
     *
     * @param account internally stored account
     * @return password-free response
     */
    public static TeacherAccountProvisionResponse from(LocalAccount account) {
        if (account == null) {
            throw new IllegalArgumentException("Created teacher account is required");
        }
        return new TeacherAccountProvisionResponse(account.userId(), account.username(), account.role(), account.tenantId());
    }
}
