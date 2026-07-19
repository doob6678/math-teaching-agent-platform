package com.doob.mathagent.infrastructure.web;

/**
 * Uniform JSON error payload returned by global REST exception handling.
 *
 * @param code stable machine-readable error code
 * @param message safe message shown to the frontend
 * @param traceId backend log correlation id
 * @param path request path that failed
 */
public record ApiErrorResponse(
        String code,
        String message,
        String traceId,
        String path) {
}
