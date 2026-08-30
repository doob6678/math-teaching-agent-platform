package com.doob.mathagent.agent.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.doob.mathagent.agent.dto.MultiAgentWritingRequest;
import com.doob.mathagent.teaching.TeachingEvidence;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
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
                        "函数讲义", "【题目 1】已知函数 f(x)=x^2，求最小值。", List.of("ev_0123456789abcdef0123456789abcdef"), false,
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
        assertThat(payload.get("evidenceRefs")).isEqualTo(List.of("ev_0123456789abcdef0123456789abcdef"));
    }

    @Test
    void initialEvidenceDocumentReferenceUsesBrokerAuthorizationNamespace() throws Exception {
        String runId = "run-file-identity-001";
        String sharedKey = "worker-secret";
        String fileDocumentId = "2093279637590351873";
        TeachingEvidence evidence = new TeachingEvidence(
                "TEACHER_RESOURCE", "抛物线的点差法.md", "2093279638022365185", 0, "来源内容", "", "",
                fileDocumentId, "feishu", "", "", List.of(), "", List.of());
        Map<String, Object> payload = PythonHandoutClient.requestPayload(
                runId,
                new MultiAgentWritingRequest("抛物线讲义", "抛物线的点差法", List.of("ev_0123456789abcdef0123456789abcdef"),
                        false, "", "", List.of(evidence)),
                runId,
                true,
                30_000L,
                new MockEnvironment().withProperty("math-agent.agent-worker.shared-key", sharedKey));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> initialEvidence = (List<Map<String, Object>>) payload.get("initialEvidence");
        String expected = "doc_" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest((sharedKey + "|" + runId + "|document|" + fileDocumentId).getBytes(StandardCharsets.UTF_8)), 0, 16);
        assertThat(initialEvidence).singleElement().satisfies(item ->
                assertThat(item).containsEntry("documentRef", expected));
    }
}
