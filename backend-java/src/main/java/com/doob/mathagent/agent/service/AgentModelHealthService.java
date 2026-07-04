package com.doob.mathagent.agent.service;

import com.doob.mathagent.agent.vo.AgentModelHealthResponse;
import com.doob.mathagent.infrastructure.ai.AiProviderCatalog;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientResponseException;

/**
 * Runs real provider reachability checks through the same OpenAI-compatible gateway used by agent execution.
 */
@Service
public class AgentModelHealthService {

    private final AiProviderCatalog providerCatalog;
    private final AiChatGateway chatGateway;
    private final Clock clock;

    /**
     * Creates the model health service.
     *
     * @param providerCatalog configured provider catalog
     * @param chatGateway real provider gateway
     */
    @Autowired
    public AgentModelHealthService(
            AiProviderCatalog providerCatalog,
            AiChatGateway chatGateway) {
        this(providerCatalog, chatGateway, Clock.systemUTC());
    }

    /**
     * Creates the model health service with an injectable clock for deterministic tests.
     *
     * @param providerCatalog configured provider catalog
     * @param chatGateway real provider gateway
     * @param clock backend clock
     */
    public AgentModelHealthService(
            AiProviderCatalog providerCatalog,
            AiChatGateway chatGateway,
            Clock clock) {
        this.providerCatalog = providerCatalog;
        this.chatGateway = chatGateway;
        this.clock = clock;
    }

    /**
     * Checks every enabled provider with a tiny real request and returns only safe metadata.
     *
     * @return health response
     */
    public AgentModelHealthResponse checkHealth() {
        Instant checkedAt = Instant.now(clock);
        List<AgentModelHealthResponse.Result> results = providerCatalog.enabledProviders()
                .stream()
                .map(provider -> checkProvider(provider, checkedAt))
                .toList();
        return new AgentModelHealthResponse(checkedAt, results);
    }

    private AgentModelHealthResponse.Result checkProvider(AiProviderCatalog.Provider provider, Instant responseCheckedAt) {
        Instant startedAt = Instant.now(clock);
        long startedNanos = System.nanoTime();
        try {
            chatGateway.call(new AiChatRequest(
                    provider.name(),
                    provider.chatModel(),
                    "ModelHealthCheck",
                    "health-check",
                    List.of("health-check")));
            return new AgentModelHealthResponse.Result(
                    provider.name(),
                    provider.chatModel(),
                    true,
                    true,
                    200,
                    elapsedMs(startedNanos),
                    "Provider answered the health check.",
                    responseCheckedAt);
        } catch (Exception exception) {
            return new AgentModelHealthResponse.Result(
                    provider.name(),
                    provider.chatModel(),
                    true,
                    false,
                    statusCode(exception),
                    elapsedMs(startedNanos),
                    safeFailureReason(exception),
                    responseCheckedAt);
        }
    }

    private static long elapsedMs(long startedNanos) {
        return Math.max(0L, Duration.ofNanos(System.nanoTime() - startedNanos).toMillis());
    }

    private static Integer statusCode(Exception exception) {
        RestClientResponseException responseException = responseException(exception);
        if (responseException != null) {
            return responseException.getStatusCode().value();
        }
        if (exception instanceof org.springframework.web.client.RestClientException) {
            return null;
        }
        if (exception instanceof IllegalArgumentException) {
            return HttpStatusCode.valueOf(400).value();
        }
        return null;
    }

    /**
     * Returns a frontend-safe category for provider failures without exposing keys, prompts, or raw bodies.
     */
    private static String safeFailureReason(Exception exception) {
        RestClientResponseException responseException = responseException(exception);
        Integer statusCode = statusCode(exception);
        String lower = safeSearchText(exception, responseException).toLowerCase(Locale.ROOT);
        String prefix = "Provider health check failed";
        if (lower.contains("quota")
                || lower.contains("insufficient_quota")
                || lower.contains("balance")
                || lower.contains("billing")
                || lower.contains("\u4f59\u989d")
                || lower.contains("\u989d\u5ea6")) {
            return prefix + ": quota_or_balance.";
        }
        if (statusCode != null && statusCode == 429 || lower.contains("rate limit") || lower.contains("too many requests")) {
            return prefix + ": rate_limited.";
        }
        if (statusCode != null && (statusCode == 401 || statusCode == 403)
                || lower.contains("invalid api key")
                || lower.contains("access_denied")
                || lower.contains("unauthorized")) {
            return prefix + ": auth_or_access_denied.";
        }
        if (statusCode != null && statusCode == 400 || lower.contains("invalidparameter") || lower.contains("parse the json body")) {
            return prefix + ": invalid_request_or_json.";
        }
        if (lower.contains("timeout") || lower.contains("timed out")) {
            return prefix + ": timeout.";
        }
        return prefix + ": " + exception.getClass().getSimpleName() + ".";
    }

    private static RestClientResponseException responseException(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof RestClientResponseException responseException) {
                return responseException;
            }
            current = current.getCause();
        }
        return null;
    }

    private static String safeSearchText(Exception exception, RestClientResponseException responseException) {
        StringBuilder text = new StringBuilder(exception.getClass().getSimpleName());
        if (exception.getMessage() != null) {
            text.append(' ').append(exception.getMessage());
        }
        if (responseException != null) {
            text.append(' ').append(responseException.getStatusCode().value());
            String body = responseException.getResponseBodyAsString();
            if (body != null) {
                text.append(' ').append(body);
            }
        }
        return text.toString();
    }
}
