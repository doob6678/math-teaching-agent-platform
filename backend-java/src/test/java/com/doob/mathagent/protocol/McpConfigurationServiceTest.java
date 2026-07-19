package com.doob.mathagent.protocol;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.doob.mathagent.protocol.service.McpAccessPolicy;
import com.doob.mathagent.protocol.service.McpClientRegistryProperties;
import com.doob.mathagent.protocol.service.ProtocolDiscoveryService;
import com.doob.mathagent.protocol.vo.McpConfigurationResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

class McpConfigurationServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void rendersCopyableMcpConfigurationWithoutEchoingSecretKey() throws Exception {
        ProtocolDiscoveryService service = new ProtocolDiscoveryService();

        McpConfigurationResponse response = service.mcpConfiguration(
                teacherClient("workbuddy-teacher"),
                "https://math.example.com/api/mcp",
                "MATH_AGENT_MCP_SECRET",
                "mcp_...cdef");

        String json = objectMapper.writeValueAsString(response);

        assertThat(response.serverName()).isEqualTo("math-agent-rag");
        assertThat(response.valid()).isTrue();
        assertThat(response.secretKeyAccepted()).isTrue();
        assertThat(response.secretKeyPreview()).isEqualTo("mcp_...cdef");
        assertThat(response.configJson()).contains("\"mcpServers\"");
        assertThat(response.configJson()).contains("\"math-agent-rag\"");
        assertThat(response.configJson()).contains("\"url\" : \"https://math.example.com/api/mcp\"");
        assertThat(response.configJson()).contains("\"Authorization\" : \"Bearer ${MATH_AGENT_MCP_SECRET}\"");
        assertThat(response.exposedTools()).containsExactlyElementsOf(McpAccessPolicy.toolsForProfile("teacher"));
        assertThat(response.exposedPrompts()).containsExactlyElementsOf(McpAccessPolicy.promptsForProfile("teacher"));
        assertThat(json).doesNotContain("teacher_secret_1234567890abcdef");
        assertThat(json).doesNotContain("C:\\");
        assertThat(json).doesNotContain("Users/doob");
    }

    @Test
    void derivesPromptAndToolExposureFromBackendOwnedProfile() {
        ProtocolDiscoveryService service = new ProtocolDiscoveryService();

        McpConfigurationResponse response = service.mcpConfiguration(
                studentClient("workbuddy-student"),
                "https://math.example.com/api/mcp",
                "MATH_AGENT_STUDENT_MCP_SECRET",
                "mcp_...1234");

        assertThat(response.keyProfile()).isEqualTo("student");
        assertThat(response.exposedTools()).containsExactlyElementsOf(McpAccessPolicy.toolsForProfile("student"));
        assertThat(response.exposedPrompts()).containsExactlyElementsOf(McpAccessPolicy.promptsForProfile("student"));
        assertThat(response.exposedTools()).doesNotContain("start_multi_agent_writing", "download_feishu_resource");
        assertThat(response.exposedPrompts()).doesNotContain("teacher_handout_writer");
    }

    @Test
    void rejectsUnsafeConfigurationInput() {
        ProtocolDiscoveryService service = new ProtocolDiscoveryService();

        assertThatThrownBy(() -> service.mcpConfiguration(
                teacherClient("workbuddy-teacher"),
                "file:///C:/Users/doob/secret",
                "MATH_AGENT_MCP_SECRET",
                "mcp_...cdef"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("MCP URL");

        assertThatThrownBy(() -> service.mcpConfiguration(
                teacherClient("workbuddy-teacher"),
                "https://math.example.com/api/mcp",
                "math-agent-secret",
                "mcp_...cdef"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("secretEnvName");
    }

    private static McpClientRegistryProperties.Client teacherClient(String clientId) {
        return new McpClientRegistryProperties.Client(
                clientId,
                "teacher",
                "school-a",
                clientId + "-subject",
                ProtocolDiscoveryService.secretHashForTest("teacher_secret_1234567890abcdef"),
                true,
                McpAccessPolicy.toolsForProfile("teacher"),
                McpAccessPolicy.scopesForProfile("teacher"));
    }

    private static McpClientRegistryProperties.Client studentClient(String clientId) {
        return new McpClientRegistryProperties.Client(
                clientId,
                "student",
                "school-a",
                clientId + "-subject",
                ProtocolDiscoveryService.secretHashForTest("student_secret_1234567890abcdef"),
                true,
                McpAccessPolicy.toolsForProfile("student"),
                McpAccessPolicy.scopesForProfile("student"));
    }
}
