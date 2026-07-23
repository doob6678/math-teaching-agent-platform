package com.doob.mathagent.teacher.vo;

import java.util.List;

/**
 * Provider failure details persisted with a source-sync job and returned to the resource UI.
 *
 * The backend never invents an application-specific authorization link. An authorization URL is present only when
 * the provider actually returned one; required scopes are likewise copied from the provider response when available.
 */
public record TeacherSourceSyncFailureResponse(
        String providerCode,
        boolean retryable,
        List<String> requiredScopes,
        String authorizationUrl) {

    public TeacherSourceSyncFailureResponse {
        requiredScopes = requiredScopes == null ? List.of() : List.copyOf(requiredScopes);
    }

    public static TeacherSourceSyncFailureResponse none() {
        return new TeacherSourceSyncFailureResponse(null, false, List.of(), null);
    }
}
