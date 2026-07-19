package com.doob.mathagent.agent;

import static org.assertj.core.api.Assertions.assertThat;

import com.doob.mathagent.agent.controller.AgentModelHealthController;
import com.doob.mathagent.agent.service.AgentModelHealthService;
import com.doob.mathagent.agent.vo.AgentModelHealthResponse;
import com.doob.mathagent.infrastructure.ai.AiProviderCatalog;
import com.doob.mathagent.infrastructure.ai.AiProviderProperties;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class AgentModelHealthControllerTest {

    @Test
    void returnsSafeBackendHealthWithoutClientSuppliedIdentity() {
        AiProviderProperties properties = new AiProviderProperties();
        properties.setDefaultProvider("openai");
        properties.getOpenai().setApiKey("openai-key");
        properties.getOpenai().setChatModel("gpt-5.4");
        AgentModelHealthService service = new AgentModelHealthService(
                new AiProviderCatalog(properties),
                request -> null,
                Clock.fixed(Instant.parse("2026-06-30T05:00:00Z"), ZoneOffset.UTC));
        AgentModelHealthController controller = new AgentModelHealthController(service);

        AgentModelHealthResponse response = controller.modelHealth();

        assertThat(response.results()).hasSize(1);
        assertThat(response.results().getFirst().providerName()).isEqualTo("openai");
        assertThat(response.results().getFirst().modelCode()).isEqualTo("gpt-5.4");
        assertThat(response.results().getFirst().reachable()).isTrue();
        assertThat(response.toString()).doesNotContain("openai-key");
    }
}
