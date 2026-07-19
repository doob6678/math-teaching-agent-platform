package com.doob.mathagent.auth.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Login request for local account authentication.
 *
 * @param username account name
 * @param password plaintext password submitted over HTTPS in production
 */
public record LoginRequest(
        @NotBlank String username,
        @NotBlank String password) {
}
