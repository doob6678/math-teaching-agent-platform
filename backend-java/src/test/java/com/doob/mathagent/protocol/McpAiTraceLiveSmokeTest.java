package com.doob.mathagent.protocol;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.doob.mathagent.agent.dto.AgentRunExecuteRequest;
import com.doob.mathagent.agent.dto.AgentRunPlanRequest;
import com.doob.mathagent.agent.service.AgentRunExecutionService;
import com.doob.mathagent.agent.service.AgentRunPlanService;
import com.doob.mathagent.agent.service.AgentTraceQueryService;
import com.doob.mathagent.agent.service.InMemoryAgentConcurrencyGuard;
import com.doob.mathagent.agent.service.InMemoryAgentTraceStore;
import com.doob.mathagent.agent.service.SpringAiOpenAiCompatibleGateway;
import com.doob.mathagent.agent.vo.AgentRunExecuteResponse;
import com.doob.mathagent.agent.vo.AgentRunPlanResponse;
import com.doob.mathagent.infrastructure.ai.AiProviderCatalog;
import com.doob.mathagent.infrastructure.ai.AiProviderProperties;
import com.doob.mathagent.infrastructure.security.RequestSubject;
import com.doob.mathagent.protocol.controller.McpJsonRpcController;
import com.doob.mathagent.protocol.service.McpClientRegistryProperties;
import com.doob.mathagent.protocol.service.McpJsonRpcService;
import com.doob.mathagent.protocol.service.McpToolExecutionService;
import com.doob.mathagent.protocol.service.ProtocolDiscoveryService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * Opt-in live smoke test proving real AI execution traces can be read back through MCP.
 */
class McpAiTraceLiveSmokeTest {

    private static final String LIVE_SMOKE_FLAG = "math-agent.ai.live-smoke";
    private static final String MCP_SECRET = "teacher_secret_1234567890abcdef";

    @Test
    void liveAiExecutionTraceCanBeReadThroughStandardJsonRpcMcpWhenEnabled() {
        assumeTrue(Boolean.getBoolean(LIVE_SMOKE_FLAG), "Live AI smoke test is opt-in");
        AiProviderProperties properties = propertiesFromEnvironment();
        AiProviderCatalog catalog = new AiProviderCatalog(properties);
        assumeTrue(!catalog.enabledProviders().isEmpty(), "No live AI provider credentials");
        AiProviderCatalog.Provider provider = catalog.defaultProvider();
        InMemoryAgentTraceStore traceStore = new InMemoryAgentTraceStore();
        AgentRunPlanResponse plan = plan(provider, catalog);
        AgentRunExecutionService executionService = new AgentRunExecutionService(
                traceStore,
                new InMemoryAgentConcurrencyGuard(),
                new SpringAiOpenAiCompatibleGateway(properties),
                catalog,
                java.time.Clock.systemUTC());

        AgentRunExecuteResponse executeResponse = executionService.execute(
                new AgentRunExecuteRequest(
                        plan,
                        "生成一句高中数学教师讲义说明：空间向量夹角用数量积公式。",
                        List.of("PUBLIC_TEXTBOOK:space-vector:angle"),
                        false),
                new RequestSubject("school-a", "teacher", "teacher-live-mcp", "device-live"));
        McpClientRegistryProperties mcpRegistry = mcpRegistry();
        McpToolExecutionService mcpToolExecutionService = McpToolExecutionServiceFixture.service(
                mcpRegistry,
                null,
                null,
                null,
                new AgentTraceQueryService(traceStore),
                null);
        McpJsonRpcController mcpController = new McpJsonRpcController(McpJsonRpcServiceFixture.service(
                new ProtocolDiscoveryService(mcpRegistry),
                mcpToolExecutionService,
                mcpRegistry));

        var mcpResponse = mcpController.post(
                "Bearer " + MCP_SECRET,
                "application/json, text/event-stream",
                "2025-11-25",
                """
                        {"jsonrpc":"2.0","id":"live-trace","method":"tools/call","params":{"name":"get_teaching_ai_trace","arguments":{"taskId":"%s"}}}
                        """.formatted(plan.planId()),
                localRequest());

        assertThat(executeResponse.actualUsage().totalTokens()).isGreaterThan(0);
        assertThat(mcpResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) mcpResponse.getBody();
        assertThat(body).containsEntry("jsonrpc", "2.0");
        assertThat(body).doesNotContainKey("error");
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) body.get("result");
        assertThat(result).containsEntry("isError", false);
        assertThat(result.get("content").toString()).contains("totalTokens", executeResponse.traceId());
        @SuppressWarnings("unchecked")
        Map<String, Object> trace = (Map<String, Object>) result.get("structuredContent");
        assertThat(trace.get("taskId")).isEqualTo(plan.planId());
        assertThat(trace.get("providerName")).isEqualTo(executeResponse.providerName());
        assertThat(trace.get("modelCode")).isEqualTo(executeResponse.modelCode());
        assertThat(trace.get("actualUsage").toString()).contains("totalTokens");
        assertThat(trace.get("diagnosticEvents").toString()).contains("MODEL_CALL_SUCCEEDED");
        assertThat(trace.toString()).doesNotContain(MCP_SECRET, properties.getOpenai().getApiKey());
        System.out.println("live-ai-mcp-trace="
                + "provider=" + executeResponse.providerName()
                + ",model=" + executeResponse.modelCode()
                + ",tokens=" + executeResponse.actualUsage().totalTokens()
                + ",traceId=" + executeResponse.traceId());
    }

    /**
     * Builds a local request accepted by the MCP Origin guard.
     */
    private static MockHttpServletRequest localRequest() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setServerName("127.0.0.1");
        return request;
    }

    /**
     * Builds one CoursewareAgent plan using the current default live provider.
     */
    private static AgentRunPlanResponse plan(
            AiProviderCatalog.Provider provider,
            AiProviderCatalog catalog) {
        return new AgentRunPlanService(catalog).plan(
                new AgentRunPlanRequest(
                        "CoursewareAgent",
                        "courseware_generation",
                        "teacher",
                        220,
                        100,
                        false,
                        true,
                        "medium",
                        "normal",
                        2.5d,
                        0,
                        false,
                        List.of("tool:courseware:generate", "tool:search:textbook"),
                        List.of(),
                        List.of("PUBLIC_TEXTBOOK"),
                        true,
                        provider.name(),
                        provider.chatModel()),
                new RequestSubject("school-a", "teacher", "teacher-live-mcp", "device-live"));
    }

    /**
     * Creates an MCP registry where one WorkBuddy key may read only owned teaching traces.
     */
    private static McpClientRegistryProperties mcpRegistry() {
        McpClientRegistryProperties properties = new McpClientRegistryProperties();
        properties.setClients(List.of(new McpClientRegistryProperties.Client(
                "workbuddy-live-trace",
                "teacher",
                "school-a",
                "teacher-live-mcp",
                McpClientRegistryProperties.secretHash(MCP_SECRET),
                true,
                List.of("get_teaching_ai_trace"),
                List.of("agent-trace:read"))));
        return properties;
    }

    /**
     * Builds provider properties from process, user, or machine environment variables without logging secrets.
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
     * Reads an environment variable from process, user, or machine scopes.
     */
    private static String env(String name) {
        String processValue = System.getenv(name);
        if (hasText(processValue)) {
            return processValue.strip();
        }
        String userValue = readScopedEnvironment(name, "User");
        if (hasText(userValue)) {
            return userValue.strip();
        }
        String machineValue = readScopedEnvironment(name, "Machine");
        return hasText(machineValue) ? machineValue.strip() : "";
    }

    /**
     * Returns an environment variable or fallback value.
     */
    private static String envOrDefault(String name, String fallback) {
        String value = env(name);
        return value.isBlank() ? fallback : value;
    }

    /**
     * Reads one Windows environment scope through PowerShell.
     */
    private static String readScopedEnvironment(String name, String scope) {
        try {
            Process process = new ProcessBuilder(
                            "powershell",
                            "-NoProfile",
                            "-Command",
                            "[Environment]::GetEnvironmentVariable('" + name + "', '" + scope + "')")
                    .redirectErrorStream(true)
                    .start();
            boolean finished = process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
            if (!finished || process.exitValue() != 0) {
                process.destroyForcibly();
                return "";
            }
            return new String(process.getInputStream().readAllBytes(), java.nio.charset.Charset.defaultCharset())
                    .strip();
        } catch (Exception ignored) {
            return "";
        }
    }

    /**
     * Returns whether a string contains non-whitespace text.
     */
    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
