package com.doob.mathagent.infrastructure.ai;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class AiProviderCatalogTest {

    @Test
    void buildsOpenAiDeepSeekAndArkProvidersFromEnvironmentBackedProperties() {
        AiProviderProperties properties = new AiProviderProperties();
        properties.setDefaultProvider("openai");
        properties.getOpenai().setApiKey("openai-key");
        properties.getOpenai().setBaseUrl("https://api.openai.com");
        properties.getOpenai().setChatModel("gpt-4.1");
        properties.getDeepseek().setApiKey("deepseek-key");
        properties.getDeepseek().setBaseUrl("https://api.deepseek.com");
        properties.getDeepseek().setChatModel("deepseek-chat");
        properties.getArk().setApiKey("ark-key");
        properties.getArk().setBaseUrl("https://ark.cn-beijing.volces.com/api/v3");
        properties.getArk().setChatModel("doubao-seed-1-6");

        AiProviderCatalog catalog = new AiProviderCatalog(properties);

        assertThat(catalog.defaultProvider().name()).isEqualTo("openai");
        assertThat(catalog.enabledProviders())
                .extracting(AiProviderCatalog.Provider::name)
                .containsExactly("openai", "deepseek", "ark");
        assertThat(catalog.provider("ark").orElseThrow().baseUrl())
                .isEqualTo("https://ark.cn-beijing.volces.com/api/v3");
    }

    @Test
    void disablesProviderWhenApiKeyIsBlank() {
        AiProviderProperties properties = new AiProviderProperties();
        properties.getOpenai().setApiKey("openai-key");
        properties.getDeepseek().setApiKey("");
        properties.getArk().setApiKey("   ");

        AiProviderCatalog catalog = new AiProviderCatalog(properties);

        assertThat(catalog.enabledProviders())
                .extracting(AiProviderCatalog.Provider::name)
                .containsExactly("openai");
    }
}
