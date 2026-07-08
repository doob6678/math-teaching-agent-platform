package com.doob.mathagent.protocol;

import static org.assertj.core.api.Assertions.assertThat;

import com.doob.mathagent.protocol.service.ProtocolDiscoveryService;
import com.doob.mathagent.protocol.vo.A2aAgentCardResponse;
import com.doob.mathagent.protocol.vo.McpToolDescriptor;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

class ProtocolDiscoveryServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void returnsReadOnlyMcpDiscoveryMetadataWithOnlyTextbookExecutionEnabled() throws Exception {
        ProtocolDiscoveryService service = new ProtocolDiscoveryService();

        List<McpToolDescriptor> tools = service.mcpTools();

        assertThat(tools).extracting(McpToolDescriptor::name)
                .contains(
                        "search_multi_source_evidence",
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
        assertThat(tools).extracting(McpToolDescriptor::name)
                .doesNotContain("create_teaching_task", "export_handout_pdf", "list_teacher_resources");
        assertThat(tools).filteredOn(McpToolDescriptor::executionEndpointEnabled)
                .extracting(McpToolDescriptor::name)
                .containsExactly(
                        "search_multi_source_evidence",
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
        assertThat(tools).filteredOn(tool -> tool.requiresCapabilityToken())
                .extracting(McpToolDescriptor::name)
                .contains("start_multi_agent_writing", "resume_multi_agent_writing", "download_feishu_resource");
        assertThat(tools).filteredOn(tool -> tool.name().equals("search_textbook_evidence"))
                .singleElement()
                .satisfies(tool -> {
                    assertThat(tool.readOnly()).isTrue();
                    assertThat(tool.inputSchema().required()).contains("query");
                    assertThat(tool.requiredRoles()).contains("student", "teacher", "admin");
                });
        assertThat(tools).filteredOn(tool -> tool.name().equals("search_multi_source_evidence"))
                .singleElement()
                .satisfies(tool -> {
                    assertThat(tool.readOnly()).isTrue();
                    assertThat(tool.requiredRoles()).containsExactly("teacher", "admin");
                    assertThat(tool.inputSchema().required()).contains("query");
                });
        assertThat(tools).filteredOn(tool -> tool.name().equals("search_teacher_resource_evidence"))
                .singleElement()
                .satisfies(tool -> {
                    assertThat(tool.readOnly()).isTrue();
                    assertThat(tool.requiredRoles()).containsExactly("teacher", "admin");
                    assertThat(tool.requiredScope()).isEqualTo("teacher-resource:read");
                });
        assertThat(tools).filteredOn(tool -> tool.name().equals("get_teaching_ai_trace"))
                .singleElement()
                .satisfies(tool -> {
                    assertThat(tool.readOnly()).isTrue();
                    assertThat(tool.requiredRoles()).contains("student", "teacher", "admin");
                    assertThat(tool.requiredScope()).isEqualTo("agent-trace:read");
                    assertThat(tool.inputSchema().required()).contains("taskId");
                });
        assertThat(tools).filteredOn(tool -> tool.name().equals("get_ai_diagnostic_summary"))
                .singleElement()
                .satisfies(tool -> {
                    assertThat(tool.readOnly()).isTrue();
                    assertThat(tool.requiredRoles()).contains("student", "teacher", "admin");
                    assertThat(tool.requiredScope()).isEqualTo("agent-trace:read");
                    assertThat(tool.inputSchema().required()).isEmpty();
                });
        assertThat(tools).filteredOn(tool -> tool.name().equals("get_multi_agent_writing_trace"))
                .singleElement()
                .satisfies(tool -> {
                    assertThat(tool.readOnly()).isTrue();
                    assertThat(tool.requiredRoles()).containsExactly("teacher", "admin");
                    assertThat(tool.requiredScope()).isEqualTo("agent-trace:read");
                    assertThat(tool.inputSchema().required()).containsExactly("workflowId");
                });
        assertThat(tools).filteredOn(tool -> tool.name().equals("download_feishu_resource"))
                .singleElement()
                .satisfies(tool -> {
                    assertThat(tool.readOnly()).isFalse();
                    assertThat(tool.requiresCapabilityToken()).isTrue();
                    assertThat(tool.requiredRoles()).containsExactly("teacher", "admin");
                    assertThat(tool.requiredScope()).isEqualTo("teacher-resource:sync-execute");
                    assertThat(tool.inputSchema().required()).containsExactly("url");
                });
        assertThat(tools).filteredOn(tool -> tool.name().equals("start_multi_agent_writing"))
                .singleElement()
                .satisfies(tool -> {
                    assertThat(tool.readOnly()).isFalse();
                    assertThat(tool.executionEndpointEnabled()).isTrue();
                    assertThat(tool.requiredRoles()).containsExactly("teacher", "admin");
                    assertThat(tool.requiredScope()).isEqualTo("agent-writing:execute");
                    assertThat(tool.inputSchema().required()).containsExactly("questionText");
                });
        assertThat(tools).filteredOn(tool -> tool.name().equals("get_multi_agent_writing_status"))
                .singleElement()
                .satisfies(tool -> {
                    assertThat(tool.readOnly()).isTrue();
                    assertThat(tool.requiredScope()).isEqualTo("agent-writing:read");
                    assertThat(tool.inputSchema().required()).containsExactly("workflowId");
                });
        assertThat(tools).filteredOn(tool -> tool.name().equals("get_multi_agent_writing_artifact"))
                .singleElement()
                .satisfies(tool -> {
                    assertThat(tool.readOnly()).isTrue();
                    assertThat(tool.requiredRoles()).containsExactly("teacher", "admin");
                    assertThat(tool.requiredScope()).isEqualTo("agent-writing:read");
                    assertThat(tool.inputSchema().required()).containsExactly("workflowId");
                });
        assertThat(tools).filteredOn(tool -> tool.name().equals("export_multi_agent_writing_artifact"))
                .singleElement()
                .satisfies(tool -> {
                    assertThat(tool.readOnly()).isFalse();
                    assertThat(tool.requiredRoles()).containsExactly("teacher", "admin");
                    assertThat(tool.requiredScope()).isEqualTo("agent-writing:export");
                    assertThat(tool.inputSchema().required()).containsExactly("workflowId");
                });
        assertNoSecretsOrLocalPaths(objectMapper.writeValueAsString(tools));
    }

    @Test
    void returnsA2aAgentCardWithoutSecretsOrLocalPaths() throws Exception {
        ProtocolDiscoveryService service = new ProtocolDiscoveryService();

        A2aAgentCardResponse card = service.a2aAgentCard();

        assertThat(card.name()).isEqualTo("Math Agent RAG");
        assertThat(card.capabilities().streaming()).isFalse();
        assertThat(card.capabilities().stateTransitionHistory()).isTrue();
        assertThat(card.skills()).extracting(A2aAgentCardResponse.Skill::id)
                .contains(
                        "textbook_evidence_retrieval",
                        "teaching_task_planning",
                        "teacher_student_handout_generation",
                        "agent_run_planning");
        assertThat(card.securitySchemes()).extracting(A2aAgentCardResponse.SecurityScheme::id)
                .contains("sa-token-session", "capability-token");
        assertNoSecretsOrLocalPaths(objectMapper.writeValueAsString(card));
    }

    private static void assertNoSecretsOrLocalPaths(String json) {
        assertThat(json).doesNotContain("C:\\");
        assertThat(json).doesNotContain("Users/doob");
        assertThat(json).doesNotContain("OPENAI_API_KEY");
        assertThat(json).doesNotContain("DEEPSEEK_API_KEY");
        assertThat(json).doesNotContain("ARK_API_KEY");
        assertThat(json).doesNotContain("sk-");
        assertThat(json).doesNotContain("AKIA");
    }
}
