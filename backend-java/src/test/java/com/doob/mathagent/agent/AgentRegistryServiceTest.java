package com.doob.mathagent.agent;

import static org.assertj.core.api.Assertions.assertThat;

import com.doob.mathagent.agent.service.AgentRegistryService;
import com.doob.mathagent.agent.vo.AgentRegistryResponse;
import com.doob.mathagent.infrastructure.security.RequestSubject;
import org.junit.jupiter.api.Test;

/** Verifies marketplace discovery is filtered by the trusted backend subject, not a browser-provided role. */
class AgentRegistryServiceTest {

    private final AgentRegistryService registryService = new AgentRegistryService();

    @Test
    void studentSeesOnlyStudentExecutableAgents() {
        AgentRegistryResponse response = registryService.visibleAgents(
                new RequestSubject("school-a", "student", "student-1", "device-1"));

        assertThat(response.agents()).extracting(AgentRegistryResponse.Item::code)
                .contains("StudentTutorAgent", "KnowledgeRetrievalAgent")
                .doesNotContain("SupervisorAgent", "DocumentWriterAgent");
    }

    @Test
    void teacherSeesSpecialistsAndServerMarkedHighValueCards() {
        AgentRegistryResponse response = registryService.visibleAgents(
                new RequestSubject("school-a", "teacher", "teacher-1", "device-1"));

        assertThat(response.agents()).extracting(AgentRegistryResponse.Item::code)
                .contains("SupervisorAgent", "KnowledgeRetrievalAgent", "DocumentWriterAgent");
        assertThat(response.agents()).extracting(AgentRegistryResponse.Item::code)
                .contains("SupervisorAgent", "DocumentWriterAgent");
    }
}
