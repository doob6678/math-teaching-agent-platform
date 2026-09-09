package com.doob.mathagent.infrastructure.ai;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * 视觉能力路由的目录层测试（2026-09-06）。
 *
 * <p>背景：学生对话"原图直接进入 AI 上下文"，deepseek/glm 实测会静默丢弃 image_url 块，
 * 因此带图请求必须能从目录层拿到视觉默认路由；视觉白名单只收录纯色图探针实测通过的模型。</p>
 */
class AiProviderCatalogVisionTest {

    @Test
    void onlyProbeVerifiedModelsAreVisionCapable() {
        assertThat(AiProviderCatalog.supportsVision("gpt-5.6-luna")).isTrue();
        assertThat(AiProviderCatalog.supportsVision("gpt-5.6-terra")).isTrue();
        assertThat(AiProviderCatalog.supportsVision("gpt-5.6-sol")).isTrue();
        assertThat(AiProviderCatalog.supportsVision("gpt-5.5")).isTrue();
        // 探针实测这些模型收到图片返回空文本，绝不能进入视觉集合。
        assertThat(AiProviderCatalog.supportsVision("deepseek-v4-flash")).isFalse();
        assertThat(AiProviderCatalog.supportsVision("deepseek-v4-pro")).isFalse();
        assertThat(AiProviderCatalog.supportsVision("qwen3.6-flash")).isFalse();
        // glm-5.3-flash 第二轮探针直发 Anthropic 原生 image 块实测看图成功（第一轮失败是桥接未转换）。
        assertThat(AiProviderCatalog.supportsVision("glm-5.3-flash")).isTrue();
        assertThat(AiProviderCatalog.supportsVision(null)).isFalse();
    }

    @Test
    void visionDefaultPicksFirstEnabledVisionProviderEvenWhenDefaultIsTextOnly() {
        AiProviderCatalog catalog = catalog("deepseek", "gpt-5.6-terra", true);
        AiProviderCatalog.Provider visionDefault = catalog.visionDefaultProvider().orElseThrow();
        assertThat(visionDefault.name()).isEqualTo("openai");
        assertThat(visionDefault.chatModel()).isEqualTo("gpt-5.6-terra");
    }

    @Test
    void visionDefaultPromotesVisionModelWhenProviderDefaultIsTextOnly() {
        // openai 部署默认模型不支持视觉时，视觉默认提升到允许列表内第一个已验证视觉模型。
        AiProviderCatalog catalog = new AiProviderCatalog(properties("deepseek", "gpt-5.4", true));
        AiProviderCatalog.Provider visionDefault = catalog.visionDefaultProvider().orElseThrow();
        assertThat(visionDefault.name()).isEqualTo("openai");
        assertThat(visionDefault.chatModel()).isEqualTo("gpt-5.6-luna");
    }

    @Test
    void visionDefaultIsEmptyWhenNoEnabledProviderSupportsVision() {
        AiProviderProperties properties = properties("deepseek", "gpt-5.6-terra", false);
        // glm-5.3-flash 已实测支持视觉，构造"无视觉"场景必须连它也禁用。
        properties.getGlm().setEnabled(false);
        assertThat(new AiProviderCatalog(properties).visionDefaultProvider()).isEmpty();
    }

    @Test
    void visionRoutesListEveryVerifiedModelPerEnabledProvider() {
        AiProviderCatalog catalog = catalog("deepseek", "gpt-5.6-terra", true);
        assertThat(catalog.visionRoutes()).extracting(
                        AiProviderCatalog.Provider::name, AiProviderCatalog.Provider::chatModel)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("openai", "gpt-5.6-terra"),
                        org.assertj.core.groups.Tuple.tuple("openai", "gpt-5.6-luna"),
                        org.assertj.core.groups.Tuple.tuple("openai", "gpt-5.6-sol"),
                        org.assertj.core.groups.Tuple.tuple("openai", "gpt-5.5"),
                        org.assertj.core.groups.Tuple.tuple("glm", "glm-5.3-flash"));
    }

    @Test
    void modelCatalogExposesVisionFlagsAndVisionDefault() {
        AiProviderCatalog catalog = catalog("deepseek", "gpt-5.6-terra", true);
        AiProviderCatalog.ModelCatalog modelCatalog = catalog.modelCatalog();
        assertThat(modelCatalog.visionDefaultProviderName()).isEqualTo("openai");
        assertThat(modelCatalog.visionDefaultModelCode()).isEqualTo("gpt-5.6-terra");
        assertThat(modelCatalog.providers().stream()
                .flatMap(provider -> provider.models().stream())
                .filter(AiProviderCatalog.ModelOption::vision)
                .map(AiProviderCatalog.ModelOption::modelCode))
                .contains("gpt-5.6-luna", "gpt-5.6-terra");
        assertThat(modelCatalog.providers().stream()
                .flatMap(provider -> provider.models().stream())
                .filter(option -> option.modelCode().startsWith("deepseek")))
                .allMatch(option -> !option.vision());
    }

    private static AiProviderCatalog catalog(String defaultProvider, String openaiChatModel, boolean openaiEnabled) {
        return new AiProviderCatalog(properties(defaultProvider, openaiChatModel, openaiEnabled));
    }

    private static AiProviderProperties properties(String defaultProvider, String openaiChatModel, boolean openaiEnabled) {
        AiProviderProperties properties = new AiProviderProperties();
        properties.setDefaultProvider(defaultProvider);
        properties.getDeepseek().setName("deepseek");
        properties.getDeepseek().setEnabled(true);
        properties.getDeepseek().setChatModel("deepseek-v4-flash");
        properties.getOpenai().setName("openai");
        properties.getOpenai().setEnabled(openaiEnabled);
        properties.getOpenai().setChatModel(openaiChatModel);
        properties.getGlm().setName("glm");
        properties.getGlm().setEnabled(true);
        properties.getGlm().setChatModel("glm-5.3-flash");
        return properties;
    }
}
