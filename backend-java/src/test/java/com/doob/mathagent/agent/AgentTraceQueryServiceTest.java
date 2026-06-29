package com.doob.mathagent.agent;

import static org.assertj.core.api.Assertions.assertThat;

import com.doob.mathagent.agent.dto.AgentTraceQueryRequest;
import com.doob.mathagent.agent.service.AgentTraceQueryService;
import com.doob.mathagent.agent.service.AgentTraceRecord;
import com.doob.mathagent.agent.service.InMemoryAgentTraceStore;
import com.doob.mathagent.agent.vo.AgentRunExecuteResponse;
import com.doob.mathagent.agent.vo.AgentTraceResponse;
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

    private static AgentTraceRecord trace(String traceId, String subjectType, String subjectId, String agentCode) {
        return new AgentTraceRecord(
                traceId,
                "plan-1",
                Instant.parse("2026-06-29T00:00:00Z"),
                "school-a",
                subjectType,
                subjectId,
                agentCode,
                "openai",
                "gpt-5.4",
                "COMPLETED",
                0.46,
                List.of("tool:search:textbook"),
                List.of("PUBLIC_TEXTBOOK"),
                List.of("textbook:chapter-1"),
                List.of(new AgentRunExecuteResponse.StageTiming("model_call", 12)),
                new AgentRunExecuteResponse.TokenUsage(11, 7, 18),
                "Live model response recorded");
    }
}
