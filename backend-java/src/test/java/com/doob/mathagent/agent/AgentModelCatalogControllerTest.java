package com.doob.mathagent.agent;

import static org.assertj.core.api.Assertions.assertThat;

import com.doob.mathagent.agent.controller.AgentModelCatalogController;
import com.doob.mathagent.agent.vo.AgentModelCatalogResponse;
import com.doob.mathagent.infrastructure.ai.AiProviderCatalog;
import com.doob.mathagent.infrastructure.ai.AiProviderProperties;
import org.junit.jupiter.api.Test;

class AgentModelCatalogControllerTest {

    @Test
    void returnsBackendModelCatalogWithoutClientSuppliedIdentity() {
        AiProviderProperties properties = new AiProviderProperties();
        properties.setDefaultProvider("openai");
        properties.getOpenai().setApiKey("openai-key");
        properties.getOpenai().setChatModel("gpt-5.4");
        properties.getDashscope().setApiKey("dashscope-key");
        properties.getDashscope().setChatModel("qwen3.6-flash");
        AgentModelCatalogController controller = new AgentModelCatalogController(new AiProviderCatalog(properties));

        AgentModelCatalogResponse response = controller.modelCatalog();

        assertThat(response.defaultProviderName()).isEqualTo("openai");
        assertThat(response.defaultModelCode()).isEqualTo("gpt-5.4");
        assertThat(response.providers()).extracting(AgentModelCatalogResponse.Provider::name)
                .containsExactly("openai", "dashscope");
        assertThat(response.providers().getFirst().models())
                .extracting(AgentModelCatalogResponse.Model::modelCode)
                .contains("gpt-5.4", "gpt-5.4-mini");
        assertThat(response.toString()).doesNotContain("openai-key", "dashscope-key");
    }
}
