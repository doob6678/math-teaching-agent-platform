package com.doob.mathagent.agent;

import static org.assertj.core.api.Assertions.assertThat;

import com.doob.mathagent.agent.entity.MultiAgentWritingWorkflowEntity;
import com.doob.mathagent.agent.mapper.MultiAgentWritingWorkflowMapper;
import com.doob.mathagent.agent.service.MultiAgentWritingWorkflowRecord;
import com.doob.mathagent.agent.service.MyBatisMultiAgentWritingWorkflowStore;
import com.doob.mathagent.agent.vo.AgentRunExecuteResponse;
import com.doob.mathagent.agent.vo.MultiAgentWritingResponse;
import com.doob.mathagent.infrastructure.security.RequestSubject;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class MyBatisMultiAgentWritingWorkflowStoreTest {

    @Test
    void savesWorkflowStatusWithoutRawPromptOrModelOutput() {
        CapturingMapper mapper = new CapturingMapper();
        MyBatisMultiAgentWritingWorkflowStore store = new MyBatisMultiAgentWritingWorkflowStore(
                mapper.proxy(),
                new ObjectMapper());

        store.save(record("workflow-1", "teacher-1", "COMPLETED"));

        assertThat(mapper.rows).hasSize(1);
        assertThat(mapper.rows.getFirst().getWorkflowId()).isEqualTo("workflow-1");
        assertThat(mapper.rows.getFirst().getSubjectId()).isEqualTo("teacher-1");
        assertThat(mapper.rows.getFirst().getStatus()).isEqualTo("COMPLETED");
        assertThat(mapper.rows.getFirst().getMetadataJson()).contains("CoursewareAgent");
        assertThat(mapper.rows.getFirst().getMetadataJson()).contains("totalTokens");
        assertThat(mapper.rows.getFirst().getMetadataJson()).doesNotContain("rawPrompt").doesNotContain("modelOutput");
    }

    @Test
    void upsertsAndReadsOnlyVisibleWorkflow() {
        CapturingMapper mapper = new CapturingMapper();
        MyBatisMultiAgentWritingWorkflowStore store = new MyBatisMultiAgentWritingWorkflowStore(
                mapper.proxy(),
                new ObjectMapper());
        store.save(record("workflow-1", "teacher-1", "RUNNING"));
        store.save(record("workflow-1", "teacher-1", "COMPLETED"));

        assertThat(mapper.rows).hasSize(1);
        assertThat(mapper.rows.getFirst().getStatus()).isEqualTo("COMPLETED");
        assertThat(store.findVisible(
                        "workflow-1",
                        new RequestSubject("school-a", "teacher", "teacher-1", "device-1")))
                .hasValueSatisfying(record -> {
                    assertThat(record.status()).isEqualTo("COMPLETED");
                    assertThat(record.stages()).hasSize(1);
                    assertThat(record.totalUsage().totalTokens()).isEqualTo(18);
                });
        assertThat(store.findVisible(
                        "workflow-1",
                        new RequestSubject("school-a", "teacher", "teacher-2", "device-1")))
                .isEmpty();
    }

    /**
     * Builds one durable workflow record for persistence tests.
     */
    private static MultiAgentWritingWorkflowRecord record(String workflowId, String subjectId, String status) {
        return new MultiAgentWritingWorkflowRecord(
                workflowId,
                "school-a",
                "teacher",
                subjectId,
                status,
                Instant.parse("2026-06-30T00:00:00Z"),
                Instant.parse("2026-06-30T00:01:00Z"),
                List.of(new MultiAgentWritingResponse.StageResult(
                        "draft",
                        "CoursewareAgent",
                        "trace-1",
                        "dashscope",
                        "qwen3.6-flash",
                        "COMPLETED",
                        new AgentRunExecuteResponse.TokenUsage(11, 7, 18),
                        "safe stage message")),
                new AgentRunExecuteResponse.TokenUsage(11, 7, 18),
                "safe workflow message");
    }

    /**
     * Minimal mapper proxy for focused MyBatis store tests.
     */
    private static final class CapturingMapper {
        private final List<MultiAgentWritingWorkflowEntity> rows = new ArrayList<>();

        /**
         * Returns a mapper proxy that supports the methods used by the store.
         */
        MultiAgentWritingWorkflowMapper proxy() {
            return (MultiAgentWritingWorkflowMapper) Proxy.newProxyInstance(
                    MultiAgentWritingWorkflowMapper.class.getClassLoader(),
                    new Class<?>[] {MultiAgentWritingWorkflowMapper.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "insert" -> {
                            rows.add((MultiAgentWritingWorkflowEntity) args[0]);
                            yield 1;
                        }
                        case "updateById" -> {
                            MultiAgentWritingWorkflowEntity entity = (MultiAgentWritingWorkflowEntity) args[0];
                            rows.removeIf(row -> row.getWorkflowId().equals(entity.getWorkflowId()));
                            rows.add(entity);
                            yield 1;
                        }
                        case "selectById" -> rows.stream()
                                .filter(row -> row.getWorkflowId().equals(String.valueOf(args[0])))
                                .findFirst()
                                .orElse(null);
                        default -> throw new UnsupportedOperationException(method.getName());
                    });
        }
    }
}
