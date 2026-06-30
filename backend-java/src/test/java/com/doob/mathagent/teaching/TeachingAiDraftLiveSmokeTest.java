package com.doob.mathagent.teaching;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.doob.mathagent.agent.service.SpringAiOpenAiCompatibleGateway;
import com.doob.mathagent.infrastructure.ai.AiProviderCatalog;
import com.doob.mathagent.infrastructure.ai.AiProviderProperties;
import com.doob.mathagent.memory.vo.StudentMemoryResponse;
import com.doob.mathagent.teaching.dto.TeachingTaskRequest;
import com.doob.mathagent.teaching.service.TeachingAiDraftService;
import com.doob.mathagent.teaching.vo.TeachingTaskResponse;
import java.util.List;
import org.junit.jupiter.api.Test;

class TeachingAiDraftLiveSmokeTest {

    @Test
    void liveProviderGeneratesTeachingDraftContentWhenEnabled() {
        assumeTrue(Boolean.getBoolean("math-agent.ai.live-smoke"), "Live AI smoke test is opt-in");
        AiProviderProperties properties = propertiesFromEnvironment();
        assumeTrue(new AiProviderCatalog(properties).enabledProviders().size() > 0, "No live AI provider credentials");
        TeachingAiDraftService service = new TeachingAiDraftService(
                new SpringAiOpenAiCompatibleGateway(properties),
                new AiProviderCatalog(properties));

        TeachingTaskResponse.AiDraft draft = service.draft(
                new TeachingTaskRequest(
                        "live-ai-draft",
                        "已知函数 f(x) 的定义域为 R，求 D(-1)",
                        "理解函数新定义题的解题入口",
                        2),
                List.of(new TeachingEvidence(
                        "PUBLIC_TEXTBOOK",
                        "教材A / 函数新概念",
                        "book-a-p101",
                        101,
                        "函数新定义题要先读清集合 D(x0) 的条件，再代入具体 x0。")),
                new StudentMemoryResponse(false, "", "", "", 0.0, "No reusable memory", List.of()));

        assertThat(draft.enabled()).isTrue();
        assertThat(draft.providerName()).isNotBlank();
        assertThat(draft.modelCode()).isNotBlank();
        assertThat(draft.totalTokens()).isGreaterThan(0);
        assertThat(draft.content()).isNotBlank();
        assertThat(draft.structured()).isTrue();
        assertThat(draft.teacherExplanation()).containsAnyOf("函数", "D(-1)", "定义");
        assertThat(draft.studentHint()).isNotBlank();
        assertThat(draft.knowledgePoints()).isNotEmpty();
        assertThat(draft.followUpQuestions()).isNotEmpty();
        assertThat(draft.retryCount()).isLessThanOrEqualTo(draft.maxRetries());
    }

    /**
     * Builds provider properties from environment without logging secret values.
     */
    private static AiProviderProperties propertiesFromEnvironment() {
        AiProviderProperties properties = new AiProviderProperties();
        properties.setDefaultProvider("openai");
        properties.getOpenai().setApiKey(env("OPENAI_API_KEY"));
        properties.getOpenai().setBaseUrl(envOrDefault("OPENAI_BASE_URL", "https://api.openai.com"));
        properties.getOpenai().setChatModel(envOrDefault("OPENAI_CHAT_MODEL", "gpt-5.4"));
        properties.getDashscope().setApiKey(env("DASHSCOPE_API_KEY"));
        properties.getDashscope().setBaseUrl(envOrDefault("DASHSCOPE_BASE_URL", "https://dashscope.aliyuncs.com/compatible-mode/v1"));
        properties.getDashscope().setChatModel(envOrDefault("DASHSCOPE_CHAT_MODEL", "qwen3.6-flash"));
        properties.getDeepseek().setApiKey(env("DEEPSEEK_API_KEY"));
        properties.getDeepseek().setBaseUrl(envOrDefault("DEEPSEEK_BASE_URL", "https://api.deepseek.com"));
        properties.getDeepseek().setChatModel(envOrDefault("DEEPSEEK_CHAT_MODEL", "deepseek-v4-flash"));
        properties.getArk().setApiKey(env("ARK_API_KEY"));
        properties.getArk().setBaseUrl(envOrDefault("ARK_BASE_URL", "https://ark.cn-beijing.volces.com/api/v3"));
        properties.getArk().setChatModel(envOrDefault("ARK_CHAT_MODEL", "doubao-seed-2-0-lite-260428"));
        return properties;
    }

    /**
     * Returns an environment variable or an empty string.
     */
    private static String env(String name) {
        String value = System.getenv(name);
        return value == null ? "" : value.strip();
    }

    /**
     * Returns an environment variable or fallback value.
     */
    private static String envOrDefault(String name, String fallback) {
        String value = env(name);
        return value.isBlank() ? fallback : value;
    }
}
