package com.doob.mathagent.agent;

import static org.assertj.core.api.Assertions.assertThat;

import com.doob.mathagent.agent.controller.MultiAgentWritingController;
import com.doob.mathagent.agent.dto.MultiAgentWritingRequest;
import com.doob.mathagent.agent.service.AgentTraceQueryService;
import com.doob.mathagent.agent.service.AgentTraceRecord;
import com.doob.mathagent.agent.service.InMemoryAgentTraceStore;
import com.doob.mathagent.agent.service.InMemoryMultiAgentWritingWorkflowStore;
import com.doob.mathagent.agent.service.MultiAgentWritingService;
import com.doob.mathagent.agent.service.MultiAgentWritingWorkflowRecord;
import com.doob.mathagent.agent.service.PythonHandoutClient;
import com.doob.mathagent.agent.worker.AgentWorkerTask;
import com.doob.mathagent.agent.worker.AgentWorkerTaskDispatchService;
import com.doob.mathagent.agent.worker.AgentWorkerTaskStore;
import com.doob.mathagent.agent.vo.AgentRunExecuteResponse;
import com.doob.mathagent.agent.vo.MultiAgentWritingResponse;
import com.doob.mathagent.agent.vo.MultiAgentWritingTraceResponse;
import com.doob.mathagent.infrastructure.security.RequestSubject;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class MultiAgentWritingControllerTest {

    @Test
    void runsWritingWithBackendSession() {
        MultiAgentWritingController controller = new MultiAgentWritingController(
                writingService(new InMemoryAgentTraceStore()),
                new AgentTraceQueryService(new InMemoryAgentTraceStore()),
                request -> new RequestSubject("school-a", "teacher", "teacher-1", "device-1"));

        assertThat(controller.run(request(), null).status()).isEqualTo("COMPLETED");
    }

    @Test
    void runsWritingUsingBackendSubjectWithoutCapabilityVerification() {
        InMemoryAgentTraceStore traceStore = new InMemoryAgentTraceStore();
        MultiAgentWritingService writingService = writingService(traceStore);
        MultiAgentWritingController controller = new MultiAgentWritingController(
                writingService,
                new AgentTraceQueryService(traceStore),
                request -> new RequestSubject("school-a", "teacher", "teacher-1", "device-1"));

        MultiAgentWritingResponse response = controller.run(request(), null);

        assertThat(response.subjectId()).isEqualTo("teacher-1");
        assertThat(response.stages()).extracting(MultiAgentWritingResponse.StageResult::stageCode)
                .containsExactly("resource_curation", "teacher_writer", "student_writer", "lecture_writer");
        MultiAgentWritingResponse recovered = controller.get(response.workflowId(), null);
        assertThat(recovered.status()).isEqualTo("COMPLETED");
        assertThat(recovered.subjectId()).isEqualTo("teacher-1");
        assertThat(recovered.totalUsage().totalTokens()).isEqualTo(response.totalUsage().totalTokens());
    }

    @Test
    void startsAsyncWritingUsingBackendSessionWithoutCapabilityVerification() {
        InMemoryAgentTraceStore traceStore = new InMemoryAgentTraceStore();
        MultiAgentWritingService writingService = writingService(traceStore);
        MultiAgentWritingController controller = new MultiAgentWritingController(
                writingService,
                new AgentTraceQueryService(traceStore),
                request -> new RequestSubject("school-a", "teacher", "teacher-1", "device-1"));

        MultiAgentWritingResponse started = controller.startAsync(request(), null);
        writingService.executeDispatchedPython(
                started.workflowId(), request(), new RequestSubject("school-a", "teacher", "teacher-1", "agent-worker"));
        MultiAgentWritingResponse recovered = controller.get(started.workflowId(), null);

        assertThat(started.status()).isEqualTo("RUNNING");
        assertThat(started.subjectId()).isEqualTo("teacher-1");
        assertThat(recovered.status()).isEqualTo("COMPLETED");
    }

    @Test
    void resumesFailedWritingUsingBackendSessionWithoutCapabilityVerification() {
        InMemoryAgentTraceStore traceStore = new InMemoryAgentTraceStore();
        InMemoryMultiAgentWritingWorkflowStore workflowStore = new InMemoryMultiAgentWritingWorkflowStore();
        workflowStore.save(new MultiAgentWritingWorkflowRecord(
                "workflow-resume-abc",
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
        MultiAgentWritingService writingService = writingService(traceStore, workflowStore);
        MultiAgentWritingController controller = new MultiAgentWritingController(
                writingService,
                new AgentTraceQueryService(traceStore),
                request -> new RequestSubject("school-a", "teacher", "teacher-1", "device-1"));

        MultiAgentWritingResponse response = controller.resume("workflow-resume-abc", request(), null);
        writingService.executeDispatchedPython(
                response.workflowId(), request(), new RequestSubject("school-a", "teacher", "teacher-1", "agent-worker"));

        MultiAgentWritingResponse recovered = controller.get(response.workflowId(), null);
        assertThat(response.status()).isEqualTo("RUNNING");
        assertThat(recovered.status()).isEqualTo("COMPLETED");
        assertThat(recovered.stages()).extracting(MultiAgentWritingResponse.StageResult::stageCode)
                .containsExactly("resource_curation", "teacher_writer", "student_writer", "lecture_writer");
    }

    @Test
    void startsAsyncWritingWithBackendSession() {
        MultiAgentWritingController controller = new MultiAgentWritingController(
                writingService(new InMemoryAgentTraceStore()),
                new AgentTraceQueryService(new InMemoryAgentTraceStore()),
                request -> new RequestSubject("school-a", "teacher", "teacher-1", "device-1"));

        assertThat(controller.startAsync(request(), null).status()).isEqualTo("RUNNING");
    }

    @Test
    void recoversOnlyOwnedWorkflowStageTracesInWritingOrder() {
        InMemoryAgentTraceStore traceStore = new InMemoryAgentTraceStore();
        traceStore.save(trace("trace-format", "workflow-123:format", "teacher-1", "HandoutFormatterAgent", 7));
        traceStore.save(trace("trace-draft", "workflow-123:draft", "teacher-1", "CoursewareAgent", 11));
        traceStore.save(trace("trace-other", "workflow-123:review", "teacher-2", "QualityCheckAgent", 100));
        traceStore.save(trace("trace-review", "workflow-123:review", "teacher-1", "QualityCheckAgent", 5));
        MultiAgentWritingController controller = new MultiAgentWritingController(
                writingService(traceStore),
                new AgentTraceQueryService(traceStore),
                request -> new RequestSubject("school-a", "teacher", "teacher-1", "device-1"));

        MultiAgentWritingTraceResponse response = controller.traces("workflow-123", null);

        assertThat(response.workflowId()).isEqualTo("workflow-123");
        assertThat(response.stageCount()).isEqualTo(3);
        assertThat(response.totalUsage().totalTokens()).isEqualTo(23);
        assertThat(response.stages()).extracting(stage -> stage.planId())
                .containsExactly("workflow-123:draft", "workflow-123:review", "workflow-123:format");
        assertThat(response.toString()).doesNotContain("teacher-2");
    }

    private static MultiAgentWritingRequest request() {
        return new MultiAgentWritingRequest(
                "teacher handout",
                "space vector angle",
                List.of("PUBLIC_TEXTBOOK:space-vector:angle"),
                false,
                "openai",
                "gpt-5.6-luna");
    }

    private static MultiAgentWritingService writingService(InMemoryAgentTraceStore traceStore) {
        return writingService(traceStore, new InMemoryMultiAgentWritingWorkflowStore());
    }

    private static MultiAgentWritingService writingService(
            InMemoryAgentTraceStore traceStore,
            InMemoryMultiAgentWritingWorkflowStore workflowStore) {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("math-agent.python-handout.enabled", "true");
        return new MultiAgentWritingService(
                workflowStore,
                new AgentWorkerTaskDispatchService(workflowStore, new TestWorkerTaskStore(), new TestOutboxStore()),
                environment,
                new CompletedPythonHandoutClient(environment));
    }

    private static final class CompletedPythonHandoutClient extends PythonHandoutClient {
        private CompletedPythonHandoutClient(MockEnvironment environment) {
            super(environment, new ObjectMapper());
        }

        @Override
        public PythonHandoutResult execute(
                String workflowId, MultiAgentWritingRequest request, String traceId, boolean resume) {
            AgentRunExecuteResponse.TokenUsage usage = new AgentRunExecuteResponse.TokenUsage(8, 4, 12);
            return new PythonHandoutResult("COMPLETED", List.of(
                    stage("resource_curation", "TeacherAssistantAgent", workflowId, usage),
                    stage("teacher_writer", "CoursewareAgent", workflowId, usage),
                    stage("student_writer", "TeacherAssistantAgent", workflowId, usage),
                    stage("lecture_writer", "HandoutFormatterAgent", workflowId, usage)), usage, "handout-v1", "");
        }

        private static MultiAgentWritingResponse.StageResult stage(
                String stageCode, String agentCode, String workflowId, AgentRunExecuteResponse.TokenUsage usage) {
            return new MultiAgentWritingResponse.StageResult(
                    stageCode, agentCode, workflowId + ":" + stageCode, "python-langgraph", "test-model",
                    "COMPLETED", usage, "Python LangGraph node completed.", "{}", 1L);
        }
    }

    private static final class TestWorkerTaskStore extends AgentWorkerTaskStore {
        private TestWorkerTaskStore() {
            super(null);
        }

        @Override
        public AgentWorkerTask create(String workflowId, String tenantId, String agentCode, String stageCode, String requestJson) {
            return new AgentWorkerTask(
                    workflowId + ":task", workflowId, tenantId, agentCode, stageCode, "QUEUED", 0, 1,
                    null, null, null, requestJson, null, Instant.now(), Instant.now());
        }
    }

    private static final class TestOutboxStore implements com.doob.mathagent.agent.worker.AgentWorkerTaskOutboxStore {
        @Override public void enqueue(AgentWorkerTask task) { }
        @Override public java.util.List<com.doob.mathagent.agent.worker.AgentWorkerTaskOutboxEvent> claimReady(String publisherId, Instant now, java.time.Duration leaseDuration, int limit) { return List.of(); }
        @Override public boolean markPublished(com.doob.mathagent.agent.worker.AgentWorkerTaskOutboxEvent event, Instant publishedAt) { return true; }
        @Override public void releaseForRetry(com.doob.mathagent.agent.worker.AgentWorkerTaskOutboxEvent event, Instant nextAttemptAt, String errorSummary) { }
        @Override public int recoverExpiredPublishing(Instant now) { return 0; }
        @Override public java.util.List<AgentWorkerTask> findOrphanQueued(Instant olderThan, int limit) { return List.of(); }
        @Override public long pendingCount() { return 0; }
        @Override public Instant oldestPendingCreatedAt() { return null; }
    }

    private static AgentTraceRecord trace(
            String traceId,
            String planId,
            String subjectId,
            String agentCode,
            int totalTokens) {
        return new AgentTraceRecord(
                traceId,
                planId,
                Instant.parse("2026-06-29T00:00:00Z"),
                "school-a",
                "teacher",
                subjectId,
                agentCode,
                "dashscope",
                "qwen3.6-flash",
                "COMPLETED",
                0.0,
                List.of("tool:courseware:generate"),
                List.of("PUBLIC_TEXTBOOK"),
                List.of("PUBLIC_TEXTBOOK:space-vector:angle"),
                List.of(new AgentRunExecuteResponse.StageTiming("model_call", 12)),
                new AgentRunExecuteResponse.TokenUsage(totalTokens - 2, 2, totalTokens),
                "safe trace");
    }
}
