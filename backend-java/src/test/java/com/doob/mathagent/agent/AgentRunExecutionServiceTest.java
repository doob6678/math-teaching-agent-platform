package com.doob.mathagent.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.doob.mathagent.agent.dto.AgentRunExecuteRequest;
import com.doob.mathagent.agent.dto.AgentRunPlanRequest;
import com.doob.mathagent.agent.service.AgentConcurrencyGuard;
import com.doob.mathagent.agent.service.AgentConcurrencyLease;
import com.doob.mathagent.agent.service.AgentRunClient;
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
    void projectsPythonFacadeResultIntoSafeTraceAndPublicResponse() {
        InMemoryAgentTraceStore traceStore = new InMemoryAgentTraceStore();
        CapturingPythonClient client = new CapturingPythonClient(result(11, 7, 18, -1.0d, false));
        AgentRunExecutionService service = service(traceStore, new InMemoryAgentConcurrencyGuard(), client);
        AgentRunPlanResponse plan = coursewarePlan();

        AgentRunExecuteResponse response = service.execute(
                new AgentRunExecuteRequest(plan, "Generate teacher handout for space vectors", List.of("textbook:chapter-1"), false),
                subject());

        assertThat(client.calls()).isEqualTo(1);
        assertThat(client.lastRequest().evidenceRefs()).containsExactly("textbook:chapter-1");
        assertThat(response.traceId()).isNotBlank();
        assertThat(response.status()).isEqualTo("COMPLETED");
        assertThat(response.providerName()).isEqualTo("openai");
        assertThat(response.modelCode()).isEqualTo("gpt-5.6-luna");
        assertThat(response.actualUsage().totalTokens()).isEqualTo(18);
        assertThat(response.actualCost()).isEqualTo(-1.0d);
        assertThat(response.costKnown()).isFalse();
        assertThat(response.message()).isEqualTo("Python AI run completed.");
        assertThat(response.generatedContent()).contains("teacherExplanation");
        assertThat(response.stageTimings()).extracting(AgentRunExecuteResponse.StageTiming::stage)
                .containsExactly("subject_policy_guard", "concurrency_guard", "trace_start", "python_ai_run", "trace_finish");
        assertThat(traceStore.find(response.traceId()).orElseThrow().diagnosticEvents())
                .extracting(event -> event.eventType())
                .containsExactly("PYTHON_AI_RUN_SUCCEEDED");
    }

    @Test
    void rejectsExecutionWhenBackendSubjectDoesNotOwnPlanBeforePythonCall() {
        CapturingPythonClient client = new CapturingPythonClient(result(5, 3, 8, -1.0d, false));
        AgentRunExecutionService service = service(new InMemoryAgentTraceStore(), new InMemoryAgentConcurrencyGuard(), client);

        assertThatThrownBy(() -> service.execute(
                new AgentRunExecuteRequest(coursewarePlan(), "Generate handout", List.of(), false),
                new RequestSubject("school-a", "teacher", "teacher-002", "device-1")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Agent plan subject mismatch");
        assertThat(client.calls()).isZero();
    }

    @Test
    void rejectsOverBudgetPlanBeforePythonCall() {
        CapturingPythonClient client = new CapturingPythonClient(result(5, 3, 8, -1.0d, false));
        AgentRunExecutionService service = service(new InMemoryAgentTraceStore(), new InMemoryAgentConcurrencyGuard(), client);
        AgentRunPlanResponse plan = coursewarePlan();
        AgentRunPlanResponse overBudget = new AgentRunPlanResponse(
                plan.planId(), plan.tenantId(), plan.subjectType(), plan.subjectId(), plan.agentCode(),
                plan.providerName(), plan.modelCode(), plan.modelLevel(), plan.allowedToolScopes(), plan.deniedToolScopes(),
                plan.toolPolicyDecisions(), plan.allowedDataScopes(), plan.deniedDataScopes(), plan.maxInputTokens(),
                plan.maxOutputTokens(), plan.estimatedTotalTokens(), plan.estimatedCost(), false, plan.routeReason(),
                plan.stageTimings(), plan.concurrencyKeys());

        assertThatThrownBy(() -> service.execute(
                new AgentRunExecuteRequest(overBudget, "over budget", List.of(), false), subject()))
                .isInstanceOf(com.doob.mathagent.agent.service.AgentBudgetExceededException.class);
        assertThat(client.calls()).isZero();
    }

    @Test
    void rejectsTamperedScopesBeforePythonCall() {
        CapturingPythonClient client = new CapturingPythonClient(result(5, 3, 8, -1.0d, false));
        AgentRunExecutionService service = service(new InMemoryAgentTraceStore(), new InMemoryAgentConcurrencyGuard(), client);
        AgentRunPlanResponse plan = coursewarePlan();
        AgentRunPlanResponse tampered = new AgentRunPlanResponse(
                plan.planId(), plan.tenantId(), plan.subjectType(), plan.subjectId(), plan.agentCode(),
                plan.providerName(), plan.modelCode(), plan.modelLevel(),
                List.of("tool:courseware:generate", "tool:student:progress:write"), plan.deniedToolScopes(),
                plan.toolPolicyDecisions(), plan.allowedDataScopes(), plan.deniedDataScopes(), plan.maxInputTokens(),
                plan.maxOutputTokens(), plan.estimatedTotalTokens(), plan.estimatedCost(), plan.withinBudget(),
                plan.routeReason(), plan.stageTimings(), plan.concurrencyKeys());

        assertThatThrownBy(() -> service.execute(
                new AgentRunExecuteRequest(tampered, "tampered", List.of(), false), subject()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Agent plan tool scope not allowed");
        assertThat(client.calls()).isZero();
    }

    @Test
    void rejectsExecutionWhenConcurrencyKeysAreAlreadyActive() {
        InMemoryAgentConcurrencyGuard guard = new InMemoryAgentConcurrencyGuard();
        AgentRunPlanResponse plan = coursewarePlan();
        guard.tryAcquire(plan.concurrencyKeys(), "trace-active", Duration.ofSeconds(30)).orElseThrow();
        CapturingPythonClient client = new CapturingPythonClient(result(5, 3, 8, -1.0d, false));
        AgentRunExecutionService service = service(new InMemoryAgentTraceStore(), guard, client);

        assertThatThrownBy(() -> service.execute(
                new AgentRunExecuteRequest(plan, "Generate handout", List.of(), false), subject()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Agent concurrency limit exceeded");
        assertThat(client.calls()).isZero();
    }

    @Test
    void releasesConcurrencyLeaseAfterPythonFacadeExecution() {
        TrackingConcurrencyGuard guard = new TrackingConcurrencyGuard();
        AgentRunPlanResponse plan = coursewarePlan();
        AgentRunExecutionService service = service(
                new InMemoryAgentTraceStore(), guard, new CapturingPythonClient(result(5, 3, 8, -1.0d, false)));

        service.execute(new AgentRunExecuteRequest(plan, "Generate handout", List.of(), false), subject());

        assertThat(guard.requestedKeys).containsExactlyElementsOf(plan.concurrencyKeys());
        assertThat(guard.released).isTrue();
    }

    @Test
    void rejectsPythonUsageThatExceedsTheSignedBudget() {
        AgentRunPlanResponse plan = coursewarePlan();
        CapturingPythonClient client = new CapturingPythonClient(result(plan.maxInputTokens() + 1, 1, plan.maxInputTokens() + 2, -1.0d, false));
        AgentRunExecutionService service = service(new InMemoryAgentTraceStore(), new InMemoryAgentConcurrencyGuard(), client);

        assertThatThrownBy(() -> service.execute(
                new AgentRunExecuteRequest(plan, "Generate handout", List.of(), false), subject()))
                .isInstanceOf(com.doob.mathagent.agent.service.AgentBudgetExceededException.class)
                .hasMessageContaining("Python usage exceeded");
    }

    private static AgentRunExecutionService service(
            InMemoryAgentTraceStore traceStore, AgentConcurrencyGuard guard, AgentRunClient client) {
        return new AgentRunExecutionService(traceStore, guard, client, providerCatalog());
    }

    private static AgentRunClient.Result result(int prompt, int completion, int total, double cost, boolean costKnown) {
        return new AgentRunClient.Result(
                "openai", "gpt-5.6-luna", new AgentRunExecuteResponse.TokenUsage(prompt, completion, total),
                "Python AI run completed.", "{\"teacherExplanation\":\"Python facade draft\"}", cost, costKnown);
    }

    private static AgentRunPlanResponse coursewarePlan() {
        return new AgentRunPlanService(providerCatalog()).plan(new AgentRunPlanRequest(
                        "CoursewareAgent", "courseware_generation", "teacher", 3000, 1600, false, true,
                        "medium", "normal", 2.5, 0, true,
                        List.of("tool:courseware:generate", "tool:search:private"), List.of(),
                        List.of("TEACHER_PRIVATE", "CLASS_AUTHORIZED"), false),
                subject());
    }

    private static AiProviderCatalog providerCatalog() {
        AiProviderProperties properties = new AiProviderProperties();
        properties.setDefaultProvider("openai");
        properties.getOpenai().setEnabled(true);
        properties.getOpenai().setChatModel("gpt-5.6-luna");
        return new AiProviderCatalog(properties);
    }

    private static RequestSubject subject() {
        return new RequestSubject("school-a", "teacher", "teacher-001", "device-1");
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

    private static final class CapturingPythonClient implements AgentRunClient {
        private final AgentRunClient.Result response;
        private final AtomicInteger calls = new AtomicInteger();
        private AgentRunExecuteRequest lastRequest;

        private CapturingPythonClient(AgentRunClient.Result response) {
            this.response = response;
        }

        @Override
        public AgentRunClient.Result execute(String traceId, AgentRunExecuteRequest request, AgentRunPlanResponse plan) {
            lastRequest = request;
            calls.incrementAndGet();
            return response;
        }

        private int calls() {
            return calls.get();
        }

        private AgentRunExecuteRequest lastRequest() {
            return lastRequest;
        }
    }
}
