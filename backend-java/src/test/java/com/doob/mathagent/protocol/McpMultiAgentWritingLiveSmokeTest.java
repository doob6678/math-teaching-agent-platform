package com.doob.mathagent.protocol;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.doob.mathagent.agent.service.AgentRunExecutionService;
import com.doob.mathagent.agent.service.AgentRunPlanService;
import com.doob.mathagent.agent.service.AgentTraceQueryService;
import com.doob.mathagent.agent.service.InMemoryAgentConcurrencyGuard;
import com.doob.mathagent.agent.service.InMemoryAgentTraceStore;
import com.doob.mathagent.agent.service.InMemoryMultiAgentWritingWorkflowStore;
import com.doob.mathagent.agent.service.MultiAgentWritingArtifactExportService;
import com.doob.mathagent.agent.service.MultiAgentWritingService;
import com.doob.mathagent.agent.service.SpringAiOpenAiCompatibleGateway;
import com.doob.mathagent.infrastructure.ai.AiProviderCatalog;
import com.doob.mathagent.infrastructure.ai.AiProviderProperties;
import com.doob.mathagent.protocol.controller.McpJsonRpcController;
import com.doob.mathagent.protocol.service.McpClientRegistryProperties;
import com.doob.mathagent.protocol.service.McpJsonRpcService;
import com.doob.mathagent.protocol.service.McpToolExecutionService;
import com.doob.mathagent.protocol.service.ProtocolDiscoveryService;
import java.nio.charset.Charset;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * Opt-in live smoke test proving WorkBuddy-style MCP can start and recover a real multi-agent writing workflow.
 */
class McpMultiAgentWritingLiveSmokeTest {

    private static final String LIVE_SMOKE_FLAG = "math-agent.ai.live-smoke";
    private static final String MCP_SECRET = "teacher_secret_1234567890abcdef";
    private static final String AUTHORIZATION = "Bearer " + MCP_SECRET;
    private static final String ACCEPT = "application/json, text/event-stream";

    @Test
    void liveMultiAgentWritingCanRunThroughStandardJsonRpcMcpWhenEnabled() {
        assumeTrue(Boolean.getBoolean(LIVE_SMOKE_FLAG), "Live AI smoke test is opt-in");
        AiProviderProperties properties = propertiesFromEnvironment();
        AiProviderCatalog catalog = new AiProviderCatalog(properties);
        assumeTrue(!catalog.enabledProviders().isEmpty(), "No live AI provider credentials");
        AiProviderCatalog.Provider provider = catalog.defaultProvider();
        InMemoryAgentTraceStore traceStore = new InMemoryAgentTraceStore();
        McpClientRegistryProperties registry = mcpRegistry();
        MultiAgentWritingService writingService = new MultiAgentWritingService(
                new AgentRunPlanService(catalog),
                new AgentRunExecutionService(
                        traceStore,
                        new InMemoryAgentConcurrencyGuard(),
                        new SpringAiOpenAiCompatibleGateway(properties),
                        catalog,
                        Clock.systemUTC()),
                new InMemoryMultiAgentWritingWorkflowStore(),
                new org.springframework.core.task.SyncTaskExecutor());
        MultiAgentWritingArtifactExportService exportService = new MultiAgentWritingArtifactExportService(
                writingService,
                Clock.fixed(Instant.parse("2026-07-01T00:00:00Z"), ZoneOffset.UTC),
                Duration.ofMinutes(10));
        McpToolExecutionService toolExecutionService = McpToolExecutionServiceFixture.service(
                registry,
                null,
                null,
                null,
                new AgentTraceQueryService(traceStore),
                null,
                null,
                null,
                null,
                null,
                writingService,
                exportService);
        McpJsonRpcController controller = new McpJsonRpcController(McpJsonRpcServiceFixture.service(
                new ProtocolDiscoveryService(registry),
                toolExecutionService,
                registry));

        Map<String, Object> started = structuredContent(controller, "live-start", "start_multi_agent_writing", Map.of(
                "writingGoal", "teacher handout",
                "questionText", "space vector angle by dot product",
                "evidenceRefs", List.of("PUBLIC_TEXTBOOK:space-vector:angle"),
                "preferredProviderName", provider.name(),
                "preferredModelCode", provider.chatModel()));
        String workflowId = started.get("workflowId").toString();
        Map<String, Object> status = structuredContent(controller, "live-status", "get_multi_agent_writing_status", Map.of(
                "workflowId", workflowId));
        Map<String, Object> artifact = structuredContent(controller, "live-artifact", "get_multi_agent_writing_artifact", Map.of(
                "workflowId", workflowId));

        assertThat(status.get("status")).isEqualTo("COMPLETED");
        assertThat(status.get("stageCount")).isEqualTo(3);
        assertThat(status.get("totalUsage").toString()).contains("totalTokens");
        assertThat(artifact.get("mergedMarkdown").toString()).isNotBlank();
        assertThat(artifact.toString()).doesNotContain(MCP_SECRET, properties.getOpenai().getApiKey());
        System.out.println("live-mcp-multi-agent-writing="
                + "provider=" + provider.name()
                + ",model=" + provider.chatModel()
                + ",workflowId=" + workflowId
                + ",usage=" + status.get("totalUsage"));
    }

    /**
     * Calls one standard MCP tool through JSON-RPC and returns its structured content.
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> structuredContent(
            McpJsonRpcController controller,
            String id,
            String toolName,
            Map<String, Object> arguments) {
        var response = controller.post(
                AUTHORIZATION,
                ACCEPT,
                "2025-11-25",
                """
                        {"jsonrpc":"2.0","id":"%s","method":"tools/call","params":{"name":"%s","arguments":%s}}
                        """.formatted(id, toolName, json(arguments)),
                localRequest());
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertThat(body).doesNotContainKey("error");
        Map<String, Object> result = (Map<String, Object>) body.get("result");
        assertThat(result).containsEntry("isError", false);
        return (Map<String, Object>) result.get("structuredContent");
    }

    /**
     * Serializes MCP tool arguments without hand-built JSON strings.
     */
    private static String json(Map<String, Object> value) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(value);
        } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
            throw new IllegalStateException("Failed to serialize MCP live smoke arguments", exception);
        }
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
     * Creates an MCP registry where one WorkBuddy key can execute and read writing workflows.
     */
    private static McpClientRegistryProperties mcpRegistry() {
        McpClientRegistryProperties properties = new McpClientRegistryProperties();
        properties.setClients(List.of(new McpClientRegistryProperties.Client(
                "workbuddy-live-writing",
                "teacher",
                "school-a",
                "teacher-live-writing",
                McpClientRegistryProperties.secretHash(MCP_SECRET),
                true,
                List.of(
                        "start_multi_agent_writing",
                        "get_multi_agent_writing_status",
                        "get_multi_agent_writing_artifact",
                        "export_multi_agent_writing_artifact"),
                List.of("agent-writing:execute", "agent-writing:read", "agent-writing:export"))));
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
            return new String(process.getInputStream().readAllBytes(), Charset.defaultCharset()).strip();
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
