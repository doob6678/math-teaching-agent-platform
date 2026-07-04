package com.doob.mathagent.protocol;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.doob.mathagent.protocol.dto.McpConfigurationRequest;
import com.doob.mathagent.protocol.service.McpClientRegistryProperties;
import com.doob.mathagent.protocol.service.ProtocolDiscoveryService;
import com.doob.mathagent.protocol.vo.McpConfigurationResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

class McpConfigurationServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void returnsCopyableMcpConfigurationWithoutEchoingSecretKey() throws Exception {
        ProtocolDiscoveryService service = serviceWithClient(
                "workbuddy-teacher",
                "teacher",
                "mcp_secret_1234567890abcdef");

        McpConfigurationResponse response = service.mcpConfiguration(new McpConfigurationRequest(
                "https://math.example.com/api/mcp",
                "mcp_secret_1234567890abcdef",
                "MATH_AGENT_MCP_SECRET",
                List.of(),
                List.of()));

        String json = objectMapper.writeValueAsString(response);

        assertThat(response.serverName()).isEqualTo("math-agent-rag");
        assertThat(response.valid()).isTrue();
        assertThat(response.secretKeyAccepted()).isTrue();
        assertThat(response.secretKeyPreview()).isEqualTo("mcp_...cdef");
        assertThat(response.configJson()).contains("\"mcpServers\"");
        assertThat(response.configJson()).contains("\"math-agent-rag\"");
        assertThat(response.configJson()).contains("\"url\" : \"https://math.example.com/api/mcp\"");
        assertThat(response.configJson()).contains("\"Authorization\" : \"Bearer ${MATH_AGENT_MCP_SECRET}\"");
        assertThat(response.exposedTools()).containsExactly(
                "search_textbook_evidence",
                "search_teacher_resource_evidence",
                "get_teaching_ai_trace",
                "get_ai_diagnostic_summary",
                "get_multi_agent_writing_trace",
                "plan_agent_run",
                "start_multi_agent_writing",
                "get_multi_agent_writing_status",
                "get_multi_agent_writing_artifact",
                "export_multi_agent_writing_artifact",
                "resume_multi_agent_writing",
                "discover_feishu_resources",
                "download_feishu_resource");
        assertThat(response.configJson()).doesNotContain("plan_agent_run");
        assertThat(response.configJson()).doesNotContain("create_teaching_task");
        assertThat(response.configJson()).doesNotContain("export_handout_pdf");
        assertThat(response.exposedPrompts()).contains("teacher_handout_writer", "student_blank_handout_writer");
        assertThat(json).doesNotContain("mcp_secret_1234567890abcdef");
        assertThat(json).doesNotContain("C:\\");
        assertThat(json).doesNotContain("Users/doob");
    }

    @Test
    void intersectsRequestedToolsAndPromptsWithBackendKeyProfile() {
        ProtocolDiscoveryService service = serviceWithClient(
                "workbuddy-student",
                "student",
                "student_secret_1234567890abcdef");

        McpConfigurationResponse response = service.mcpConfiguration(new McpConfigurationRequest(
                "https://math.example.com/api/mcp",
                "student_secret_1234567890abcdef",
                "MATH_AGENT_STUDENT_MCP_SECRET",
                List.of(
                        "search_textbook_evidence",
                        "search_teacher_resource_evidence",
                        "get_teaching_ai_trace",
                        "get_ai_diagnostic_summary",
                        "export_handout_pdf"),
                List.of("student_blank_handout_writer", "teacher_handout_writer")));

        assertThat(response.keyProfile()).isEqualTo("student");
        assertThat(response.exposedTools()).containsExactly(
                "search_textbook_evidence",
                "get_teaching_ai_trace",
                "get_ai_diagnostic_summary");
        assertThat(response.exposedPrompts()).containsExactly("student_blank_handout_writer");
        assertThat(response.configJson()).doesNotContain("\"tools\"");
        assertThat(response.configJson()).doesNotContain("search_textbook_evidence");
        assertThat(response.configJson()).doesNotContain("export_handout_pdf");
        assertThat(response.configJson()).doesNotContain("search_teacher_resource_evidence");
        assertThat(response.configJson()).doesNotContain("student_blank_handout_writer");
        assertThat(response.configJson()).doesNotContain("teacher_handout_writer");
    }

    @Test
    void exposesOnlyRealExecutableMcpToolsWhenRequested() {
        ProtocolDiscoveryService service = serviceWithClient(
                "workbuddy-teacher",
                "teacher",
                "mcp_secret_1234567890abcdef");

        McpConfigurationResponse response = service.mcpConfiguration(new McpConfigurationRequest(
                "https://math.example.com/api/mcp",
                "mcp_secret_1234567890abcdef",
                "MATH_AGENT_MCP_SECRET",
                List.of(
                        "search_textbook_evidence",
                        "search_teacher_resource_evidence",
                        "get_teaching_ai_trace",
                        "get_ai_diagnostic_summary",
                        "get_multi_agent_writing_trace",
                        "plan_agent_run",
                        "download_feishu_resource",
                        "create_teaching_task",
                        "export_handout_pdf",
                        "list_teacher_resources"),
                List.of("teacher_handout_writer")));

        assertThat(response.exposedTools()).containsExactly(
                "search_textbook_evidence",
                "search_teacher_resource_evidence",
                "get_teaching_ai_trace",
                "get_ai_diagnostic_summary",
                "get_multi_agent_writing_trace",
                "plan_agent_run",
                "download_feishu_resource");
        assertThat(response.configJson()).doesNotContain("search_teacher_resource_evidence");
        assertThat(response.configJson()).doesNotContain("get_teaching_ai_trace");
        assertThat(response.configJson()).doesNotContain("get_ai_diagnostic_summary");
        assertThat(response.configJson()).doesNotContain("get_multi_agent_writing_trace");
        assertThat(response.configJson()).doesNotContain("plan_agent_run");
        assertThat(response.configJson()).doesNotContain("create_teaching_task");
        assertThat(response.configJson()).doesNotContain("export_handout_pdf");
        assertThat(response.configJson()).doesNotContain("list_teacher_resources");
    }

    @Test
    void resolvesKeyProfileFromRegisteredSecretHashInsteadOfSecretPrefix() {
        McpClientRegistryProperties properties = new McpClientRegistryProperties();
        properties.setClients(List.of(new McpClientRegistryProperties.Client(
                "student-client-1",
                "student",
                "school-a",
                "student-mcp-client-1",
                ProtocolDiscoveryService.secretHashForTest("teacher_named_secret_1234567890"),
                true,
                List.of("search_textbook_evidence"),
                List.of("PUBLIC_TEXTBOOK"))));
        ProtocolDiscoveryService service = new ProtocolDiscoveryService(properties);

        McpConfigurationResponse response = service.mcpConfiguration(new McpConfigurationRequest(
                "https://math.example.com/api/mcp",
                "teacher_named_secret_1234567890",
                "MATH_AGENT_REGISTERED_MCP_SECRET",
                List.of("search_textbook_evidence", "export_handout_pdf"),
                List.of("student_blank_handout_writer", "teacher_handout_writer")));

        assertThat(response.keyProfile()).isEqualTo("student");
        assertThat(response.exposedTools()).containsExactly("search_textbook_evidence");
        assertThat(response.exposedPrompts()).containsExactly("student_blank_handout_writer");
        assertThat(response.configJson()).doesNotContain("teacher_named_secret_1234567890");
        assertThat(response.configJson()).doesNotContain("export_handout_pdf");
        assertThat(response.configJson()).doesNotContain("teacher_handout_writer");
    }

    @Test
    void rejectsUnsafeMcpConfigurationInput() {
        ProtocolDiscoveryService service = serviceWithClient(
                "workbuddy-teacher",
                "teacher",
                "mcp_secret_1234567890abcdef");

        assertThatThrownBy(() -> service.mcpConfiguration(new McpConfigurationRequest(
                "file:///C:/Users/doob/secret",
                "mcp_secret_1234567890abcdef",
                "MATH_AGENT_MCP_SECRET",
                List.of(),
                List.of())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("MCP URL");

        assertThatThrownBy(() -> service.mcpConfiguration(new McpConfigurationRequest(
                "https://math.example.com/api/mcp",
                "short",
                "MATH_AGENT_MCP_SECRET",
                List.of(),
                List.of())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("secretKey");
    }

    @Test
    void rejectsUnregisteredSecretInsteadOfGeneratingFakeConfig() {
        ProtocolDiscoveryService service = new ProtocolDiscoveryService();

        assertThatThrownBy(() -> service.mcpConfiguration(new McpConfigurationRequest(
                "https://math.example.com/api/mcp",
                "mcp_secret_1234567890abcdef",
                "MATH_AGENT_MCP_SECRET",
                List.of(),
                List.of())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not registered");
    }

    private static ProtocolDiscoveryService serviceWithClient(String clientId, String profile, String secret) {
        McpClientRegistryProperties properties = new McpClientRegistryProperties();
        properties.setClients(List.of(new McpClientRegistryProperties.Client(
                clientId,
                profile,
                "school-a",
                clientId + "-subject",
                McpClientRegistryProperties.secretHash(secret),
                true,
                List.of("search_textbook_evidence"),
                List.of("PUBLIC_TEXTBOOK"))));
        return new ProtocolDiscoveryService(properties);
    }
}
