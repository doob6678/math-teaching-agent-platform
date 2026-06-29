package com.doob.mathagent.infrastructure.ai;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class AiProviderCatalogTest {

    @Test
    void buildsDashScopeOpenAiDeepSeekAndArkProvidersFromEnvironmentBackedProperties() {
        AiProviderProperties properties = new AiProviderProperties();
        properties.setDefaultProvider("dashscope");
        properties.getDashscope().setApiKey("dashscope-key");
        properties.getDashscope().setBaseUrl("https://dashscope.aliyuncs.com/compatible-mode/v1");
        properties.getDashscope().setChatModel("qwen3.6-flash");
        properties.getOpenai().setApiKey("openai-key");
        properties.getOpenai().setBaseUrl("https://api.openai.com");
        properties.getOpenai().setChatModel("gpt-5.4");
        properties.getDeepseek().setApiKey("deepseek-key");
        properties.getDeepseek().setBaseUrl("https://api.deepseek.com");
        properties.getDeepseek().setChatModel("deepseek-v4-flash");
        properties.getArk().setApiKey("ark-key");
        properties.getArk().setBaseUrl("https://ark.cn-beijing.volces.com/api/v3");
        properties.getArk().setChatModel("doubao-seed-2-0-lite-260428");

        AiProviderCatalog catalog = new AiProviderCatalog(properties);

        assertThat(catalog.defaultProvider().name()).isEqualTo("dashscope");
        assertThat(catalog.enabledProviders())
                .extracting(AiProviderCatalog.Provider::name)
                .containsExactly("dashscope", "openai", "deepseek", "ark");
        assertThat(catalog.provider("ark").orElseThrow().baseUrl())
                .isEqualTo("https://ark.cn-beijing.volces.com/api/v3");
    }

    @Test
    void disablesProviderWhenApiKeyIsBlank() {
        AiProviderProperties properties = new AiProviderProperties();
        properties.getDashscope().setApiKey("");
        properties.getOpenai().setApiKey("openai-key");
        properties.getDeepseek().setApiKey("");
        properties.getArk().setApiKey("   ");

        AiProviderCatalog catalog = new AiProviderCatalog(properties);

        assertThat(catalog.enabledProviders())
                .extracting(AiProviderCatalog.Provider::name)
                .containsExactly("openai");
    }
}
