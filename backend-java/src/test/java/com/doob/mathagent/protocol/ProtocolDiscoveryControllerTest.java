package com.doob.mathagent.protocol;

import static org.assertj.core.api.Assertions.assertThat;

import com.doob.mathagent.protocol.controller.A2aAgentCardController;
import com.doob.mathagent.protocol.controller.McpDiscoveryController;
import com.doob.mathagent.protocol.dto.McpConfigurationRequest;
import com.doob.mathagent.protocol.service.ProtocolDiscoveryService;
import org.junit.jupiter.api.Test;

class ProtocolDiscoveryControllerTest {

    @Test
    void exposesMcpToolDiscoveryEndpointThroughService() {
        ProtocolDiscoveryService service = new ProtocolDiscoveryService();
        McpDiscoveryController controller = new McpDiscoveryController(service);

        var tools = controller.tools();

        assertThat(tools).extracting("name")
                .contains("search_textbook_evidence", "plan_agent_run");
        assertThat(tools).allSatisfy(tool -> assertThat(tool.executionEndpointEnabled()).isFalse());
    }

    @Test
    void exposesA2aAgentCardEndpointThroughService() {
        ProtocolDiscoveryService service = new ProtocolDiscoveryService();
        A2aAgentCardController controller = new A2aAgentCardController(service);

        var card = controller.agentCard();

        assertThat(card.name()).isEqualTo("Math Agent RAG");
        assertThat(card.skills()).extracting("id")
                .contains("teacher_student_handout_generation");
        assertThat(card.url()).isEqualTo("/api/a2a");
    }

    @Test
    void exposesCopyableMcpConfigurationThroughService() {
        ProtocolDiscoveryService service = new ProtocolDiscoveryService();
        McpDiscoveryController controller = new McpDiscoveryController(service);

        var config = controller.configuration(new McpConfigurationRequest(
                "https://math.example.com/api/mcp",
                "mcp_secret_1234567890abcdef",
                "MATH_AGENT_MCP_SECRET",
                java.util.List.of(),
                java.util.List.of()));

        assertThat(config.valid()).isTrue();
        assertThat(config.configJson()).contains("\"math-agent-rag\"");
        assertThat(config.configJson()).contains("${MATH_AGENT_MCP_SECRET}");
        assertThat(config.configJson()).doesNotContain("mcp_secret_1234567890abcdef");
    }
}
