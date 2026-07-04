package com.doob.mathagent.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.doob.mathagent.agent.controller.MultiAgentWritingController;
import com.doob.mathagent.agent.dto.MultiAgentWritingRequest;
import com.doob.mathagent.agent.service.AgentRunExecutionService;
import com.doob.mathagent.agent.service.AgentRunPlanService;
import com.doob.mathagent.agent.service.AgentTraceQueryService;
import com.doob.mathagent.agent.service.AgentTraceRecord;
import com.doob.mathagent.agent.service.InMemoryAgentConcurrencyGuard;
import com.doob.mathagent.agent.service.InMemoryAgentTraceStore;
import com.doob.mathagent.agent.service.InMemoryMultiAgentWritingWorkflowStore;
import com.doob.mathagent.agent.service.MultiAgentWritingService;
import com.doob.mathagent.agent.service.MultiAgentWritingWorkflowRecord;
import com.doob.mathagent.agent.vo.AgentRunExecuteResponse;
import com.doob.mathagent.agent.vo.MultiAgentWritingResponse;
import com.doob.mathagent.agent.vo.MultiAgentWritingTraceResponse;
import com.doob.mathagent.infrastructure.ai.AiProviderCatalog;
import com.doob.mathagent.infrastructure.ai.AiProviderProperties;
import com.doob.mathagent.infrastructure.security.RequestSubject;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class MultiAgentWritingControllerTest {

    @Test
    void rejectsWritingWithoutCapabilityToken() {
        MultiAgentWritingController controller = new MultiAgentWritingController(
                writingService(new InMemoryAgentTraceStore()),
                new AgentTraceQueryService(new InMemoryAgentTraceStore()),
                request -> new RequestSubject("school-a", "teacher", "teacher-1", "device-1"),
                (token, action, path, requestHash, subject) -> false);

        assertThatThrownBy(() -> controller.run(request(), null))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Capability token required");
    }

    @Test
    void runsWritingAfterCapabilityVerificationUsingBackendSubject() {
        InMemoryAgentTraceStore traceStore = new InMemoryAgentTraceStore();
        List<String> capabilityChecks = new ArrayList<>();
        MultiAgentWritingService writingService = writingService(traceStore);
        MultiAgentWritingController controller = new MultiAgentWritingController(
                writingService,
                new AgentTraceQueryService(traceStore),
                request -> new RequestSubject("school-a", "teacher", "teacher-1", "device-1"),
                (token, action, path, requestHash, subject) -> {
                    capabilityChecks.add(action + "|" + path + "|" + subject.subjectId());
                    return true;
                });

        MultiAgentWritingResponse response = controller.run(request(), null);

        assertThat(response.subjectId()).isEqualTo("teacher-1");
        assertThat(response.stages()).hasSize(3);
        assertThat(capabilityChecks)
                .containsExactly("agent-run:CoursewareAgent|/api/agents/writing/courseware|teacher-1");
        MultiAgentWritingResponse recovered = controller.get(response.workflowId(), null);
        assertThat(recovered.status()).isEqualTo("COMPLETED");
        assertThat(recovered.subjectId()).isEqualTo("teacher-1");
        assertThat(recovered.totalUsage().totalTokens()).isEqualTo(response.totalUsage().totalTokens());
    }

    @Test
    void startsAsyncWritingWithCapabilityPathBoundToAsyncEndpoint() {
        InMemoryAgentTraceStore traceStore = new InMemoryAgentTraceStore();
        List<String> capabilityChecks = new ArrayList<>();
        MultiAgentWritingService writingService = writingService(traceStore);
        MultiAgentWritingController controller = new MultiAgentWritingController(
                writingService,
                new AgentTraceQueryService(traceStore),
                request -> new RequestSubject("school-a", "teacher", "teacher-1", "device-1"),
                (token, action, path, requestHash, subject) -> {
                    capabilityChecks.add(action + "|" + path + "|" + subject.subjectId());
                    return true;
                });

        MultiAgentWritingResponse started = controller.startAsync(request(), null);
        MultiAgentWritingResponse recovered = controller.get(started.workflowId(), null);

        assertThat(started.status()).isEqualTo("RUNNING");
        assertThat(started.subjectId()).isEqualTo("teacher-1");
        assertThat(recovered.status()).isEqualTo("COMPLETED");
        assertThat(capabilityChecks)
                .containsExactly("agent-run:CoursewareAgent|/api/agents/writing/courseware/async|teacher-1");
    }

    @Test
    void resumesFailedWritingWithCapabilityPathBoundToWorkflowId() {
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
        List<String> capabilityChecks = new ArrayList<>();
        MultiAgentWritingController controller = new MultiAgentWritingController(
                writingService(traceStore, workflowStore),
                new AgentTraceQueryService(traceStore),
                request -> new RequestSubject("school-a", "teacher", "teacher-1", "device-1"),
                (token, action, path, requestHash, subject) -> {
                    capabilityChecks.add(action + "|" + path + "|" + subject.subjectId());
                    return true;
                });

        MultiAgentWritingResponse response = controller.resume("workflow-resume-abc", request(), null);

        assertThat(response.status()).isEqualTo("COMPLETED");
        assertThat(response.stages()).extracting(MultiAgentWritingResponse.StageResult::stageCode)
                .containsExactly("draft", "review", "format");
        assertThat(capabilityChecks)
                .containsExactly("agent-run:CoursewareAgent|/api/agents/writing/workflow-resume-abc/resume|teacher-1");
    }

    @Test
    void rejectsAsyncWritingWithoutCapabilityToken() {
        MultiAgentWritingController controller = new MultiAgentWritingController(
                writingService(new InMemoryAgentTraceStore()),
                new AgentTraceQueryService(new InMemoryAgentTraceStore()),
                request -> new RequestSubject("school-a", "teacher", "teacher-1", "device-1"),
                (token, action, path, requestHash, subject) -> false);

        assertThatThrownBy(() -> controller.startAsync(request(), null))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Capability token required");
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
                request -> new RequestSubject("school-a", "teacher", "teacher-1", "device-1"),
                (token, action, path, requestHash, subject) -> true);

        MultiAgentWritingTraceResponse response = controller.traces("workflow-123", null);

        assertThat(response.workflowId()).isEqualTo("workflow-123");
        assertThat(response.stageCount()).isEqualTo(3);
        assertThat(response.totalUsage().totalTokens()).isEqualTo(23);
        assertThat(response.stages()).extracting(stage -> stage.planId())
                .containsExactly("workflow-123:draft", "workflow-123:review", "workflow-123:format");
        assertThat(response.toString()).doesNotContain("teacher-2");
    }

    /**
     * Builds the controller request.
     */
    private static MultiAgentWritingRequest request() {
        return new MultiAgentWritingRequest(
                "teacher handout",
                "space vector angle",
                List.of("PUBLIC_TEXTBOOK:space-vector:angle"),
                false,
                "dashscope",
                "qwen3.6-flash");
    }

    /**
     * Builds a writing service with real planning and deterministic test model execution.
     */
    private static MultiAgentWritingService writingService(InMemoryAgentTraceStore traceStore) {
        return writingService(traceStore, new InMemoryMultiAgentWritingWorkflowStore());
    }

    /**
     * Builds a writing service with an explicit workflow store for recovery tests.
     */
    private static MultiAgentWritingService writingService(
            InMemoryAgentTraceStore traceStore,
            InMemoryMultiAgentWritingWorkflowStore workflowStore) {
        AiProviderProperties properties = new AiProviderProperties();
        properties.setDefaultProvider("dashscope");
        properties.getDashscope().setApiKey("dashscope-key");
        properties.getDashscope().setChatModel("qwen3.6-flash");
        AiProviderCatalog catalog = new AiProviderCatalog(properties);
        return new MultiAgentWritingService(
                new AgentRunPlanService(catalog),
                AgentRunExecutionServiceFixture.deterministicModelService(traceStore, new InMemoryAgentConcurrencyGuard()),
                workflowStore,
                new org.springframework.core.task.SyncTaskExecutor());
    }

    /**
     * Builds a safe trace row linked to one writing workflow stage.
     */
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
