package com.doob.mathagent.agent;

import static org.assertj.core.api.Assertions.assertThat;

import com.doob.mathagent.agent.controller.AgentRunPlanController;
import com.doob.mathagent.agent.dto.AgentRunPlanRequest;
import com.doob.mathagent.agent.service.AgentRunPlanService;
import com.doob.mathagent.agent.vo.AgentRunPlanResponse;
import com.doob.mathagent.infrastructure.ai.AiProviderCatalog;
import com.doob.mathagent.infrastructure.ai.AiProviderProperties;
import com.doob.mathagent.infrastructure.security.RequestSubject;
import java.util.List;
import org.junit.jupiter.api.Test;

class AgentRunPlanControllerTest {

    @Test
    void resolvesBackendSubjectInsteadOfRequestBodyIdentity() {
        AiProviderProperties properties = new AiProviderProperties();
        properties.getOpenai().setApiKey("openai-key");
        AgentRunPlanController controller = new AgentRunPlanController(
                new AgentRunPlanService(new AiProviderCatalog(properties)),
                request -> new RequestSubject("school-a", "teacher", "teacher-001", "device-1"));
        AgentRunPlanRequest request = new AgentRunPlanRequest(
                "TeacherAssistantAgent",
                "handout_generation",
                "teacher",
                1200,
                800,
                false,
                true,
                "medium",
                "normal",
                1.0,
                0,
                false,
                List.of("tool:search:private"),
                List.of(),
                List.of("TEACHER_PRIVATE"),
                true);

        AgentRunPlanResponse response = controller.plan(request, null);

        assertThat(response.tenantId()).isEqualTo("school-a");
        assertThat(response.subjectType()).isEqualTo("teacher");
        assertThat(response.subjectId()).isEqualTo("teacher-001");
        assertThat(response.allowedDataScopes()).containsExactly("TEACHER_PRIVATE");
    }
}
