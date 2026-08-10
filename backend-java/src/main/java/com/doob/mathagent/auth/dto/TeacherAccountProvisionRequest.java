package com.doob.mathagent.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Administrator-only teacher account input.
 *
 * <p>The tenant and role are intentionally absent.  The authenticated administrator session is the only source
 * allowed to determine those fields, so a browser payload cannot create an account in another tenant.</p>
 */
public record TeacherAccountProvisionRequest(
        @NotBlank @Size(min = 3, max = 64) String username,
        @NotBlank @Size(min = 8, max = 128) String password) {
}
