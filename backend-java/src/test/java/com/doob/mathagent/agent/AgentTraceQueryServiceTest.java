package com.doob.mathagent.agent;

import static org.assertj.core.api.Assertions.assertThat;

import com.doob.mathagent.agent.dto.AgentTraceQueryRequest;
import com.doob.mathagent.agent.service.AgentTraceQueryService;
import com.doob.mathagent.agent.service.AgentTraceRecord;
import com.doob.mathagent.agent.service.InMemoryAgentTraceStore;
import com.doob.mathagent.agent.vo.AgentTraceDiagnosticSummaryResponse;
import com.doob.mathagent.agent.vo.AgentRunExecuteResponse;
import com.doob.mathagent.agent.vo.AgentTraceResponse;
import com.doob.mathagent.agent.vo.AgentTraceUsageSummaryResponse;
import com.doob.mathagent.infrastructure.security.RequestSubject;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class AgentTraceQueryServiceTest {

    @Test
    void studentOnlyListsOwnAgentTracesFromBackendSubject() {
        InMemoryAgentTraceStore store = new InMemoryAgentTraceStore();
        store.save(trace("trace-student-1", "student", "student-1", "StudentTutorAgent"));
        store.save(trace("trace-student-2", "student", "student-2", "StudentTutorAgent"));
        AgentTraceQueryService service = new AgentTraceQueryService(store);

        List<AgentTraceResponse> traces = service.list(
                new AgentTraceQueryRequest("StudentTutorAgent", "COMPLETED", 20),
                new RequestSubject("school-a", "student", "student-1", "device-1"));

        assertThat(traces).extracting(AgentTraceResponse::traceId).containsExactly("trace-student-1");
        assertThat(traces.getFirst().subjectId()).isEqualTo("student-1");
        assertThat(traces.getFirst().actualUsage().totalTokens()).isEqualTo(18);
        assertThat(traces.getFirst().stageTimings()).extracting(AgentRunExecuteResponse.StageTiming::stage)
                .containsExactly("model_call");
        assertThat(traces.getFirst().message()).contains("model response recorded");
    }

    @Test
    void adminListsTenantAgentTracesWithoutFrontendSubjectId() {
        InMemoryAgentTraceStore store = new InMemoryAgentTraceStore();
        store.save(trace("trace-teacher-1", "teacher", "teacher-1", "CoursewareAgent"));
        store.save(trace("trace-student-1", "student", "student-1", "StudentTutorAgent"));
        AgentTraceQueryService service = new AgentTraceQueryService(store);

        List<AgentTraceResponse> traces = service.list(
                new AgentTraceQueryRequest(null, "COMPLETED", 20),
                new RequestSubject("school-a", "admin", "admin-1", "device-1"));

        assertThat(traces).extracting(AgentTraceResponse::traceId)
                .containsExactly("trace-student-1", "trace-teacher-1");
    }

    @Test
    void findRejectsTraceOwnedByAnotherSubject() {
        InMemoryAgentTraceStore store = new InMemoryAgentTraceStore();
        store.save(trace("trace-student-2", "student", "student-2", "StudentTutorAgent"));
        AgentTraceQueryService service = new AgentTraceQueryService(store);

        Optional<AgentTraceResponse> trace = service.find(
                "trace-student-2",
                new RequestSubject("school-a", "student", "student-1", "device-1"));

        assertThat(trace).isEmpty();
    }

    @Test
    void summarizesOfficialUsageOnlyForBackendVisibleTraces() {
        InMemoryAgentTraceStore store = new InMemoryAgentTraceStore();
        store.save(trace("trace-student-1", "student", "student-1", "StudentTutorAgent"));
        store.save(traceWithUsage("trace-student-1b", "student", "student-1", "StudentTutorAgent",
                "dashscope", "qwen3.6-flash", 5, 3, 8));
        store.save(traceWithUsage("trace-student-2", "student", "student-2", "StudentTutorAgent",
                "openai", "gpt-5.4", 100, 50, 150));
        AgentTraceQueryService service = new AgentTraceQueryService(store);

        AgentTraceUsageSummaryResponse summary = service.usageSummary(
                new AgentTraceQueryRequest("StudentTutorAgent", "COMPLETED", 20),
                new RequestSubject("school-a", "student", "student-1", "device-1"));

        assertThat(summary.runCount()).isEqualTo(2);
        assertThat(summary.totalUsage().promptTokens()).isEqualTo(16);
        assertThat(summary.totalUsage().completionTokens()).isEqualTo(10);
        assertThat(summary.totalUsage().totalTokens()).isEqualTo(26);
        assertThat(summary.modelUsages()).extracting(AgentTraceUsageSummaryResponse.ModelUsage::modelCode)
                .containsExactly("gpt-5.4", "qwen3.6-flash");
    }

    @Test
    void filtersVisibleTracesByPlanIdForTeachingTaskRecovery() {
        InMemoryAgentTraceStore store = new InMemoryAgentTraceStore();
        store.save(traceWithPlanId("trace-task-1", "teacher", "teacher-1", "CoursewareAgent", "task-1"));
        store.save(traceWithPlanId("trace-task-2", "teacher", "teacher-1", "CoursewareAgent", "task-2"));
        store.save(traceWithPlanId("trace-other-owner", "teacher", "teacher-2", "CoursewareAgent", "task-1"));
        AgentTraceQueryService service = new AgentTraceQueryService(store);

        List<AgentTraceResponse> traces = service.list(
                new AgentTraceQueryRequest("CoursewareAgent", "COMPLETED", "task-1", 20),
                new RequestSubject("school-a", "teacher", "teacher-1", "device-1"));

        assertThat(traces).extracting(AgentTraceResponse::traceId).containsExactly("trace-task-1");
        assertThat(traces.getFirst().planId()).isEqualTo("task-1");
    }

    @Test
    void filtersVisibleTracesByPlanIdPrefixForWorkflowRecovery() {
        InMemoryAgentTraceStore store = new InMemoryAgentTraceStore();
        store.save(traceWithPlanId("trace-draft", "teacher", "teacher-1", "CoursewareAgent", "workflow-1:draft"));
        store.save(traceWithPlanId("trace-review", "teacher", "teacher-1", "QualityCheckAgent", "workflow-1:review"));
        store.save(traceWithPlanId("trace-other-workflow", "teacher", "teacher-1", "CoursewareAgent", "workflow-2:draft"));
        store.save(traceWithPlanId("trace-other-owner", "teacher", "teacher-2", "CoursewareAgent", "workflow-1:draft"));
        AgentTraceQueryService service = new AgentTraceQueryService(store);

        List<AgentTraceResponse> traces = service.list(
                new AgentTraceQueryRequest(null, "COMPLETED", null, "workflow-1:", 20),
                new RequestSubject("school-a", "teacher", "teacher-1", "device-1"));

        assertThat(traces).extracting(AgentTraceResponse::traceId)
                .containsExactly("trace-draft", "trace-review");
        assertThat(traces).extracting(AgentTraceResponse::planId)
                .containsExactly("workflow-1:draft", "workflow-1:review");
    }

    @Test
    void summarizesSafeDiagnosticEventsForBackendVisibleTraces() {
        InMemoryAgentTraceStore store = new InMemoryAgentTraceStore();
        store.save(traceWithDiagnostics(
                "trace-recovered",
                "teacher",
                "teacher-1",
                "CoursewareAgent",
                "openai",
                "gpt-5.4",
                List.of(
                        diagnostic("MODEL_CALL_SUCCEEDED", "openai", "gpt-5.4", 0, true),
                        diagnostic("JSON_PARSE_FAILED", "openai", "gpt-5.4", 0, true),
                        diagnostic("RETRY_SCHEDULED", "openai", "gpt-5.4", 1, true),
                        diagnostic("MODEL_CALL_SUCCEEDED", "openai", "gpt-5.4", 1, true),
                        diagnostic("JSON_PARSE_SUCCEEDED", "openai", "gpt-5.4", 1, false))));
        store.save(traceWithDiagnostics(
                "trace-rotated",
                "teacher",
                "teacher-1",
                "CoursewareAgent",
                "dashscope",
                "qwen3.6-flash",
                List.of(
                        diagnostic("MODEL_CALL_FAILED", "dashscope", "qwen3.6-flash", 0, true),
                        diagnostic("PROVIDER_ROTATED", "openai", "gpt-5.4", 0, true))));
        store.save(traceWithDiagnostics(
                "trace-other-owner",
                "teacher",
                "teacher-2",
                "CoursewareAgent",
                "openai",
                "gpt-5.4",
                List.of(diagnostic("JSON_PARSE_FAILED", "openai", "gpt-5.4", 0, true))));
        AgentTraceQueryService service = new AgentTraceQueryService(store);

        AgentTraceDiagnosticSummaryResponse summary = service.diagnosticSummary(
                new AgentTraceQueryRequest("CoursewareAgent", "COMPLETED", 20),
                new RequestSubject("school-a", "teacher", "teacher-1", "device-1"));

        assertThat(summary.runCount()).isEqualTo(2);
        assertThat(summary.diagnosticEventCount()).isEqualTo(7);
        assertThat(summary.jsonParseFailureCount()).isEqualTo(1);
        assertThat(summary.retryScheduledCount()).isEqualTo(1);
        assertThat(summary.retryRecoveredCount()).isEqualTo(1);
        assertThat(summary.providerRotationCount()).isEqualTo(1);
        assertThat(summary.modelCallFailureCount()).isEqualTo(1);
        assertThat(summary.modelDiagnostics()).extracting(AgentTraceDiagnosticSummaryResponse.ModelDiagnostic::modelCode)
                .containsExactly("gpt-5.4", "qwen3.6-flash");
        assertThat(summary.modelDiagnostics().getFirst().retryRecoveredCount()).isEqualTo(1);
    }

    private static AgentTraceRecord trace(String traceId, String subjectType, String subjectId, String agentCode) {
        return traceWithUsage(traceId, subjectType, subjectId, agentCode, "openai", "gpt-5.4", 11, 7, 18);
    }

    private static AgentTraceRecord traceWithPlanId(
            String traceId,
            String subjectType,
            String subjectId,
            String agentCode,
            String planId) {
        return traceRecord(traceId, subjectType, subjectId, agentCode, planId, "openai", "gpt-5.4", 11, 7, 18);
    }

    private static AgentTraceRecord traceWithUsage(
            String traceId,
            String subjectType,
            String subjectId,
            String agentCode,
            String providerName,
            String modelCode,
            int promptTokens,
            int completionTokens,
            int totalTokens) {
        return new AgentTraceRecord(
                traceId,
                "plan-1",
                Instant.parse("2026-06-29T00:00:00Z"),
                "school-a",
                subjectType,
                subjectId,
                agentCode,
                providerName,
                modelCode,
                "COMPLETED",
                0.46,
                List.of("tool:search:textbook"),
                List.of("PUBLIC_TEXTBOOK"),
                List.of("textbook:chapter-1"),
                List.of(new AgentRunExecuteResponse.StageTiming("model_call", 12)),
                new AgentRunExecuteResponse.TokenUsage(promptTokens, completionTokens, totalTokens),
                "Live model response recorded");
    }

    private static AgentTraceRecord traceWithDiagnostics(
            String traceId,
            String subjectType,
            String subjectId,
            String agentCode,
            String providerName,
            String modelCode,
            List<AgentTraceRecord.DiagnosticEvent> diagnosticEvents) {
        return new AgentTraceRecord(
                traceId,
                "plan-1",
                Instant.parse("2026-06-29T00:00:00Z"),
                "school-a",
                subjectType,
                subjectId,
                agentCode,
                providerName,
                modelCode,
                "COMPLETED",
                0.46,
                List.of("tool:search:textbook"),
                List.of("PUBLIC_TEXTBOOK"),
                List.of("textbook:chapter-1"),
                List.of(new AgentRunExecuteResponse.StageTiming("model_call", 12)),
                new AgentRunExecuteResponse.TokenUsage(11, 7, 18),
                "Live model response recorded",
                diagnosticEvents);
    }

    private static AgentTraceRecord.DiagnosticEvent diagnostic(
            String eventType,
            String providerName,
            String modelCode,
            int attemptNo,
            boolean retryable) {
        return new AgentTraceRecord.DiagnosticEvent(
                eventType,
                providerName,
                modelCode,
                attemptNo,
                retryable,
                eventType.toLowerCase());
    }

    private static AgentTraceRecord traceRecord(
            String traceId,
            String subjectType,
            String subjectId,
            String agentCode,
            String planId,
            String providerName,
            String modelCode,
            int promptTokens,
            int completionTokens,
            int totalTokens) {
        return new AgentTraceRecord(
                traceId,
                planId,
                Instant.parse("2026-06-29T00:00:00Z"),
                "school-a",
                subjectType,
                subjectId,
                agentCode,
                providerName,
                modelCode,
                "COMPLETED",
                0.46,
                List.of("tool:search:textbook"),
                List.of("PUBLIC_TEXTBOOK"),
                List.of("textbook:chapter-1"),
                List.of(new AgentRunExecuteResponse.StageTiming("model_call", 12)),
                new AgentRunExecuteResponse.TokenUsage(promptTokens, completionTokens, totalTokens),
                "Live model response recorded");
    }
}
