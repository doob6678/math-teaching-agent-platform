package com.doob.mathagent.agent;

import static org.assertj.core.api.Assertions.assertThat;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.doob.mathagent.agent.entity.AgentRunTraceEntity;
import com.doob.mathagent.agent.mapper.AgentRunTraceMapper;
import com.doob.mathagent.agent.service.AgentTraceRecord;
import com.doob.mathagent.agent.service.AgentTraceSearchCriteria;
import com.doob.mathagent.agent.service.MyBatisAgentTraceStore;
import com.doob.mathagent.agent.vo.AgentRunExecuteResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class MyBatisAgentTraceStoreTest {

    @Test
    void saveConvertsTraceRecordToEntityWithoutRawPromptOrOutput() {
        CapturingMapper mapper = new CapturingMapper();
        MyBatisAgentTraceStore store = new MyBatisAgentTraceStore(mapper.proxy(), new ObjectMapper());

        store.save(trace("trace-1", "teacher", "teacher-1", "CoursewareAgent"));

        assertThat(mapper.inserted.getTraceId()).isEqualTo("trace-1");
        assertThat(mapper.inserted.getTenantId()).isEqualTo("school-a");
        assertThat(mapper.inserted.getSubjectId()).isEqualTo("teacher-1");
        assertThat(mapper.inserted.getAgentCode()).isEqualTo("CoursewareAgent");
        assertThat(mapper.inserted.getAllowedToolScopesJson()).contains("tool:courseware:generate");
        assertThat(mapper.inserted.getAllowedDataScopesJson()).contains("TEACHER_PRIVATE");
        assertThat(mapper.inserted.getEvidenceRefsJson()).contains("textbook:chapter-1");
        assertThat(mapper.inserted.getMetadataJson()).contains("stageTimings");
        assertThat(mapper.inserted.getMetadataJson()).contains("promptTokens");
        assertThat(mapper.inserted.getMetadataJson()).contains("model response recorded");
        assertThat(mapper.inserted.getMetadataJson()).contains("diagnosticEvents");
        assertThat(mapper.inserted.getMetadataJson()).contains("JSON_PARSE_SUCCEEDED");
        assertThat(mapper.inserted.toString()).doesNotContain("rawPrompt").doesNotContain("modelOutput");
    }

    @Test
    void searchReturnsTenantScopedTraceRecordsFromPersistedRows() {
        CapturingMapper mapper = new CapturingMapper();
        mapper.rows.add(entity("trace-1", "school-a", "teacher", "teacher-1", "CoursewareAgent"));
        mapper.rows.add(entity("trace-2", "school-a", "student", "student-1", "StudentTutorAgent"));
        MyBatisAgentTraceStore store = new MyBatisAgentTraceStore(mapper.proxy(), new ObjectMapper());

        List<AgentTraceRecord> traces = store.search(new AgentTraceSearchCriteria(
                "school-a",
                "teacher",
                "teacher-1",
                "CoursewareAgent",
                "COMPLETED",
                20));

        assertThat(traces).extracting(AgentTraceRecord::traceId).containsExactly("trace-1");
        assertThat(traces.getFirst().allowedToolScopes()).containsExactly("tool:courseware:generate");
        assertThat(traces.getFirst().evidenceRefs()).containsExactly("textbook:chapter-1");
        assertThat(traces.getFirst().actualUsage().totalTokens()).isEqualTo(18);
        assertThat(traces.getFirst().stageTimings()).extracting(AgentRunExecuteResponse.StageTiming::stage)
                .containsExactly("model_call");
        assertThat(traces.getFirst().diagnosticEvents()).extracting(AgentTraceRecord.DiagnosticEvent::eventType)
                .containsExactly("JSON_PARSE_SUCCEEDED");
    }

    @Test
    void readsOldMetadataRowsWithoutDiagnosticEvents() {
        CapturingMapper mapper = new CapturingMapper();
        AgentRunTraceEntity oldRow = entity("trace-old", "school-a", "teacher", "teacher-1", "CoursewareAgent");
        oldRow.setMetadataJson("""
                {"stageTimings":[{"stage":"model_call","elapsedMs":12}],"actualUsage":{"promptTokens":11,"completionTokens":7,"totalTokens":18},"message":"old trace"}
                """);
        mapper.rows.add(oldRow);
        MyBatisAgentTraceStore store = new MyBatisAgentTraceStore(mapper.proxy(), new ObjectMapper());

        AgentTraceRecord trace = store.find("trace-old").orElseThrow();

        assertThat(trace.message()).isEqualTo("old trace");
        assertThat(trace.actualUsage().totalTokens()).isEqualTo(18);
        assertThat(trace.diagnosticEvents()).isEmpty();
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
                List.of("tool:courseware:generate"),
                List.of("TEACHER_PRIVATE"),
                List.of("textbook:chapter-1"),
                List.of(new AgentRunExecuteResponse.StageTiming("model_call", 12)),
                new AgentRunExecuteResponse.TokenUsage(11, 7, 18),
                "Live model response recorded",
                List.of(new AgentTraceRecord.DiagnosticEvent(
                        "JSON_PARSE_SUCCEEDED",
                        "openai",
                        "gpt-5.4",
                        0,
                        false,
                        "Structured teaching draft parsed.")));
    }

    private static AgentRunTraceEntity entity(
            String traceId,
            String tenantId,
            String subjectType,
            String subjectId,
            String agentCode) {
        AgentRunTraceEntity entity = new AgentRunTraceEntity();
        entity.setTraceId(traceId);
        entity.setPlanId("plan-1");
        entity.setCreatedAt(Instant.parse("2026-06-29T00:00:00Z"));
        entity.setTenantId(tenantId);
        entity.setSubjectType(subjectType);
        entity.setSubjectId(subjectId);
        entity.setAgentCode(agentCode);
        entity.setProviderName("openai");
        entity.setModelCode("gpt-5.4");
        entity.setStatus("COMPLETED");
        entity.setEstimatedCost(0.46);
        entity.setAllowedToolScopesJson("[\"tool:courseware:generate\"]");
        entity.setAllowedDataScopesJson("[\"TEACHER_PRIVATE\"]");
        entity.setEvidenceRefsJson("[\"textbook:chapter-1\"]");
        entity.setMetadataJson("""
                {"stageTimings":[{"stage":"model_call","elapsedMs":12}],"actualUsage":{"promptTokens":11,"completionTokens":7,"totalTokens":18},"message":"Live model response recorded","diagnosticEvents":[{"eventType":"JSON_PARSE_SUCCEEDED","providerName":"openai","modelCode":"gpt-5.4","attemptNo":0,"retryable":false,"message":"Structured teaching draft parsed."}]}
                """);
        return entity;
    }

    private static class CapturingMapper {
        private final List<AgentRunTraceEntity> rows = new ArrayList<>();
        private AgentRunTraceEntity inserted;

        AgentRunTraceMapper proxy() {
            return (AgentRunTraceMapper) Proxy.newProxyInstance(
                    AgentRunTraceMapper.class.getClassLoader(),
                    new Class<?>[] {AgentRunTraceMapper.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "insert" -> {
                            inserted = (AgentRunTraceEntity) args[0];
                            yield 1;
                        }
                        case "selectById" -> rows.stream()
                                .filter(row -> row.getTraceId().equals(String.valueOf(args[0])))
                                .findFirst()
                                .orElse(null);
                        case "selectPage" -> selectPage((Page<AgentRunTraceEntity>) args[0], (Wrapper<AgentRunTraceEntity>) args[1]);
                        default -> throw new UnsupportedOperationException(method.getName());
                    });
        }

        private Page<AgentRunTraceEntity> selectPage(
                Page<AgentRunTraceEntity> page,
                Wrapper<AgentRunTraceEntity> ignored) {
            page.setRecords(rows.stream()
                    .filter(row -> "school-a".equals(row.getTenantId()))
                    .filter(row -> "teacher".equals(row.getSubjectType()))
                    .filter(row -> "teacher-1".equals(row.getSubjectId()))
                    .filter(row -> "CoursewareAgent".equals(row.getAgentCode()))
                    .filter(row -> "COMPLETED".equals(row.getStatus()))
                    .toList());
            return page;
        }
    }
}
