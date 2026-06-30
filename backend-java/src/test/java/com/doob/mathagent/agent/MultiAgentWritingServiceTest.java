package com.doob.mathagent.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.doob.mathagent.agent.dto.MultiAgentWritingRequest;
import com.doob.mathagent.agent.service.AgentRunExecutionService;
import com.doob.mathagent.agent.service.AgentRunPlanService;
import com.doob.mathagent.agent.service.AiChatGateway;
import com.doob.mathagent.agent.service.AiChatRequest;
import com.doob.mathagent.agent.service.AiChatResult;
import com.doob.mathagent.agent.service.InMemoryAgentConcurrencyGuard;
import com.doob.mathagent.agent.service.InMemoryAgentTraceStore;
import com.doob.mathagent.agent.service.InMemoryMultiAgentWritingWorkflowStore;
import com.doob.mathagent.agent.service.MultiAgentWritingService;
import com.doob.mathagent.agent.service.MultiAgentWritingWorkflowRecord;
import com.doob.mathagent.agent.service.MultiAgentWritingWorkflowStore;
import com.doob.mathagent.agent.vo.MultiAgentWritingResponse;
import com.doob.mathagent.infrastructure.ai.AiProviderCatalog;
import com.doob.mathagent.infrastructure.ai.AiProviderProperties;
import com.doob.mathagent.infrastructure.security.RequestSubject;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.core.task.TaskExecutor;
import org.junit.jupiter.api.Test;

class MultiAgentWritingServiceTest {

    @Test
    void runsThreeWritingAgentsAndPersistsRecoverableTraces() {
        CapturingGateway gateway = new CapturingGateway(List.of(
                new AiChatResult("dashscope", "qwen3.6-flash", 11, 7, 18, "draft recorded"),
                new AiChatResult("dashscope", "qwen3.6-flash", 9, 5, 14, "review recorded"),
                new AiChatResult("dashscope", "qwen3.6-flash", 8, 4, 12, "format recorded")));
        InMemoryAgentTraceStore traceStore = new InMemoryAgentTraceStore();
        InMemoryMultiAgentWritingWorkflowStore workflowStore = new InMemoryMultiAgentWritingWorkflowStore();
        MultiAgentWritingService service = service(traceStore, workflowStore, gateway);

        MultiAgentWritingResponse response = service.run(
                new MultiAgentWritingRequest(
                        "teacher handout",
                        "space vector angle",
                        List.of("PUBLIC_TEXTBOOK:space-vector:angle"),
                        false,
                        "dashscope",
                        "qwen3.6-flash"),
                new RequestSubject("school-a", "teacher", "teacher-1", "device-1"));

        assertThat(response.status()).isEqualTo("COMPLETED");
        assertThat(response.stages()).extracting(MultiAgentWritingResponse.StageResult::agentCode)
                .containsExactly("CoursewareAgent", "QualityCheckAgent", "HandoutFormatterAgent");
        assertThat(response.totalUsage().totalTokens()).isEqualTo(44);
        assertThat(gateway.requests()).extracting(AiChatRequest::agentCode)
                .containsExactly("CoursewareAgent", "QualityCheckAgent", "HandoutFormatterAgent");
        assertThat(response.stages()).allSatisfy(stage -> {
            var trace = traceStore.find(stage.traceId()).orElseThrow();
            assertThat(trace.planId()).startsWith(response.workflowId() + ":");
            assertThat(trace.subjectId()).isEqualTo("teacher-1");
            assertThat(trace.diagnosticEvents()).extracting(
                    com.doob.mathagent.agent.service.AgentTraceRecord.DiagnosticEvent::eventType)
                    .contains("MODEL_CALL_SUCCEEDED");
        });
        MultiAgentWritingResponse recovered = service.find(
                        response.workflowId(),
                        new RequestSubject("school-a", "teacher", "teacher-1", "device-1"))
                .orElseThrow();
        assertThat(recovered.status()).isEqualTo("COMPLETED");
        assertThat(recovered.stages()).hasSize(3);
        assertThat(recovered.totalUsage().totalTokens()).isEqualTo(44);
        assertThat(workflowStore.findVisible(
                        response.workflowId(),
                        new RequestSubject("school-a", "teacher", "teacher-2", "device-1")))
                .isEmpty();
    }

    @Test
    void rejectsStudentSubjectBeforeAnyModelCall() {
        CapturingGateway gateway = new CapturingGateway(List.of());
        MultiAgentWritingService service = service(
                new InMemoryAgentTraceStore(),
                new InMemoryMultiAgentWritingWorkflowStore(),
                gateway);

        assertThatThrownBy(() -> service.run(
                        new MultiAgentWritingRequest("student handout", "function", List.of(), true, "", ""),
                        new RequestSubject("school-a", "student", "student-1", "device-1")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("teacher or admin");
        assertThat(gateway.requests()).isEmpty();
    }

    @Test
    void startsAsyncWorkflowAndCompletesWhenExecutorRuns() {
        CapturingGateway gateway = new CapturingGateway(List.of(
                new AiChatResult("dashscope", "qwen3.6-flash", 11, 7, 18, "draft recorded"),
                new AiChatResult("dashscope", "qwen3.6-flash", 9, 5, 14, "review recorded"),
                new AiChatResult("dashscope", "qwen3.6-flash", 8, 4, 12, "format recorded")));
        InMemoryMultiAgentWritingWorkflowStore workflowStore = new InMemoryMultiAgentWritingWorkflowStore();
        CapturingTaskExecutor taskExecutor = new CapturingTaskExecutor();
        MultiAgentWritingService service = service(
                new InMemoryAgentTraceStore(),
                workflowStore,
                taskExecutor,
                gateway);

        MultiAgentWritingResponse started = service.startAsync(
                new MultiAgentWritingRequest(
                        "teacher handout",
                        "space vector angle",
                        List.of("PUBLIC_TEXTBOOK:space-vector:angle"),
                        false,
                        "dashscope",
                        "qwen3.6-flash"),
                new RequestSubject("school-a", "teacher", "teacher-1", "device-1"));

        assertThat(started.status()).isEqualTo("RUNNING");
        assertThat(started.workflowId()).isNotBlank();
        assertThat(gateway.requests()).isEmpty();

        taskExecutor.runNext();

        MultiAgentWritingResponse completed = service.find(
                        started.workflowId(),
                        new RequestSubject("school-a", "teacher", "teacher-1", "device-1"))
                .orElseThrow();
        assertThat(completed.status()).isEqualTo("COMPLETED");
        assertThat(completed.stages()).hasSize(3);
        assertThat(completed.totalUsage().totalTokens()).isEqualTo(44);
    }

    @Test
    void persistsFailedWorkflowStatusWithCompletedStages() {
        CapturingGateway gateway = new CapturingGateway(List.of(
                new AiChatResult("dashscope", "qwen3.6-flash", 11, 7, 18, "draft recorded")));
        CapturingWorkflowStore workflowStore = new CapturingWorkflowStore();
        MultiAgentWritingService service = service(new InMemoryAgentTraceStore(), workflowStore, gateway);

        assertThatThrownBy(() -> service.run(
                        new MultiAgentWritingRequest(
                                "teacher handout",
                                "space vector angle",
                                List.of("PUBLIC_TEXTBOOK:space-vector:angle"),
                                false,
                                "dashscope",
                                "qwen3.6-flash"),
                        new RequestSubject("school-a", "teacher", "teacher-1", "device-1")))
                .isInstanceOf(RuntimeException.class);

        assertThat(gateway.requests()).hasSize(2);
        assertThat(workflowStore.saved()).extracting(MultiAgentWritingWorkflowRecord::status)
                .containsExactly("RUNNING", "RUNNING", "FAILED");
        assertThat(workflowStore.saved().getLast().stages()).hasSize(1);
        assertThat(workflowStore.saved().getLast().message()).contains("failed");
    }

    /**
     * Builds the service with real planner/executor policy and a controlled gateway.
     */
    private static MultiAgentWritingService service(
            InMemoryAgentTraceStore traceStore,
            MultiAgentWritingWorkflowStore workflowStore,
            AiChatGateway gateway) {
        return service(traceStore, workflowStore, Runnable::run, gateway);
    }

    /**
     * Builds the service with a controlled task executor for async tests.
     */
    private static MultiAgentWritingService service(
            InMemoryAgentTraceStore traceStore,
            MultiAgentWritingWorkflowStore workflowStore,
            TaskExecutor taskExecutor,
            AiChatGateway gateway) {
        AiProviderCatalog catalog = providerCatalog();
        return new MultiAgentWritingService(
                new AgentRunPlanService(catalog),
                new AgentRunExecutionService(
                        traceStore,
                        new InMemoryAgentConcurrencyGuard(),
                        gateway,
                        catalog,
                        Clock.systemUTC()),
                workflowStore,
                taskExecutor);
    }

    /**
     * Creates enabled provider settings for deterministic unit execution.
     */
    private static AiProviderCatalog providerCatalog() {
        AiProviderProperties properties = new AiProviderProperties();
        properties.setDefaultProvider("dashscope");
        properties.getDashscope().setApiKey("dashscope-key");
        properties.getDashscope().setChatModel("qwen3.6-flash");
        return new AiProviderCatalog(properties);
    }

    /**
     * Captures model requests and returns configured outcomes.
     */
    private static final class CapturingGateway implements AiChatGateway {
        private final List<AiChatResult> outcomes;
        private final List<AiChatRequest> requests = new ArrayList<>();
        private int index;

        private CapturingGateway(List<AiChatResult> outcomes) {
            this.outcomes = outcomes;
        }

        @Override
        public AiChatResult call(AiChatRequest request) {
            requests.add(request);
            return outcomes.get(index++);
        }

        private List<AiChatRequest> requests() {
            return requests;
        }
    }

    /**
     * Captures one background task so the test controls when async execution proceeds.
     */
    private static final class CapturingTaskExecutor implements TaskExecutor {
        private final List<Runnable> tasks = new ArrayList<>();

        @Override
        public void execute(Runnable task) {
            tasks.add(task);
        }

        private void runNext() {
            tasks.removeFirst().run();
        }
    }

    /**
     * Captures workflow status snapshots written by the service.
     */
    private static final class CapturingWorkflowStore implements MultiAgentWritingWorkflowStore {
        private final List<MultiAgentWritingWorkflowRecord> saved = new ArrayList<>();

        @Override
        public MultiAgentWritingWorkflowRecord save(MultiAgentWritingWorkflowRecord record) {
            MultiAgentWritingWorkflowRecord normalized = record.normalize();
            saved.add(normalized);
            return normalized;
        }

        @Override
        public Optional<MultiAgentWritingWorkflowRecord> findVisible(String workflowId, RequestSubject subject) {
            return saved.stream()
                    .filter(record -> record.workflowId().equals(workflowId))
                    .findFirst();
        }

        private List<MultiAgentWritingWorkflowRecord> saved() {
            return saved;
        }
    }
}
