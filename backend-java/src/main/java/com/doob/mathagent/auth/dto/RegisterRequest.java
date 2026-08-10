package com.doob.mathagent.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Public student registration request.
 *
 * @param username unique login username
 * @param password plaintext password submitted over HTTPS in production
 */
public record RegisterRequest(
        @NotBlank @Size(min = 3, max = 64) String username,
        @NotBlank @Size(min = 8, max = 128) String password) {
}
