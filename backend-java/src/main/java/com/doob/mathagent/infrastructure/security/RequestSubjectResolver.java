package com.doob.mathagent.infrastructure.security;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Resolves the effective backend subject for a request.
 */
@FunctionalInterface
public interface RequestSubjectResolver {

    /**
     * Resolves the request subject from trusted backend state.
     *
     * @param request HTTP request, or null in direct local tests
     * @return resolved request subject
     */
    RequestSubject resolve(HttpServletRequest request);

    /**
     * Returns a resolver for direct local tests where no HTTP request exists.
     *
     * @return local development resolver
     */
    static RequestSubjectResolver localDevelopment() {
        return request -> RequestSubject.anonymous("default", "unknown-device");
    }
}
