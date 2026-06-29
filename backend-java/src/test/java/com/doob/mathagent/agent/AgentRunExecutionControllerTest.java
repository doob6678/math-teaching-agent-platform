package com.doob.mathagent.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.doob.mathagent.agent.controller.AgentRunExecutionController;
import com.doob.mathagent.agent.dto.AgentRunExecuteRequest;
import com.doob.mathagent.agent.service.AgentConcurrencyGuard;
import com.doob.mathagent.agent.service.AgentRunExecutionService;
import com.doob.mathagent.agent.service.AgentRunCapabilityVerifier;
import com.doob.mathagent.agent.service.InMemoryAgentTraceStore;
import com.doob.mathagent.agent.vo.AgentRunExecuteResponse;
import com.doob.mathagent.agent.vo.AgentRunPlanResponse;
import com.doob.mathagent.infrastructure.security.RequestSubject;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class AgentRunExecutionControllerTest {

    @Test
    void rejectsHighValueExecutionWithoutAcceptedCapabilityToken() {
        AgentRunExecutionController controller = new AgentRunExecutionController(
                new AgentRunExecutionService(new InMemoryAgentTraceStore()),
                request -> new RequestSubject("school-a", "teacher", "teacher-001", "device-1"),
                (token, action, path, requestHash, subject) -> false);

        assertThatThrownBy(() -> controller.execute(highValueRequest(), null))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Capability token required");
    }

    @Test
    void consumesCapabilityForHighValueExecutionAndRecordsTrace() {
        List<String> actions = new ArrayList<>();
        AgentRunCapabilityVerifier verifier = (token, action, path, requestHash, subject) -> {
            actions.add(action + "|" + path + "|" + requestHash + "|" + subject.subjectId());
            return true;
        };
        AgentRunExecutionController controller = new AgentRunExecutionController(
                new AgentRunExecutionService(new InMemoryAgentTraceStore()),
                request -> new RequestSubject("school-a", "teacher", "teacher-001", "device-1"),
                verifier);

        AgentRunExecuteResponse response = controller.execute(highValueRequest(), null);

        assertThat(response.status()).isEqualTo("COMPLETED");
        assertThat(actions).containsExactly("agent-run:CoursewareAgent|/api/agents/execute||teacher-001");
    }

    @Test
    void allowsNonHighValueExecutionWithoutCapability() {
        AgentRunExecutionController controller = new AgentRunExecutionController(
                new AgentRunExecutionService(new InMemoryAgentTraceStore()),
                request -> new RequestSubject("school-a", "student", "student-001", "device-1"),
                (token, action, path, requestHash, subject) -> false);

        AgentRunExecuteResponse response = controller.execute(nonHighValueRequest(), null);

        assertThat(response.status()).isEqualTo("COMPLETED");
        assertThat(response.subjectId()).isEqualTo("student-001");
    }

    @Test
    void mapsConcurrencyConflictToTooManyRequests() {
        AgentConcurrencyGuard deniedGuard = (keys, traceId, leaseTime) -> Optional.empty();
        AgentRunExecutionController controller = new AgentRunExecutionController(
                new AgentRunExecutionService(new InMemoryAgentTraceStore(), deniedGuard),
                request -> new RequestSubject("school-a", "teacher", "teacher-001", "device-1"),
                (token, action, path, requestHash, subject) -> true);

        assertThatThrownBy(() -> controller.execute(highValueRequest(), null))
                .isInstanceOfSatisfying(ResponseStatusException.class, exception ->
                        assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS));
    }

    private static AgentRunExecuteRequest highValueRequest() {
        return new AgentRunExecuteRequest(highValuePlan(), "Generate courseware", List.of("doc-1"), true);
    }

    private static AgentRunExecuteRequest nonHighValueRequest() {
        return new AgentRunExecuteRequest(nonHighValuePlan(), "Solve a problem", List.of("textbook-1"), true);
    }

    private static AgentRunPlanResponse highValuePlan() {
        return plan("plan-1", "teacher", "teacher-001", "CoursewareAgent", true, "agent-run:CoursewareAgent");
    }

    private static AgentRunPlanResponse nonHighValuePlan() {
        return plan("plan-2", "student", "student-001", "StudentTutorAgent", false, "");
    }

    private static AgentRunPlanResponse plan(
            String planId,
            String subjectType,
            String subjectId,
            String agentCode,
            boolean capabilityRequired,
            String capabilityAction) {
        return new AgentRunPlanResponse(
                planId,
                "school-a",
                subjectType,
                subjectId,
                agentCode,
                "openai",
                "gpt-5.4",
                "reasoning",
                List.of("tool:search:textbook"),
                List.of(),
                List.of(new AgentRunPlanResponse.ToolPolicyDecision(
                        "tool:search:textbook",
                        "ALLOWED",
                        "Tool is allowed by agent policy and not disabled by request preference")),
                List.of("PUBLIC_TEXTBOOK"),
                List.of(),
                capabilityRequired,
                capabilityAction,
                12000,
                4000,
                4600,
                0.46,
                true,
                "test route",
                List.of(new AgentRunPlanResponse.StageTiming("model_route", 1)),
                List.of("concurrent:user:" + subjectId + ":" + agentCode));
    }
}
