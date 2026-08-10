package com.doob.mathagent.agent.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.doob.mathagent.agent.dto.MultiAgentWritingRequest;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/** Verifies the Java-to-Python handout request carries only the versioned opaque contract. */
class PythonHandoutClientTest {

    @Test
    void buildsVersionedOpaquePayloadWithoutJavaIdentityFields() {
        Map<String, Object> payload = PythonHandoutClient.requestPayload(
                "run-contract-001",
                new MultiAgentWritingRequest(
                        "函数讲义", "【题目 1】已知函数 f(x)=x^2，求最小值。", List.of("doc:approved-1"), false,
                        "luna", "gpt-5.6-luna"),
                "trace-contract-001",
                true,
                30_000L,
                new MockEnvironment().withProperty("math-agent.python-handout.graph-version", "handout-v2"));

        assertThat(payload).containsEntry("contractVersion", "handout-ai-v1")
                .containsEntry("runId", "run-contract-001")
                .containsEntry("taskId", "run-contract-001")
                .containsEntry("graphVersion", "handout-v2")
                .containsEntry("idempotencyKey", "handout:run-contract-001")
                .containsEntry("resume", true)
                .containsKeys("traceparent", "deadlineEpochMs", "evidenceRefs")
                .doesNotContainKeys("tenantId", "subjectId", "subjectType", "filesystemPath", "javaIdentity");
        assertThat(payload.get("evidenceRefs")).isEqualTo(List.of("doc:approved-1"));
    }
}
