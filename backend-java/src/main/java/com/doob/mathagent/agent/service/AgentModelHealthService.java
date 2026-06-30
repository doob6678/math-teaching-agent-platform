package com.doob.mathagent.agent.service;

import com.doob.mathagent.agent.vo.AgentModelHealthResponse;
import com.doob.mathagent.infrastructure.ai.AiProviderCatalog;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
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
                    "Provider health check failed: " + exception.getClass().getSimpleName() + ".",
                    responseCheckedAt);
        }
    }

    private static long elapsedMs(long startedNanos) {
        return Math.max(0L, Duration.ofNanos(System.nanoTime() - startedNanos).toMillis());
    }

    private static Integer statusCode(Exception exception) {
        if (exception instanceof RestClientResponseException responseException) {
            return responseException.getStatusCode().value();
        }
        Throwable cause = exception.getCause();
        if (cause instanceof RestClientResponseException responseException) {
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
}
