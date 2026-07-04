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
import com.doob.mathagent.agent.vo.AgentRunExecuteResponse;
import com.doob.mathagent.agent.vo.MultiAgentWritingResponse;
import com.doob.mathagent.infrastructure.ai.AiProviderCatalog;
import com.doob.mathagent.infrastructure.ai.AiProviderProperties;
import com.doob.mathagent.infrastructure.security.RequestSubject;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.TaskExecutor;

class MultiAgentWritingServiceTest {

    @Test
    void runsThreeWritingAgentsAndPersistsRecoverableTraces() {
        CapturingGateway gateway = new CapturingGateway(List.of(
                new AiChatResult("dashscope", "qwen3.6-flash", 11, 7, 18, "draft recorded", "teacher draft"),
                new AiChatResult("dashscope", "qwen3.6-flash", 9, 5, 14, "review recorded", reviewJson("quality review")),
                new AiChatResult("dashscope", "qwen3.6-flash", 8, 4, 12, "format recorded", "formatted handout")));
        InMemoryAgentTraceStore traceStore = new InMemoryAgentTraceStore();
        InMemoryMultiAgentWritingWorkflowStore workflowStore = new InMemoryMultiAgentWritingWorkflowStore();
        MultiAgentWritingService service = service(traceStore, workflowStore, gateway);

        MultiAgentWritingResponse response = service.run(request(false), subject());

        assertThat(response.status()).isEqualTo("COMPLETED");
        assertThat(response.stages()).extracting(MultiAgentWritingResponse.StageResult::agentCode)
                .containsExactly("CoursewareAgent", "QualityCheckAgent", "HandoutFormatterAgent");
        assertThat(response.totalUsage().totalTokens()).isEqualTo(44);
        assertThat(response.stages()).extracting(MultiAgentWritingResponse.StageResult::generatedContent)
                .containsExactly("teacher draft", reviewJson("quality review"), "formatted handout");
        assertThat(service.artifact(response.workflowId(), subject()).mergedMarkdown())
                .contains("teacher draft", "formatted handout");
        assertThat(gateway.requests()).extracting(AiChatRequest::agentCode)
                .containsExactly("CoursewareAgent", "QualityCheckAgent", "HandoutFormatterAgent");
        assertThat(traceStore.find(response.stages().get(1).traceId()).orElseThrow().diagnosticEvents())
                .extracting(com.doob.mathagent.agent.service.AgentTraceRecord.DiagnosticEvent::eventType)
                .containsExactly("MODEL_CALL_SUCCEEDED", "JSON_PARSE_SUCCEEDED");
        assertThat(service.find(response.workflowId(), subject()).orElseThrow().status()).isEqualTo("COMPLETED");
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
                        new MultiAgentWritingRequest("student handout", "function", List.of(), false, "", ""),
                        new RequestSubject("school-a", "student", "student-1", "device-1")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("teacher or admin");
        assertThat(gateway.requests()).isEmpty();
    }

    @Test
    void startsAsyncWorkflowAndCompletesWhenExecutorRuns() {
        CapturingGateway gateway = new CapturingGateway(List.of(
                new AiChatResult("dashscope", "qwen3.6-flash", 11, 7, 18, "draft recorded", "draft"),
                new AiChatResult("dashscope", "qwen3.6-flash", 9, 5, 14, "review recorded", reviewJson("review")),
                new AiChatResult("dashscope", "qwen3.6-flash", 8, 4, 12, "format recorded", "format")));
        InMemoryMultiAgentWritingWorkflowStore workflowStore = new InMemoryMultiAgentWritingWorkflowStore();
        CapturingTaskExecutor taskExecutor = new CapturingTaskExecutor();
        MultiAgentWritingService service = service(
                new InMemoryAgentTraceStore(),
                workflowStore,
                taskExecutor,
                gateway);

        MultiAgentWritingResponse started = service.startAsync(request(false), subject());

        assertThat(started.status()).isEqualTo("RUNNING");
        assertThat(gateway.requests()).isEmpty();

        taskExecutor.runNext();

        MultiAgentWritingResponse completed = service.find(started.workflowId(), subject()).orElseThrow();
        assertThat(completed.status()).isEqualTo("COMPLETED");
        assertThat(completed.stages()).hasSize(3);
        assertThat(completed.totalUsage().totalTokens()).isEqualTo(44);
    }

    @Test
    void resumesFailedWorkflowFromFirstMissingStageWithoutRepeatingCompletedDraft() {
        CapturingGateway gateway = new CapturingGateway(List.of(
                new AiChatResult("dashscope", "qwen3.6-flash", 9, 5, 14, "review recorded", reviewJson("review")),
                new AiChatResult("dashscope", "qwen3.6-flash", 8, 4, 12, "format recorded", "format")));
        InMemoryAgentTraceStore traceStore = new InMemoryAgentTraceStore();
        InMemoryMultiAgentWritingWorkflowStore workflowStore = new InMemoryMultiAgentWritingWorkflowStore();
        workflowStore.save(new MultiAgentWritingWorkflowRecord(
                "workflow-resume-123",
                "school-a",
                "teacher",
                "teacher-1",
                "FAILED",
                Instant.parse("2026-06-30T00:00:00Z"),
                Instant.parse("2026-06-30T00:01:00Z"),
                List.of(new MultiAgentWritingResponse.StageResult(
                        "draft",
                        "CoursewareAgent",
                        "trace-draft",
                        "dashscope",
                        "qwen3.6-flash",
                        "COMPLETED",
                        new AgentRunExecuteResponse.TokenUsage(11, 7, 18),
                        "draft recorded")),
                new AgentRunExecuteResponse.TokenUsage(11, 7, 18),
                "failed after draft"));
        MultiAgentWritingService service = service(traceStore, workflowStore, gateway);

        MultiAgentWritingResponse response = service.resume("workflow-resume-123", request(false), subject());

        assertThat(response.status()).isEqualTo("COMPLETED");
        assertThat(response.stages()).extracting(MultiAgentWritingResponse.StageResult::stageCode)
                .containsExactly("draft", "review", "format");
        assertThat(gateway.requests()).extracting(AiChatRequest::agentCode)
                .containsExactly("QualityCheckAgent", "HandoutFormatterAgent");
        assertThat(response.totalUsage().totalTokens()).isEqualTo(44);
        assertThat(traceStore.find(response.stages().get(1).traceId()).orElseThrow().planId())
                .isEqualTo("workflow-resume-123:review");
        assertThat(traceStore.find(response.stages().get(2).traceId()).orElseThrow().planId())
                .isEqualTo("workflow-resume-123:format");
    }

    @Test
    void persistsFailedWorkflowStatusWithCompletedStages() {
        CapturingGateway gateway = new CapturingGateway(List.of(
                new AiChatResult("dashscope", "qwen3.6-flash", 11, 7, 18, "draft recorded", "draft")));
        CapturingWorkflowStore workflowStore = new CapturingWorkflowStore();
        MultiAgentWritingService service = service(new InMemoryAgentTraceStore(), workflowStore, gateway);

        assertThatThrownBy(() -> service.run(request(false), subject()))
                .isInstanceOf(RuntimeException.class);

        assertThat(gateway.requests()).hasSize(4);
        assertThat(workflowStore.saved()).extracting(MultiAgentWritingWorkflowRecord::status)
                .containsExactly("RUNNING", "RUNNING", "FAILED");
        assertThat(workflowStore.saved().getLast().stages()).hasSize(1);
        assertThat(workflowStore.saved().getLast().message()).contains("failed");
    }

    private static MultiAgentWritingService service(
            InMemoryAgentTraceStore traceStore,
            MultiAgentWritingWorkflowStore workflowStore,
            AiChatGateway gateway) {
        return service(traceStore, workflowStore, Runnable::run, gateway);
    }

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

    private static MultiAgentWritingRequest request(boolean dryRun) {
        return new MultiAgentWritingRequest(
                "teacher handout",
                "space vector angle",
                List.of("PUBLIC_TEXTBOOK:space-vector:angle"),
                dryRun,
                "dashscope",
                "qwen3.6-flash");
    }

    private static RequestSubject subject() {
        return new RequestSubject("school-a", "teacher", "teacher-1", "device-1");
    }

    private static AiProviderCatalog providerCatalog() {
        AiProviderProperties properties = new AiProviderProperties();
        properties.setDefaultProvider("dashscope");
        properties.getDashscope().setApiKey("dashscope-key");
        properties.getDashscope().setChatModel("qwen3.6-flash");
        return new AiProviderCatalog(properties);
    }

    private static String reviewJson(String text) {
        return "{\"review\":\"" + text + "\",\"status\":\"ok\"}";
    }

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
