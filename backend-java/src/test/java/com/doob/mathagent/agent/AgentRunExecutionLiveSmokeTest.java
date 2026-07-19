package com.doob.mathagent.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.doob.mathagent.agent.dto.AgentRunExecuteRequest;
import com.doob.mathagent.agent.dto.AgentRunPlanRequest;
import com.doob.mathagent.agent.service.AgentRunExecutionService;
import com.doob.mathagent.agent.service.AgentRunPlanService;
import com.doob.mathagent.agent.service.InMemoryAgentConcurrencyGuard;
import com.doob.mathagent.agent.service.InMemoryAgentTraceStore;
import com.doob.mathagent.agent.service.SpringAiOpenAiCompatibleGateway;
import com.doob.mathagent.agent.vo.AgentRunExecuteResponse;
import com.doob.mathagent.agent.vo.AgentRunPlanResponse;
import com.doob.mathagent.infrastructure.ai.AiProviderCatalog;
import com.doob.mathagent.infrastructure.ai.AiProviderProperties;
import com.doob.mathagent.infrastructure.security.RequestSubject;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Opt-in live smoke test for the generic AgentRun execution path.
 */
class AgentRunExecutionLiveSmokeTest {

    @Test
    void liveAgentRunRecordsModelDiagnosticsWhenEnabled() {
        assumeTrue(Boolean.getBoolean("math-agent.ai.live-smoke"), "Live AI smoke test is opt-in");
        AiProviderProperties properties = propertiesFromEnvironment();
        AiProviderCatalog catalog = new AiProviderCatalog(properties);
        assumeTrue(!catalog.enabledProviders().isEmpty(), "No live AI provider credentials");
        AiProviderCatalog.Provider provider = catalog.defaultProvider();
        InMemoryAgentTraceStore traceStore = new InMemoryAgentTraceStore();
        AgentRunPlanResponse plan = new AgentRunPlanService(catalog).plan(
                new AgentRunPlanRequest(
                        "CoursewareAgent",
                        "courseware_generation",
                        "teacher",
                        240,
                        120,
                        false,
                        true,
                        "medium",
                        "normal",
                        2.5,
                        0,
                        false,
                        List.of("tool:courseware:generate", "tool:search:textbook"),
                        List.of(),
                        List.of("PUBLIC_TEXTBOOK"),
                        true,
                        provider.name(),
                        provider.chatModel()),
                new RequestSubject("school-a", "teacher", "teacher-live", "device-live"));
        AgentRunExecutionService service = new AgentRunExecutionService(
                traceStore,
                new InMemoryAgentConcurrencyGuard(),
                new SpringAiOpenAiCompatibleGateway(properties),
                catalog,
                java.time.Clock.systemUTC());

        AgentRunExecuteResponse response = service.execute(
                new AgentRunExecuteRequest(
                        plan,
                        "Generate one concise teacher note about space vector angle.",
                        List.of("PUBLIC_TEXTBOOK:space-vector:angle"),
                        false),
                new RequestSubject("school-a", "teacher", "teacher-live", "device-live"));

        assertThat(response.providerName()).isNotBlank();
        assertThat(response.modelCode()).isNotBlank();
        assertThat(response.actualUsage().totalTokens()).isGreaterThan(0);
        assertThat(response.message()).contains("Live model response recorded");
        assertThat(traceStore.find(response.traceId()).orElseThrow().diagnosticEvents())
                .extracting(com.doob.mathagent.agent.service.AgentTraceRecord.DiagnosticEvent::eventType)
                .contains("MODEL_CALL_SUCCEEDED");
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
