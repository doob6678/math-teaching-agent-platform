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
    void returnsReadOnlyMcpDiscoveryMetadataWithoutExecutionEndpoints() throws Exception {
        ProtocolDiscoveryService service = new ProtocolDiscoveryService();

        List<McpToolDescriptor> tools = service.mcpTools();

        assertThat(tools).extracting(McpToolDescriptor::name)
                .contains(
                        "search_textbook_evidence",
                        "plan_agent_run",
                        "create_teaching_task",
                        "export_handout_pdf");
        assertThat(tools).allSatisfy(tool -> assertThat(tool.executionEndpointEnabled()).isFalse());
        assertThat(tools).filteredOn(tool -> tool.requiresCapabilityToken())
                .extracting(McpToolDescriptor::name)
                .contains("create_teaching_task", "export_handout_pdf");
        assertThat(tools).filteredOn(tool -> tool.name().equals("search_textbook_evidence"))
                .singleElement()
                .satisfies(tool -> {
                    assertThat(tool.readOnly()).isTrue();
                    assertThat(tool.inputSchema().required()).contains("query");
                    assertThat(tool.requiredRoles()).contains("student", "teacher", "admin");
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
