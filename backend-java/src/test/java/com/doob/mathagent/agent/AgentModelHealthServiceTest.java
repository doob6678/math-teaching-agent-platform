package com.doob.mathagent.agent;

import static org.assertj.core.api.Assertions.assertThat;

import com.doob.mathagent.agent.service.AgentModelHealthService;
import com.doob.mathagent.agent.service.AiChatGateway;
import com.doob.mathagent.agent.service.AiChatRequest;
import com.doob.mathagent.agent.service.AiChatResult;
import com.doob.mathagent.infrastructure.ai.AiProviderCatalog;
import com.doob.mathagent.infrastructure.ai.AiProviderProperties;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class AgentModelHealthServiceTest {

    @Test
    void checksConfiguredProvidersWithRealGatewayBoundaryAndSafeMetadataOnly() {
        AiProviderProperties properties = providerProperties();
        AgentModelHealthService service = new AgentModelHealthService(
                new AiProviderCatalog(properties),
                request -> new AiChatResult(
                        request.providerName(),
                        request.modelCode(),
                        3,
                        2,
                        5,
                        "raw output must not be exposed"),
                Clock.fixed(Instant.parse("2026-06-30T05:00:00Z"), ZoneOffset.UTC));

        var response = service.checkHealth();

        assertThat(response.checkedAt()).isEqualTo(Instant.parse("2026-06-30T05:00:00Z"));
        assertThat(response.results()).hasSize(2);
        assertThat(response.results().getFirst().providerName()).isEqualTo("openai");
        assertThat(response.results().getFirst().modelCode()).isEqualTo("gpt-5.4");
        assertThat(response.results().getFirst().configured()).isTrue();
        assertThat(response.results().getFirst().reachable()).isTrue();
        assertThat(response.results().getFirst().statusCode()).isEqualTo(200);
        assertThat(response.results().getFirst().elapsedMs()).isGreaterThanOrEqualTo(0);
        assertThat(response.results().getFirst().safeReason()).isEqualTo("Provider answered the health check.");
        assertThat(response.toString()).doesNotContain("openai-key", "dashscope-key", "raw output");
    }

    @Test
    void reportsProviderFailuresWithoutLeakingSecretsOrRawErrorBodies() {
        AiProviderProperties properties = providerProperties();
        AiChatGateway gateway = request -> {
            if ("dashscope".equals(request.providerName())) {
                throw new IllegalStateException("401 invalid api key dashscope-key raw response body");
            }
            return new AiChatResult(request.providerName(), request.modelCode(), 1, 1, 2, "ok");
        };
        AgentModelHealthService service = new AgentModelHealthService(
                new AiProviderCatalog(properties),
                gateway,
                Clock.fixed(Instant.parse("2026-06-30T05:00:00Z"), ZoneOffset.UTC));

        var response = service.checkHealth();

        assertThat(response.results()).hasSize(2);
        assertThat(response.results().get(1).providerName()).isEqualTo("dashscope");
        assertThat(response.results().get(1).configured()).isTrue();
        assertThat(response.results().get(1).reachable()).isFalse();
        assertThat(response.results().get(1).safeReason()).isEqualTo("Provider health check failed: IllegalStateException.");
        assertThat(response.toString()).doesNotContain("dashscope-key", "raw response body");
    }

    private static AiProviderProperties providerProperties() {
        AiProviderProperties properties = new AiProviderProperties();
        properties.setDefaultProvider("openai");
        properties.getOpenai().setApiKey("openai-key");
        properties.getOpenai().setChatModel("gpt-5.4");
        properties.getDashscope().setApiKey("dashscope-key");
        properties.getDashscope().setChatModel("qwen3.6-flash");
        return properties;
    }
}
