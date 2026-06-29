package com.doob.mathagent.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.doob.mathagent.agent.dto.AgentRunExecuteRequest;
import com.doob.mathagent.agent.dto.AgentRunPlanRequest;
import com.doob.mathagent.agent.service.AgentRunExecutionService;
import com.doob.mathagent.agent.service.AgentRunPlanService;
import com.doob.mathagent.agent.service.InMemoryAgentTraceStore;
import com.doob.mathagent.agent.vo.AgentRunExecuteResponse;
import com.doob.mathagent.agent.vo.AgentRunPlanResponse;
import com.doob.mathagent.infrastructure.ai.AiProviderCatalog;
import com.doob.mathagent.infrastructure.ai.AiProviderProperties;
import com.doob.mathagent.infrastructure.security.RequestSubject;
import java.util.List;
import org.junit.jupiter.api.Test;

class AgentRunExecutionServiceTest {

    @Test
    void recordsBaselineTraceWithoutRawModelOutput() {
        AgentRunExecutionService service = new AgentRunExecutionService(new InMemoryAgentTraceStore());
        AgentRunPlanResponse plan = coursewarePlan();
        AgentRunExecuteRequest request = new AgentRunExecuteRequest(
                plan,
                "Generate teacher handout for space vectors",
                List.of("textbook:chapter-1"),
                true);

        AgentRunExecuteResponse response = service.execute(
                request,
                new RequestSubject("school-a", "teacher", "teacher-001", "device-1"));

        assertThat(response.traceId()).isNotBlank();
        assertThat(response.status()).isEqualTo("COMPLETED");
        assertThat(response.message()).contains("Baseline trace recorded");
        assertThat(response.planId()).isEqualTo(plan.planId());
        assertThat(response.agentCode()).isEqualTo("CoursewareAgent");
        assertThat(response.providerName()).isEqualTo("openai");
        assertThat(response.modelCode()).isEqualTo("gpt-4.1");
        assertThat(response.allowedToolScopes()).containsExactlyElementsOf(plan.allowedToolScopes());
        assertThat(response.allowedDataScopes()).containsExactlyElementsOf(plan.allowedDataScopes());
        assertThat(response.estimatedCost()).isEqualTo(plan.estimatedCost());
        assertThat(response.stageTimings()).extracting(AgentRunExecuteResponse.StageTiming::stage)
                .containsExactly("capability_guard", "trace_start", "baseline_execute", "trace_finish");
    }

    @Test
    void rejectsExecutionWhenBackendSubjectDoesNotOwnPlan() {
        AgentRunExecutionService service = new AgentRunExecutionService(new InMemoryAgentTraceStore());
        AgentRunExecuteRequest request = new AgentRunExecuteRequest(
                coursewarePlan(),
                "Generate teacher handout for space vectors",
                List.of(),
                true);

        assertThatThrownBy(() -> service.execute(
                request,
                new RequestSubject("school-a", "teacher", "teacher-002", "device-1")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Agent plan subject mismatch");
    }

    @Test
    void rejectsExecutionWhenPlanContainsToolScopeOutsideServerPolicy() {
        AgentRunExecutionService service = new AgentRunExecutionService(new InMemoryAgentTraceStore());
        AgentRunPlanResponse plan = coursewarePlan();
        AgentRunPlanResponse tampered = new AgentRunPlanResponse(
                plan.planId(),
                plan.tenantId(),
                plan.subjectType(),
                plan.subjectId(),
                plan.agentCode(),
                plan.providerName(),
                plan.modelCode(),
                plan.modelLevel(),
                List.of("tool:courseware:generate", "tool:student:progress:write"),
                plan.deniedToolScopes(),
                plan.allowedDataScopes(),
                plan.deniedDataScopes(),
                plan.capabilityRequired(),
                plan.capabilityAction(),
                plan.maxInputTokens(),
                plan.maxOutputTokens(),
                plan.estimatedTotalTokens(),
                plan.estimatedCost(),
                plan.withinBudget(),
                plan.routeReason(),
                plan.stageTimings(),
                plan.concurrencyKeys());

        assertThatThrownBy(() -> service.execute(
                new AgentRunExecuteRequest(tampered, "tampered", List.of(), true),
                new RequestSubject("school-a", "teacher", "teacher-001", "device-1")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Agent plan tool scope not allowed");
    }

    @Test
    void rejectsExecutionWhenPlanRoleIsNotAllowedForAgent() {
        AgentRunExecutionService service = new AgentRunExecutionService(new InMemoryAgentTraceStore());
        AgentRunPlanResponse plan = coursewarePlan();
        AgentRunPlanResponse tampered = new AgentRunPlanResponse(
                plan.planId(),
                plan.tenantId(),
                "student",
                "student-001",
                plan.agentCode(),
                plan.providerName(),
                plan.modelCode(),
                plan.modelLevel(),
                plan.allowedToolScopes(),
                plan.deniedToolScopes(),
                plan.allowedDataScopes(),
                plan.deniedDataScopes(),
                plan.capabilityRequired(),
                plan.capabilityAction(),
                plan.maxInputTokens(),
                plan.maxOutputTokens(),
                plan.estimatedTotalTokens(),
                plan.estimatedCost(),
                plan.withinBudget(),
                plan.routeReason(),
                plan.stageTimings(),
                plan.concurrencyKeys());

        assertThatThrownBy(() -> service.execute(
                new AgentRunExecuteRequest(tampered, "tampered", List.of(), true),
                new RequestSubject("school-a", "student", "student-001", "device-1")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Agent subject not allowed");
    }

    @Test
    void requiresCapabilityFromServerSideAgentPolicyEvenWhenPlanIsTampered() {
        AgentRunExecutionService service = new AgentRunExecutionService(new InMemoryAgentTraceStore());
        AgentRunPlanResponse plan = coursewarePlan();
        AgentRunPlanResponse tampered = new AgentRunPlanResponse(
                plan.planId(),
                plan.tenantId(),
                plan.subjectType(),
                plan.subjectId(),
                plan.agentCode(),
                plan.providerName(),
                plan.modelCode(),
                plan.modelLevel(),
                plan.allowedToolScopes(),
                plan.deniedToolScopes(),
                plan.allowedDataScopes(),
                plan.deniedDataScopes(),
                false,
                "",
                plan.maxInputTokens(),
                plan.maxOutputTokens(),
                plan.estimatedTotalTokens(),
                plan.estimatedCost(),
                plan.withinBudget(),
                plan.routeReason(),
                plan.stageTimings(),
                plan.concurrencyKeys());

        assertThat(service.requiresCapability(new AgentRunExecuteRequest(tampered, "tampered", List.of(), true)))
                .isTrue();
        assertThat(service.capabilityAction(tampered)).isEqualTo("agent-run:CoursewareAgent");
    }

    private static AgentRunPlanResponse coursewarePlan() {
        return new AgentRunPlanService(providerCatalog()).plan(new AgentRunPlanRequest(
                        "CoursewareAgent",
                        "courseware_generation",
                        "teacher",
                        3000,
                        1600,
                        false,
                        true,
                        "medium",
                        "normal",
                        2.5,
                        0,
                        true,
                        List.of("tool:courseware:generate", "tool:search:private"),
                        List.of("TEACHER_PRIVATE", "CLASS_AUTHORIZED"),
                        true),
                new RequestSubject("school-a", "teacher", "teacher-001", "device-1"));
    }

    private static AiProviderCatalog providerCatalog() {
        AiProviderProperties properties = new AiProviderProperties();
        properties.setDefaultProvider("openai");
        properties.getOpenai().setApiKey("openai-key");
        properties.getOpenai().setChatModel("gpt-4.1");
        return new AiProviderCatalog(properties);
    }
}
