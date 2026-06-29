package com.doob.mathagent.protocol;

import static org.assertj.core.api.Assertions.assertThat;

import com.doob.mathagent.protocol.controller.A2aAgentCardController;
import com.doob.mathagent.protocol.controller.McpDiscoveryController;
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
}
