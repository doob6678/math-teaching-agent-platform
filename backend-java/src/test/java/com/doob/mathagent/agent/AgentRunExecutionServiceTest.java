package com.doob.mathagent.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.doob.mathagent.agent.dto.AgentRunExecuteRequest;
import com.doob.mathagent.agent.dto.AgentRunPlanRequest;
import com.doob.mathagent.agent.service.AgentConcurrencyGuard;
import com.doob.mathagent.agent.service.AgentConcurrencyLease;
import com.doob.mathagent.agent.service.AiChatGateway;
import com.doob.mathagent.agent.service.AiChatRequest;
import com.doob.mathagent.agent.service.AiChatResult;
import com.doob.mathagent.agent.service.AgentRunExecutionService;
import com.doob.mathagent.agent.service.AgentRunPlanService;
import com.doob.mathagent.agent.service.InMemoryAgentConcurrencyGuard;
import com.doob.mathagent.agent.service.InMemoryAgentTraceStore;
import com.doob.mathagent.agent.vo.AgentRunExecuteResponse;
import com.doob.mathagent.agent.vo.AgentRunPlanResponse;
import com.doob.mathagent.infrastructure.ai.AiProviderCatalog;
import com.doob.mathagent.infrastructure.ai.AiProviderProperties;
import com.doob.mathagent.infrastructure.security.RequestSubject;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class AgentRunExecutionServiceTest {

    @Test
    void recordsBaselineTraceWithoutRawModelOutput() {
        AgentRunExecutionService service = new AgentRunExecutionService(
                new InMemoryAgentTraceStore(),
                new InMemoryAgentConcurrencyGuard());
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
        assertThat(response.providerName()).isEqualTo("dashscope");
        assertThat(response.modelCode()).isEqualTo("qwen3.6-flash");
        assertThat(response.allowedToolScopes()).containsExactlyElementsOf(plan.allowedToolScopes());
        assertThat(response.allowedDataScopes()).containsExactlyElementsOf(plan.allowedDataScopes());
        assertThat(response.concurrencyKeys()).containsExactlyElementsOf(plan.concurrencyKeys());
        assertThat(response.estimatedCost()).isEqualTo(plan.estimatedCost());
        assertThat(response.stageTimings()).extracting(AgentRunExecuteResponse.StageTiming::stage)
                .containsExactly("capability_guard", "concurrency_guard", "trace_start", "baseline_execute", "trace_finish");
    }

    @Test
    void rejectsExecutionWhenBackendSubjectDoesNotOwnPlan() {
        AgentRunExecutionService service = new AgentRunExecutionService(
                new InMemoryAgentTraceStore(),
                new InMemoryAgentConcurrencyGuard());
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
        AgentRunExecutionService service = new AgentRunExecutionService(
                new InMemoryAgentTraceStore(),
                new InMemoryAgentConcurrencyGuard());
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
                plan.toolPolicyDecisions(),
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
        AgentRunExecutionService service = new AgentRunExecutionService(
                new InMemoryAgentTraceStore(),
                new InMemoryAgentConcurrencyGuard());
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
                plan.toolPolicyDecisions(),
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
        AgentRunExecutionService service = new AgentRunExecutionService(
                new InMemoryAgentTraceStore(),
                new InMemoryAgentConcurrencyGuard());
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
                plan.toolPolicyDecisions(),
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

    @Test
    void rejectsExecutionWhenFrontendReAddsUserDisabledTool() {
        AgentRunExecutionService service = new AgentRunExecutionService(
                new InMemoryAgentTraceStore(),
                new InMemoryAgentConcurrencyGuard());
        AgentRunPlanResponse plan = disabledPrivateSearchPlan();
        AgentRunPlanResponse tampered = new AgentRunPlanResponse(
                plan.planId(),
                plan.tenantId(),
                plan.subjectType(),
                plan.subjectId(),
                plan.agentCode(),
                plan.providerName(),
                plan.modelCode(),
                plan.modelLevel(),
                List.of("tool:courseware:generate", "tool:search:private"),
                plan.deniedToolScopes(),
                plan.toolPolicyDecisions(),
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
                .hasMessageContaining("Agent plan tool scope disabled by user");
    }

    @Test
    void rejectsExecutionWhenConcurrencyKeysAreAlreadyActive() {
        InMemoryAgentConcurrencyGuard guard = new InMemoryAgentConcurrencyGuard();
        AgentRunPlanResponse plan = coursewarePlan();
        guard.tryAcquire(plan.concurrencyKeys(), "trace-active", Duration.ofSeconds(30)).orElseThrow();
        AgentRunExecutionService service = new AgentRunExecutionService(new InMemoryAgentTraceStore(), guard);

        assertThatThrownBy(() -> service.execute(
                new AgentRunExecuteRequest(plan, "Generate handout", List.of(), true),
                new RequestSubject("school-a", "teacher", "teacher-001", "device-1")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Agent concurrency limit exceeded");
    }

    @Test
    void releasesConcurrencyLeaseAfterBaselineExecution() {
        TrackingConcurrencyGuard guard = new TrackingConcurrencyGuard();
        AgentRunPlanResponse plan = coursewarePlan();
        AgentRunExecutionService service = new AgentRunExecutionService(new InMemoryAgentTraceStore(), guard);

        service.execute(
                new AgentRunExecuteRequest(plan, "Generate handout", List.of(), true),
                new RequestSubject("school-a", "teacher", "teacher-001", "device-1"));

        assertThat(guard.requestedKeys).containsExactlyElementsOf(plan.concurrencyKeys());
        assertThat(guard.released).isTrue();
    }

    @Test
    void callsModelGatewayForNonDryRunAndReturnsActualUsage() {
        CapturingAiChatGateway gateway = new CapturingAiChatGateway(List.of(new AiChatResult(
                "dashscope",
                "qwen3.6-flash",
                11,
                7,
                18,
                "model response recorded")));
        AgentRunExecutionService service = new AgentRunExecutionService(
                new InMemoryAgentTraceStore(),
                new InMemoryAgentConcurrencyGuard(),
                gateway);
        AgentRunPlanResponse plan = coursewarePlan();

        AgentRunExecuteResponse response = service.execute(
                new AgentRunExecuteRequest(plan, "Generate handout", List.of("doc-1"), false),
                new RequestSubject("school-a", "teacher", "teacher-001", "device-1"));

        assertThat(gateway.requests).hasSize(1);
        assertThat(gateway.requests.getFirst().providerName()).isEqualTo("dashscope");
        assertThat(response.providerName()).isEqualTo("dashscope");
        assertThat(response.modelCode()).isEqualTo("qwen3.6-flash");
        assertThat(response.actualUsage().promptTokens()).isEqualTo(11);
        assertThat(response.actualUsage().completionTokens()).isEqualTo(7);
        assertThat(response.actualUsage().totalTokens()).isEqualTo(18);
        assertThat(response.stageTimings()).extracting(AgentRunExecuteResponse.StageTiming::stage)
                .contains("model_call");
        assertThat(response.message()).contains("model response recorded");
    }

    @Test
    void rotatesToFallbackModelWhenPrimaryModelCallFails() {
        CapturingAiChatGateway gateway = new CapturingAiChatGateway(List.of(
                new IllegalStateException("primary unavailable"),
                new AiChatResult("openai", "gpt-5.4", 9, 6, 15, "fallback response recorded")));
        AgentRunExecutionService service = new AgentRunExecutionService(
                new InMemoryAgentTraceStore(),
                new InMemoryAgentConcurrencyGuard(),
                gateway);
        AgentRunPlanResponse plan = coursewarePlan();

        AgentRunExecuteResponse response = service.execute(
                new AgentRunExecuteRequest(plan, "Generate handout", List.of("doc-1"), false),
                new RequestSubject("school-a", "teacher", "teacher-001", "device-1"));

        assertThat(gateway.requests).hasSize(2);
        assertThat(gateway.requests.get(0).providerName()).isEqualTo("dashscope");
        assertThat(gateway.requests.get(1).providerName()).isEqualTo("openai");
        assertThat(response.providerName()).isEqualTo("openai");
        assertThat(response.modelCode()).isEqualTo("gpt-5.4");
        assertThat(response.actualUsage().totalTokens()).isEqualTo(15);
        assertThat(response.message()).contains("fallback response recorded");
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
                        List.of(),
                        List.of("TEACHER_PRIVATE", "CLASS_AUTHORIZED"),
                        true),
                new RequestSubject("school-a", "teacher", "teacher-001", "device-1"));
    }

    private static AgentRunPlanResponse disabledPrivateSearchPlan() {
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
                        List.of("tool:search:private"),
                        List.of("TEACHER_PRIVATE", "CLASS_AUTHORIZED"),
                        true),
                new RequestSubject("school-a", "teacher", "teacher-001", "device-1"));
    }

    private static AiProviderCatalog providerCatalog() {
        AiProviderProperties properties = new AiProviderProperties();
        properties.setDefaultProvider("dashscope");
        properties.getDashscope().setApiKey("dashscope-key");
        properties.getDashscope().setChatModel("qwen3.6-flash");
        properties.getOpenai().setApiKey("openai-key");
        properties.getOpenai().setChatModel("gpt-5.4");
        return new AiProviderCatalog(properties);
    }

    private static final class TrackingConcurrencyGuard implements AgentConcurrencyGuard {
        private List<String> requestedKeys = List.of();
        private final AtomicBoolean released = new AtomicBoolean(false);

        @Override
        public Optional<AgentConcurrencyLease> tryAcquire(List<String> keys, String traceId, Duration leaseTime) {
            requestedKeys = keys;
            return Optional.of(() -> released.set(true));
        }
    }

    private static final class CapturingAiChatGateway implements AiChatGateway {
        private final List<Object> outcomes;
        private final AtomicInteger calls = new AtomicInteger();
        private final List<AiChatRequest> requests = new java.util.ArrayList<>();

        private CapturingAiChatGateway(List<Object> outcomes) {
            this.outcomes = outcomes;
        }

        @Override
        public AiChatResult call(AiChatRequest request) {
            requests.add(request);
            Object outcome = outcomes.get(calls.getAndIncrement());
            if (outcome instanceof RuntimeException exception) {
                throw exception;
            }
            return (AiChatResult) outcome;
        }
    }
}
