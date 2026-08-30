package com.doob.mathagent.teaching.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.doob.mathagent.agent.service.ProviderRouteGrantSigner;
import com.doob.mathagent.infrastructure.ai.AiProviderCatalog;
import com.doob.mathagent.infrastructure.ai.AiProviderProperties;
import com.doob.mathagent.memory.vo.StudentMemoryResponse;
import com.doob.mathagent.teaching.TeachingEvidence;
import com.doob.mathagent.teaching.TeachingHandoutVersions;
import com.doob.mathagent.teaching.dto.TeachingTaskRequest;
import com.doob.mathagent.teaching.vo.TeachingTaskResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/** Ensures the teaching-task adapter consumes the one Python handout graph, not the retired draft contract. */
class PythonTeachingHandoutClientTest {

    @Test
    void projectsAllThreeAudienceDocumentsAndUsageFromTheHandoutGraph() throws Exception {
        TeachingTaskResponse.AiDraft draft = PythonTeachingHandoutClient.project(new ObjectMapper().readTree("""
                {
                  "status":"COMPLETED",
                  "documents":{
                    "teacher_writer":{"markdown":"教师逐题讲解\\n\\n![函数图像](IMAJES/image-001.png)","assetPlacements":[{"logicalPath":"函数资料/IMAJES/image-001.png","markdownLine":"![函数图像](IMAJES/image-001.png)","anchorBefore":"教师逐题讲解","anchorAfter":"","layout":"single","variants":["teacher_writer","student_writer"],"caption":"函数图像"}]},
                    "student_writer":{"markdown":"学生练习\\n\\n![函数图像](IMAJES/image-001.png)","assetPlacements":[{"logicalPath":"函数资料/IMAJES/image-001.png","markdownLine":"![函数图像](IMAJES/image-001.png)","anchorBefore":"学生练习","anchorAfter":"","layout":"single","variants":["teacher_writer","student_writer"],"caption":"函数图像"}]},
                    "lecture_writer":{"markdown":"课堂投影"}
                  },
                  "metrics":{
                    "promptTokens":120,
                    "completionTokens":80,
                    "totalTokens":200,
                    "nodeMetrics":[{"node":"teacher_writer","provider":"openai","model":"gpt-5.6-luna","status":"COMPLETED"}]
                  }
                }
                """));

        assertThat(draft.structured()).isTrue();
        assertThat(draft.teacherExplanation()).contains("教师逐题讲解", "![函数图像](IMAJES/image-001.png)");
        assertThat(draft.studentHint()).contains("学生练习", "![函数图像](IMAJES/image-001.png)");
        assertThat(draft.lectureContent()).isEqualTo("课堂投影");
        assertThat(draft.assetPlacements()).hasSize(2).first().satisfies(placement -> {
            assertThat(placement.logicalPath()).isEqualTo("函数资料/IMAJES/image-001.png");
            assertThat(placement.markdownLine()).isEqualTo("![函数图像](IMAJES/image-001.png)");
            assertThat(placement.anchorBefore()).isEqualTo("教师逐题讲解");
            assertThat(placement.anchorAfter()).isBlank();
            assertThat(placement.variants()).containsExactly("teacher_writer", "student_writer");
        });
        assertThat(draft.assetPlacements()).noneMatch(placement -> placement.markdownLine().contains("asset-a"));
        assertThat(draft.followUpQuestions()).isEmpty();
        assertThat(draft.providerName()).isEqualTo("openai");
        assertThat(draft.modelCode()).isEqualTo("gpt-5.6-luna");
        assertThat(draft.totalTokens()).isEqualTo(200);
    }

    @Test
    void rejectsRetiredQuestionAndAssetIdPlacementContract() throws Exception {
        JsonNode response = new ObjectMapper().readTree("""
                {
                  "status":"COMPLETED",
                  "documents":{
                    "teacher_writer":{"markdown":"教师正文","assetPlacements":[{"questionNumber":1,"assetIds":["asset-a"],"anchor":"题目","layout":"single","variants":["teacher_writer"]}]},
                    "student_writer":{"markdown":"学生正文"},
                    "lecture_writer":{"markdown":"课堂正文"}
                  },
                  "metrics":{}
                }
                """);

        assertThatThrownBy(() -> PythonTeachingHandoutClient.project(response))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("invalid source-image placement");
    }

    @Test
    void ignoresSelfReviewEnvelopeAndStateFieldsInTaskAndStudentProjections() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        TeachingTaskResponse.AiDraft draft = PythonTeachingHandoutClient.project(objectMapper.readTree("""
                {
                  "status":"COMPLETED",
                  "documents":{
                    "teacher_writer":{"markdown":"教师正文"},
                    "student_writer":{"markdown":"学生正文"},
                    "lecture_writer":{"markdown":"课堂正文"}
                  },
                  "metrics":{"nodeMetrics":[{"node":"teacher_writer","provider":"openai","model":"gpt-5.6-terra","status":"COMPLETED"}]},
                  "selfReview":{"candidateHash":"candidate-secret","failureCode":"HANDOUT_OUTPUT_CONTRACT_FAILURE","prompt":"review-prompt","evidenceRefs":["ev-secret"]},
                  "state":{"selfReview":{"candidateHash":"nested-candidate-secret"},"evidenceIds":["evidence-secret"]}
                }
                """));
        TeachingTaskResponse task = new TeachingTaskResponse(
                "task-projection", "request-projection", "tenant-a", "student", "student-1", null,
                com.doob.mathagent.teaching.TeachingTaskStatus.FAILED, "题目", "目标", "", List.of(), List.of(),
                List.of(), List.of(), "", "教师正文", "学生正文", "课堂正文", List.of(), null, List.of(), draft,
                null, null, null, "HANDOUT_OUTPUT_CONTRACT_FAILURE");

        String projectedDraft = objectMapper.writeValueAsString(draft);
        String studentPayload = objectMapper.writeValueAsString(task.studentSafe());

        assertThat(projectedDraft).contains("教师正文", "学生正文", "课堂正文")
                .doesNotContain("selfReview", "candidate-secret", "nested-candidate-secret", "review-prompt", "ev-secret", "evidence-secret", "HANDOUT_OUTPUT_CONTRACT_FAILURE");
        assertThat(task.studentSafe().status()).isEqualTo(com.doob.mathagent.teaching.TeachingTaskStatus.FAILED);
        assertThat(studentPayload).contains("\"status\":\"FAILED\"")
                .doesNotContain("selfReview", "candidateHash", "failureCode", "prompt", "evidenceRef", "evidenceId", "HANDOUT_OUTPUT_CONTRACT_FAILURE", "教师正文", "课堂正文");
    }

    @Test
    void issuesRunScopedOpaqueEvidenceReferencesInsteadOfSourceScopeAndChunkIds() throws Exception {
        PythonTeachingHandoutClient client = client(new MockEnvironment()
                .withProperty("math-agent.agent-worker.shared-key", "worker-secret"));
        Method method = PythonTeachingHandoutClient.class.getDeclaredMethod("evidenceRefs", String.class, List.class);
        method.setAccessible(true);
        @SuppressWarnings("unchecked")
        List<String> refs = (List<String>) method.invoke(client, "run-opaque-001", List.of(new TeachingEvidence(
                "TEACHER_RESOURCE", "教师资料", "internal-block-id", 1, "已选证据", "", "", "internal-document-id")));

        assertThat(refs).hasSize(1);
        assertThat(refs.getFirst()).matches("ev_[0-9a-f]{32}")
                .doesNotContain("TEACHER_RESOURCE", "internal-block-id", "internal-document-id", ":");
    }

    @Test
    void alignsBothJavaEventProjectorsOnSafeReviewProgressFields() throws Exception {
        JsonNode event = new ObjectMapper().readTree("""
                {"event":"self_review_completed","status":"COMPLETED","node":"self_review","phase":"review",
                 "revisionRound":2,"turn":3,"provider":"openai","model":"gpt-5.6-terra","deterministicRepair":false,
                 "error":"HANDOUT_OUTPUT_CONTRACT_FAILURE","feedbackCodes":["FORMAT_ERROR"],
                 "selfReview":{"candidateHash":"candidate-secret","prompt":"review-prompt"},"evidenceIds":["ev-secret"]}
                """);
        Map<String, Object> clientProjection = PythonTeachingHandoutClient.projectEvent(event);
        Map<String, Object> streamProjection = TeachingTaskEventStreamService.projectPythonEvent(Map.ofEntries(
                Map.entry("event", "self_review_completed"), Map.entry("status", "COMPLETED"),
                Map.entry("node", "self_review"), Map.entry("phase", "review"), Map.entry("revisionRound", 2),
                Map.entry("turn", 3), Map.entry("provider", "openai"), Map.entry("model", "gpt-5.6-terra"),
                Map.entry("deterministicRepair", false), Map.entry("error", "HANDOUT_OUTPUT_CONTRACT_FAILURE"),
                Map.entry("feedbackCodes", List.of("FORMAT_ERROR")),
                Map.entry("selfReview", Map.of("candidateHash", "candidate-secret")),
                Map.entry("evidenceIds", List.of("ev-secret"))));

        assertThat(clientProjection).isEqualTo(streamProjection)
                .containsEntry("turn", 3)
                .containsEntry("status", "COMPLETED")
                .doesNotContainKeys("error", "feedbackCodes", "selfReview", "evidenceIds");
    }

    @Test
    void projectsOnlyOperationalEventFieldsAndRedactsCheckpointContent() throws Exception {
        JsonNode event = new ObjectMapper().readTree("""
                {"event":"node_completed","node":"teacher_writer","status":"SUCCESS",
                 "provider":"openai","model":"gpt-5.6-terra","markdown":"教师正文",
                 "prompt":"secret","error":"HANDOUT_OUTPUT_CONTRACT_FAILURE",
                 "selfReview":{"candidateHash":"candidate-secret"},"evidenceIds":["ev-secret"],
                 "state":{"writers":[{"markdown":"secret"}]}}
                """);

        assertThat(PythonTeachingHandoutClient.projectEvent(event))
                .containsEntry("event", "node_completed")
                .containsEntry("node", "teacher_writer")
                .containsEntry("provider", "openai")
                .doesNotContainKeys("markdown", "prompt", "error", "selfReview", "evidenceIds", "state");
    }

    @Test
    void rejectsNestedEventValuesEvenWhenTheirFieldIsAllowlisted() {
        assertThat(PythonTeachingHandoutClient.projectEvent(new ObjectMapper().createObjectNode()
                .put("event", "started").put("state", "secret")))
                .containsOnlyKeys("event");
    }

    @Test
    void derivesPythonDeadlineFromLectureTaskLeaseNotStageWorkerLease() throws Exception {
        PythonTeachingHandoutClient client = client(new MockEnvironment()
                .withProperty("math-agent.teaching.lecture-task.lease-seconds", "120")
                .withProperty("math-agent.agent-worker.runtime.lease-seconds", "1")
                .withProperty("math-agent.python-handout.timeout-ms", "900000"));
        java.lang.reflect.Field timeout = PythonTeachingHandoutClient.class.getDeclaredField("timeoutMs");
        timeout.setAccessible(true);

        assertThat(timeout.getLong(client)).isEqualTo(105_000L);
    }

    @Test
    void defaultsHandoutRouteToDeepSeekFlash() throws Exception {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("math-agent.ai.route-grant-secret", "route-secret");
        PythonTeachingHandoutClient client = client(environment);
        Method method = PythonTeachingHandoutClient.class.getDeclaredMethod("providerRoute", String.class);
        method.setAccessible(true);
        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> route = (java.util.Map<String, Object>) method.invoke(client, "run-route-001");

        assertThat(route.get("primary")).isEqualTo(java.util.Map.of("name", "deepseek", "model", "deepseek-v4-flash"));
        assertThat(route.get("fallbacks")).isEqualTo(List.of());
        assertThat(route.get("routeGrant")).isInstanceOf(String.class);
    }

    @Test
    void explicitlyPreferredTerraRetainsDeepSeekFallback() throws Exception {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("math-agent.ai.route-grant-secret", "route-secret")
                .withProperty("math-agent.handout.preferred-provider", "openai");
        PythonTeachingHandoutClient client = client(environment);
        Method method = PythonTeachingHandoutClient.class.getDeclaredMethod("providerRoute", String.class);
        method.setAccessible(true);
        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> route = (java.util.Map<String, Object>) method.invoke(client, "run-terra-001");

        assertThat(route.get("primary")).isEqualTo(java.util.Map.of("name", "openai", "model", "gpt-5.6-terra"));
        assertThat(route.get("fallbacks")).isEqualTo(List.of(java.util.Map.of("name", "deepseek", "model", "deepseek-v4-flash")));
    }

    @Test
    void explicitlyPreferredDeepSeekFlashSkipsUnavailableTerra() throws Exception {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("math-agent.ai.route-grant-secret", "route-secret")
                .withProperty("math-agent.handout.preferred-provider", "deepseek");
        PythonTeachingHandoutClient client = client(environment);
        Method method = PythonTeachingHandoutClient.class.getDeclaredMethod("providerRoute", String.class);
        method.setAccessible(true);
        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> route = (java.util.Map<String, Object>) method.invoke(client, "run-deepseek-001");

        assertThat(route.get("primary")).isEqualTo(java.util.Map.of("name", "deepseek", "model", "deepseek-v4-flash"));
        assertThat(route.get("fallbacks")).isEqualTo(List.of());
    }

    private static PythonTeachingHandoutClient client(MockEnvironment environment) {
        AiProviderProperties properties = new AiProviderProperties();
        properties.setOpenai(new AiProviderProperties.Provider("openai", true, "gpt-5.6-luna"));
        properties.setDeepseek(new AiProviderProperties.Provider("deepseek", true, "deepseek-v4-flash"));
        return new PythonTeachingHandoutClient(
                environment,
                new ObjectMapper(),
                new AiProviderCatalog(properties),
                new ProviderRouteGrantSigner(environment));
    }

    /** 验证 Java 仅转换 Writer 原文结构，不再根据题目或旧渲染器补写教学内容和图形。 */
    @Test
    void rendersOnlyTheThreeCanonicalWriterMarkdownBodiesWithoutAutomaticFigure() {
        TeachingTaskResponse.AiDraft draft = new TeachingTaskResponse.AiDraft(
                true, "python", "writer", 0, 0, 0, "", "", true,
                "# 教师原文\n\n教师专属推导。",
                "# 学生原文\n\n学生独立作答。",
                "# 讲解原文\n\n课堂讲解要点。",
                List.of(), List.of(), "", 0, 0, false, List.of());
        TeachingTaskRequest request = new TeachingTaskRequest(
                "client", "二次函数 y=x^2 的顶点是什么？", "二次函数图像与最值", 1).normalize();

        TeachingHandoutVersions versions = TeachingWorkflowCorePolicy.renderHandoutVersions(
                request, List.of(), List.of(),
                new StudentMemoryResponse(false, null, "private", "", 0D, "", List.of()),
                null, draft, null);

        assertThat(versions.teacherHandoutLatex()).contains("教师原文", "教师专属推导")
                .doesNotContain("学生独立作答", "课堂讲解要点", "tikzpicture", "抛物线");
        assertThat(versions.studentHandoutLatex()).contains("学生原文", "学生独立作答")
                .doesNotContain("教师专属推导", "课堂讲解要点", "tikzpicture");
        assertThat(versions.lectureHandoutLatex()).contains("讲解原文", "课堂讲解要点")
                .doesNotContain("教师专属推导", "学生独立作答", "tikzpicture");
    }
}
