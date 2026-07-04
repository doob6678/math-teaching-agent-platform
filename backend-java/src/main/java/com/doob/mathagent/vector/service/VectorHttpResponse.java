package com.doob.mathagent.vector.service;

/**
 * HTTP status and response body.
 */
public record VectorHttpResponse(int statusCode, String body) {

    public boolean success2xx() {
        return statusCode >= 200 && statusCode < 300;
    }
}
