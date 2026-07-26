package com.doob.mathagent.agent.service;

/**
 * Indicates that the configured model endpoint could not accept a request after bounded retries.
 *
 * <p>The exception deliberately exposes only the HTTP status to controllers. The provider response body can contain
 * operational request identifiers, so the gateway writes its bounded form to server logs instead of returning it to
 * a browser.</p>
 */
public class AiProviderUnavailableException extends IllegalStateException {

    private final int statusCode;

    public AiProviderUnavailableException(int statusCode, String message) {
        super(message);
        this.statusCode = statusCode;
    }

    public int statusCode() {
        return statusCode;
    }
}
