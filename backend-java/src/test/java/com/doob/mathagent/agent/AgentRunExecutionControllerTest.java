package com.doob.mathagent.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.doob.mathagent.agent.controller.AgentRunExecutionController;
import com.doob.mathagent.agent.dto.AgentRunExecuteRequest;
import com.doob.mathagent.agent.service.AgentConcurrencyGuard;
import com.doob.mathagent.agent.service.AgentRunExecutionService;
import com.doob.mathagent.agent.service.InMemoryAgentTraceStore;
import com.doob.mathagent.agent.vo.AgentRunExecuteResponse;
import com.doob.mathagent.agent.vo.AgentRunPlanResponse;
import com.doob.mathagent.infrastructure.security.RequestSubject;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class AgentRunExecutionControllerTest {

    @Test
    void executesAfterBackendSubjectVerification() {
        AgentRunExecutionController controller = new AgentRunExecutionController(
                AgentRunExecutionServiceFixture.deterministicModelService(new InMemoryAgentTraceStore()),
                request -> new RequestSubject("school-a", "teacher", "teacher-001", "device-1"));

        AgentRunExecuteResponse response = controller.execute(request(), null);

        assertThat(response.status()).isEqualTo("COMPLETED");
        assertThat(response.subjectId()).isEqualTo("teacher-001");
    }

    @Test
    void mapsConcurrencyConflictToTooManyRequests() {
        AgentConcurrencyGuard deniedGuard = (keys, traceId, leaseTime) -> Optional.empty();
        AgentRunExecutionController controller = new AgentRunExecutionController(
                AgentRunExecutionServiceFixture.deterministicModelService(new InMemoryAgentTraceStore(), deniedGuard),
                request -> new RequestSubject("school-a", "teacher", "teacher-001", "device-1"));

        assertThatThrownBy(() -> controller.execute(request(), null))
                .isInstanceOfSatisfying(ResponseStatusException.class, exception ->
                        assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS));
    }

    private static AgentRunExecuteRequest request() {
        AgentRunPlanResponse plan = new AgentRunPlanResponse(
                "plan-1", "school-a", "teacher", "teacher-001", "CoursewareAgent", "openai", "gpt-5.4",
                "reasoning", List.of("tool:search:textbook"), List.of(),
                List.of(new AgentRunPlanResponse.ToolPolicyDecision(
                        "tool:search:textbook", "ALLOWED", "backend policy")),
                List.of("PUBLIC_TEXTBOOK"), List.of(), 12000, 4000, 4600, 0.46, true,
                "test route", List.of(new AgentRunPlanResponse.StageTiming("model_route", 1)),
                List.of("concurrent:user:teacher-001:CoursewareAgent"));
        return new AgentRunExecuteRequest(plan, "Generate courseware", List.of("doc-1"), false);
    }
}
