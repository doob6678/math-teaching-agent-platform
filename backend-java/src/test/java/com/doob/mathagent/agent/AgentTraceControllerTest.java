package com.doob.mathagent.agent;

import static org.assertj.core.api.Assertions.assertThat;

import com.doob.mathagent.agent.controller.AgentTraceController;
import com.doob.mathagent.agent.dto.AgentTraceQueryRequest;
import com.doob.mathagent.agent.service.AgentTraceQueryService;
import com.doob.mathagent.agent.service.AgentTraceRecord;
import com.doob.mathagent.agent.service.InMemoryAgentTraceStore;
import com.doob.mathagent.agent.vo.AgentRunExecuteResponse;
import com.doob.mathagent.agent.vo.AgentTraceDiagnosticSummaryResponse;
import com.doob.mathagent.agent.vo.AgentTraceResponse;
import com.doob.mathagent.agent.vo.AgentTraceUsageSummaryResponse;
import com.doob.mathagent.infrastructure.security.RequestSubject;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class AgentTraceControllerTest {

    @Test
    void listsAgentTracesUsingBackendSubjectOnly() {
        InMemoryAgentTraceStore store = new InMemoryAgentTraceStore();
        store.save(new AgentTraceRecord(
                "trace-1",
                "plan-1",
                Instant.parse("2026-06-29T00:00:00Z"),
                "school-a",
                "teacher",
                "teacher-1",
                "CoursewareAgent",
                "openai",
                "gpt-5.4",
                "COMPLETED",
                0.46,
                List.of("tool:courseware:generate"),
                List.of("TEACHER_PRIVATE"),
                List.of("textbook:chapter-1"),
                List.of(new AgentRunExecuteResponse.StageTiming("model_call", 12)),
                new AgentRunExecuteResponse.TokenUsage(11, 7, 18),
                "Live model response recorded"));
        AgentTraceController controller = new AgentTraceController(
                new AgentTraceQueryService(store),
                request -> new RequestSubject("school-a", "teacher", "teacher-1", "device-1"));

        List<AgentTraceResponse> traces = controller.list(new AgentTraceQueryRequest(null, null, 20), null);

        assertThat(traces).extracting(AgentTraceResponse::traceId).containsExactly("trace-1");
        assertThat(traces.getFirst().subjectId()).isEqualTo("teacher-1");
    }

    @Test
    void summarizesAgentTraceUsageUsingBackendSubjectOnly() {
        InMemoryAgentTraceStore store = new InMemoryAgentTraceStore();
        store.save(new AgentTraceRecord(
                "trace-1",
                "plan-1",
                Instant.parse("2026-06-29T00:00:00Z"),
                "school-a",
                "teacher",
                "teacher-1",
                "CoursewareAgent",
                "openai",
                "gpt-5.4",
                "COMPLETED",
                0.46,
                List.of("tool:courseware:generate"),
                List.of("TEACHER_PRIVATE"),
                List.of("textbook:chapter-1"),
                List.of(new AgentRunExecuteResponse.StageTiming("model_call", 12)),
                new AgentRunExecuteResponse.TokenUsage(11, 7, 18),
                "Live model response recorded"));
        AgentTraceController controller = new AgentTraceController(
                new AgentTraceQueryService(store),
                request -> new RequestSubject("school-a", "teacher", "teacher-1", "device-1"));

        AgentTraceUsageSummaryResponse summary = controller.usageSummary(
                new AgentTraceQueryRequest("CoursewareAgent", "COMPLETED", 20),
                null);

        assertThat(summary.subjectId()).isEqualTo("teacher-1");
        assertThat(summary.totalUsage().totalTokens()).isEqualTo(18);
        assertThat(summary.modelUsages()).extracting(AgentTraceUsageSummaryResponse.ModelUsage::modelCode)
                .containsExactly("gpt-5.4");
    }

    @Test
    void summarizesAgentTraceDiagnosticsUsingBackendSubjectOnly() {
        InMemoryAgentTraceStore store = new InMemoryAgentTraceStore();
        store.save(new AgentTraceRecord(
                "trace-1",
                "plan-1",
                Instant.parse("2026-06-29T00:00:00Z"),
                "school-a",
                "teacher",
                "teacher-1",
                "CoursewareAgent",
                "openai",
                "gpt-5.4",
                "COMPLETED",
                0.46,
                List.of("tool:courseware:generate"),
                List.of("TEACHER_PRIVATE"),
                List.of("textbook:chapter-1"),
                List.of(new AgentRunExecuteResponse.StageTiming("model_call", 12)),
                new AgentRunExecuteResponse.TokenUsage(11, 7, 18),
                "Live model response recorded",
                List.of(
                        new AgentTraceRecord.DiagnosticEvent(
                                "JSON_PARSE_FAILED", "openai", "gpt-5.4", 0, true, "parse failed"),
                        new AgentTraceRecord.DiagnosticEvent(
                                "RETRY_SCHEDULED", "openai", "gpt-5.4", 1, true, "retry"),
                        new AgentTraceRecord.DiagnosticEvent(
                                "JSON_PARSE_SUCCEEDED", "openai", "gpt-5.4", 1, false, "parsed"))));
        AgentTraceController controller = new AgentTraceController(
                new AgentTraceQueryService(store),
                request -> new RequestSubject("school-a", "teacher", "teacher-1", "device-1"));

        AgentTraceDiagnosticSummaryResponse summary = controller.diagnosticSummary(
                new AgentTraceQueryRequest("CoursewareAgent", "COMPLETED", 20),
                null);

        assertThat(summary.subjectId()).isEqualTo("teacher-1");
        assertThat(summary.jsonParseFailureCount()).isEqualTo(1);
        assertThat(summary.retryScheduledCount()).isEqualTo(1);
        assertThat(summary.retryRecoveredCount()).isEqualTo(1);
    }
}
