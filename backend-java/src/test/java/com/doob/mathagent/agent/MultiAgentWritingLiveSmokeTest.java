package com.doob.mathagent.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.doob.mathagent.agent.dto.MultiAgentWritingRequest;
import com.doob.mathagent.agent.service.AgentRunExecutionService;
import com.doob.mathagent.agent.service.AgentRunPlanService;
import com.doob.mathagent.agent.service.AgentTraceRecord;
import com.doob.mathagent.agent.service.InMemoryAgentConcurrencyGuard;
import com.doob.mathagent.agent.service.InMemoryAgentTraceStore;
import com.doob.mathagent.agent.service.InMemoryMultiAgentWritingWorkflowStore;
import com.doob.mathagent.agent.service.MultiAgentWritingService;
import com.doob.mathagent.agent.service.SpringAiOpenAiCompatibleGateway;
import com.doob.mathagent.agent.vo.MultiAgentWritingResponse;
import com.doob.mathagent.infrastructure.ai.AiProviderCatalog;
import com.doob.mathagent.infrastructure.ai.AiProviderProperties;
import com.doob.mathagent.infrastructure.security.RequestSubject;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Opt-in live smoke test for the full multi-agent writing workflow.
 */
class MultiAgentWritingLiveSmokeTest {

    @Test
    void liveMultiAgentWritingRunsThreeRealModelStagesWhenEnabled() {
        assumeTrue(Boolean.getBoolean("math-agent.ai.multi-agent-live-smoke"), "Multi-agent live smoke test is opt-in");
        AiProviderProperties properties = propertiesFromEnvironment();
        AiProviderCatalog catalog = new AiProviderCatalog(properties);
        assumeTrue(!catalog.enabledProviders().isEmpty(), "No live AI provider credentials");
        AiProviderCatalog.Provider provider = catalog.defaultProvider();
        InMemoryAgentTraceStore traceStore = new InMemoryAgentTraceStore();
        MultiAgentWritingService service = new MultiAgentWritingService(
                new AgentRunPlanService(catalog),
                new AgentRunExecutionService(
                        traceStore,
                        new InMemoryAgentConcurrencyGuard(),
                        new SpringAiOpenAiCompatibleGateway(properties),
                        catalog,
                        java.time.Clock.systemUTC()),
                new InMemoryMultiAgentWritingWorkflowStore(),
                new org.springframework.core.task.SyncTaskExecutor());

        MultiAgentWritingResponse response = service.run(
                new MultiAgentWritingRequest(
                        "Create a concise teacher handout outline.",
                        "space vector angle between a line and a plane",
                        List.of("PUBLIC_TEXTBOOK:space-vector:angle"),
                        false,
                        provider.name(),
                        provider.chatModel()),
                new RequestSubject("school-a", "teacher", "teacher-live", "device-live"));

        assertThat(response.status()).isEqualTo("COMPLETED");
        assertThat(response.stages()).hasSize(3);
        assertThat(response.stages()).extracting(MultiAgentWritingResponse.StageResult::stageCode)
                .containsExactly("draft", "review", "format");
        assertThat(response.totalUsage().totalTokens()).isGreaterThan(0);
        assertThat(response.stages()).allSatisfy(stage -> {
            assertThat(stage.traceId()).isNotBlank();
            assertThat(stage.providerName()).isNotBlank();
            assertThat(stage.modelCode()).isNotBlank();
            assertThat(stage.actualUsage().totalTokens()).isGreaterThan(0);
            List<String> eventTypes = traceStore.find(stage.traceId()).orElseThrow().diagnosticEvents().stream()
                    .map(AgentTraceRecord.DiagnosticEvent::eventType)
                    .toList();
            assertThat(eventTypes).contains("MODEL_CALL_SUCCEEDED");
            if (!stage.providerName().equals(provider.name()) || !stage.modelCode().equals(provider.chatModel())) {
                assertThat(eventTypes).contains("PROVIDER_ROTATED");
            }
        });
    }

    /**
     * Builds provider properties from environment without logging secret values.
     */
    private static AiProviderProperties propertiesFromEnvironment() {
        AiProviderProperties properties = new AiProviderProperties();
        properties.setDefaultProvider(envOrDefault("MATH_AGENT_AI_DEFAULT_PROVIDER", "openai"));
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
