package com.doob.mathagent.infrastructure.ai;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class AiProviderCatalogTest {

    @Test
    void buildsDashScopeOpenAiDeepSeekAndArkProvidersFromEnvironmentBackedProperties() {
        AiProviderProperties properties = new AiProviderProperties();
        properties.setDefaultProvider("openai");
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

        assertThat(catalog.defaultProvider().name()).isEqualTo("openai");
        assertThat(catalog.enabledProviders())
                .extracting(AiProviderCatalog.Provider::name)
                .containsExactly("openai", "dashscope", "deepseek", "ark");
        assertThat(catalog.provider("ark").orElseThrow().baseUrl())
                .isEqualTo("https://ark.cn-beijing.volces.com/api/v3");
    }

    @Test
    void exposesEnabledModelCatalogWithoutSecretsAndKeepsOpenAiGpt54Default() {
        AiProviderProperties properties = new AiProviderProperties();
        properties.setDefaultProvider("openai");
        properties.getDashscope().setApiKey("dashscope-key");
        properties.getDashscope().setChatModel("qwen3.6-flash");
        properties.getOpenai().setApiKey("openai-key");
        properties.getOpenai().setChatModel("gpt-5.4");
        properties.getDeepseek().setApiKey("deepseek-key");
        properties.getDeepseek().setChatModel("deepseek-v4-flash");
        properties.getArk().setApiKey("ark-key");
        properties.getArk().setChatModel("doubao-seed-2-0-lite-260428");

        AiProviderCatalog catalog = new AiProviderCatalog(properties);

        assertThat(catalog.modelCatalog().defaultProviderName()).isEqualTo("openai");
        assertThat(catalog.modelCatalog().defaultModelCode()).isEqualTo("gpt-5.4");
        assertThat(catalog.modelCatalog().fallbackProviderOrder())
                .containsExactly("openai", "dashscope", "deepseek", "ark");
        assertThat(catalog.modelCatalog().providers())
                .extracting(AiProviderCatalog.ModelProvider::name)
                .containsExactly("openai", "dashscope", "deepseek", "ark");
        assertThat(catalog.modelCatalog().providers().getFirst().models())
                .extracting(AiProviderCatalog.ModelOption::modelCode)
                .contains("gpt-5.4", "gpt-5.4-mini", "gpt-5.4-nano");
        assertThat(catalog.modelCatalog().providers().get(1).models())
                .extracting(AiProviderCatalog.ModelOption::modelCode)
                .contains("qwen3.6-flash", "qwen3.7-plus", "qwen3.7-max");
        assertThat(catalog.modelCatalog().providers().get(2).models())
                .extracting(AiProviderCatalog.ModelOption::modelCode)
                .contains("deepseek-v4-flash", "deepseek-v4-pro");
        assertThat(catalog.modelCatalog().providers().get(3).models())
                .extracting(AiProviderCatalog.ModelOption::modelCode)
                .contains("doubao-seed-2-0-lite-260428", "doubao-seed-2.0-mini");
        assertThat(catalog.modelCatalog().toString()).doesNotContain("openai-key", "dashscope-key");
    }

    @Test
    void keepsConfiguredDefaultProviderFirstInRuntimeFallbackOrder() {
        AiProviderProperties properties = new AiProviderProperties();
        properties.setDefaultProvider("deepseek");
        properties.getDashscope().setApiKey("dashscope-key");
        properties.getDashscope().setChatModel("qwen3.6-flash");
        properties.getOpenai().setApiKey("openai-key");
        properties.getOpenai().setChatModel("gpt-5.4");
        properties.getDeepseek().setApiKey("deepseek-key");
        properties.getDeepseek().setChatModel("deepseek-v4-flash");
        properties.getArk().setApiKey("ark-key");
        properties.getArk().setChatModel("doubao-seed-2-0-lite-260428");

        AiProviderCatalog catalog = new AiProviderCatalog(properties);

        assertThat(catalog.defaultProvider().name()).isEqualTo("deepseek");
        assertThat(catalog.enabledProviders())
                .extracting(AiProviderCatalog.Provider::name)
                .containsExactly("deepseek", "openai", "dashscope", "ark");
        assertThat(catalog.modelCatalog().fallbackProviderOrder())
                .containsExactly("deepseek", "openai", "dashscope", "ark");
        assertThat(catalog.modelCatalog().providers())
                .extracting(AiProviderCatalog.ModelProvider::name)
                .containsExactly("deepseek", "openai", "dashscope", "ark");
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
