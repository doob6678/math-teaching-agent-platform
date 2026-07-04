package com.doob.mathagent.vector.service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Production HTTP transport backed by Java HttpClient.
 */
@Component
public class JavaNetVectorHttpTransport implements VectorHttpTransport {

    private final HttpClient client = HttpClient.newHttpClient();

    @Override
    public VectorHttpResponse postJson(URI uri, Map<String, String> headers, String body, Duration timeout) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                .timeout(timeout)
                .version(HttpClient.Version.HTTP_1_1)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        headers.forEach(builder::header);
        try {
            HttpResponse<String> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            return new VectorHttpResponse(response.statusCode(), response.body());
        } catch (IOException e) {
            throw new IllegalStateException("Vector HTTP request failed: " + e.getClass().getSimpleName(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Vector HTTP request interrupted", e);
        }
    }
}
