package com.doob.mathagent.vector.service;

import java.net.URI;
import java.time.Duration;
import java.util.Map;

/**
 * Minimal HTTP transport boundary for embedding and Milvus REST calls.
 */
public interface VectorHttpTransport {

    /**
     * Sends a JSON POST request and returns status/body.
     */
    VectorHttpResponse postJson(URI uri, Map<String, String> headers, String body, Duration timeout);
}
