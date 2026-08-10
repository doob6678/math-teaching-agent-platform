package com.doob.mathagent.agent;

import static org.assertj.core.api.Assertions.assertThat;

import com.doob.mathagent.agent.dto.MultiAgentWritingRequest;
import org.junit.jupiter.api.Test;

class MultiAgentWritingRequestTest {

    @Test
    void keepsRequestPreferenceAvailableForServerSideWritingRouteDecision() {
        MultiAgentWritingRequest normalized = new MultiAgentWritingRequest(
                "teacher handout", "space vector", java.util.List.of(), false, "openai", "gpt-5.6-terra")
                .normalize();

        assertThat(normalized.preferredProviderName()).isEqualTo("openai");
        assertThat(normalized.preferredModelCode()).isEqualTo("gpt-5.6-terra");
    }
}
