package com.doob.mathagent.teaching.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.doob.mathagent.agent.service.InMemoryAgentTraceStore;
import com.doob.mathagent.memory.service.InMemoryStudentMemoryStore;
import com.doob.mathagent.memory.service.StudentMemoryReuseService;
import com.doob.mathagent.memory.vo.StudentMemoryResponse;
import com.doob.mathagent.resources.TextbookCatalogReader;
import com.doob.mathagent.resources.TextbookChunkReader;
import com.doob.mathagent.retrieval.TextbookRetrievalService;
import com.doob.mathagent.retrieval.TextbookRetrievalServiceFixture;
import com.doob.mathagent.teaching.TeachingEvidence;
import com.doob.mathagent.teaching.TeachingRequestContext;
import com.doob.mathagent.teaching.TeachingWorkflowEvent;
import com.doob.mathagent.teaching.TeachingWorkflowNode;
import com.doob.mathagent.teaching.dto.TeachingTaskRequest;
import com.doob.mathagent.teaching.service.TeachingWorkflowService.EvidencePack;
import com.doob.mathagent.teaching.service.TeachingWorkflowService.ProgressPhase;
import com.doob.mathagent.teaching.service.TeachingWorkflowService.RetrievalOutcome;
import com.doob.mathagent.teaching.vo.TeachingTaskResponse;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Covers durable branch status when an optional canonical corpus is unavailable. */
class TeachingWorkflowRetrievalProgressTest {

    @TempDir
    Path tempDir;

    @Test
    void canonicalFailureKeepsTeacherBranchPendingPythonDecision() {
        RetrievalFixtureService service = new RetrievalFixtureService(tempDir, null);

        EvidencePack result = service.retrieveEvidencePack(
                new TeachingTaskRequest("retrieval-progress", "函数单调性", "函数单调性", 3),
                new TeachingRequestContext("tenant-a", "teacher", "teacher-1", "device-1"));

        assertThat(result.teacherResourceEvidence()).isEmpty();
        assertThat(result.teacherResourceOutcome().status()).isEqualTo("skipped");
        assertThat(result.teacherResourceOutcome().detail()).contains("Python Writer");
        assertThat(result.teacherResourceElapsedMs()).isZero();
    }

    @Test
    void timedOutTextbookDoesNotTurnUnrequestedTeacherResourcesIntoZeroHitResult() {
        RetrievalFixtureService service = new RetrievalFixtureService(tempDir, null) {
            @Override
            protected List<TeachingEvidence> retrieveTextbookEvidence(TeachingTaskRequest request, TeachingRequestContext context) {
                try {
                    Thread.sleep(80L);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                }
                return List.of();
            }
        };
        service.evidenceTimeoutMs = 10L;
        java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newFixedThreadPool(3);
        service.evidenceTaskExecutor = executor::execute;
        try {
            EvidencePack result = service.retrieveEvidencePack(
                    new TeachingTaskRequest("parabola-timeout", "抛物线", "抛物线", 3),
                    new TeachingRequestContext("tenant-a", "teacher", "teacher-1", "device-1"));

            assertThat(result.textbookOutcome().status()).isEqualTo("degraded");
            assertThat(result.textbookOutcome().detail()).contains("timeout", "可独立恢复");
            assertThat(result.teacherResourceEvidence()).isEmpty();
            assertThat(result.teacherResourceOutcome().status()).isEqualTo("skipped");
            assertThat(result.teacherResourceOutcome().detail()).contains("Python Writer");
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void unrequestedTeacherBranchDoesNotErasePublicTextbookEvidence() {
        TeachingEvidence textbookHit = new TeachingEvidence(
                "PUBLIC_TEXTBOOK", "抛物线教材", "textbook-parabola-1", 18, "抛物线的标准方程与顶点。", "", "", "textbook-doc-1");
        RetrievalFixtureService service = new RetrievalFixtureService(tempDir, null) {
            @Override
            protected List<TeachingEvidence> retrieveTextbookEvidence(TeachingTaskRequest request, TeachingRequestContext context) {
                return List.of(textbookHit);
            }
        };
        service.evidenceTimeoutMs = 10L;
        java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newFixedThreadPool(3);
        service.evidenceTaskExecutor = executor::execute;
        try {
            EvidencePack result = service.retrieveEvidencePack(
                    new TeachingTaskRequest("parabola-textbook-timeout", "抛物线", "抛物线", 3),
                    new TeachingRequestContext("tenant-a", "teacher", "teacher-1", "device-1"));

            assertThat(result.textbookEvidence()).containsExactly(textbookHit);
            assertThat(result.textbookOutcome().status()).isEqualTo("completed");
            assertThat(result.teacherResourceEvidence()).isEmpty();
            assertThat(result.teacherResourceOutcome().status()).isEqualTo("skipped");
            assertThat(result.teacherResourceOutcome().detail()).contains("Python Writer");
            assertThat(result.mergedEvidence()).containsExactly(textbookHit);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void progressNodesKeepDegradedSkippedAndEmptyCompletedBranchesDistinct() {
        TeachingTaskRequest request = new TeachingTaskRequest("progress-status", "函数", "函数", 3);
        StudentMemoryResponse memory = new StudentMemoryResponse(false, "", "", "", 0D, "", List.of());

        List<TeachingWorkflowNode> nodes = TeachingWorkflowProgressModel.progressWorkflowNodes(
                request, memory, List.of(), List.of(), List.of(), List.of(), null,
                new TeachingHandoutTemplateService().resolveFor(request), true, true,
                ProgressPhase.CONTENT_GENERATING,
                RetrievalOutcome.completed(), RetrievalOutcome.completed(),
                RetrievalOutcome.degraded("规范试卷检索暂不可用，已保留教师资料。"));

        assertThat(nodes).filteredOn(node -> node.code().equals("PUBLIC_TEXTBOOK_RETRIEVAL"))
                .singleElement().satisfies(node -> {
                    assertThat(node.status()).isEqualTo("completed");
                    assertThat(node.summary()).isEqualTo("命中公开教材证据 0 条。");
                });
        assertThat(nodes).filteredOn(node -> node.code().equals("QUESTION_BANK_RETRIEVAL"))
                .singleElement().extracting(TeachingWorkflowNode::status).isEqualTo("completed");
        assertThat(nodes).filteredOn(node -> node.code().equals("TEACHER_RESOURCE_RETRIEVAL"))
                .singleElement().satisfies(node -> {
                    assertThat(node.status()).isEqualTo("degraded");
                    assertThat(node.summary()).contains("规范试卷检索暂不可用");
                });

        List<TeachingWorkflowNode> policyDisabledNodes = TeachingWorkflowProgressModel.progressWorkflowNodes(
                request, memory, List.of(), List.of(), List.of(), List.of(), null,
                new TeachingHandoutTemplateService().resolveFor(request), true, false,
                ProgressPhase.CONTENT_GENERATING,
                RetrievalOutcome.completed(), RetrievalOutcome.completed(), RetrievalOutcome.completed());
        assertThat(policyDisabledNodes).filteredOn(node -> node.code().equals("TEACHER_RESOURCE_RETRIEVAL"))
                .singleElement().extracting(TeachingWorkflowNode::status).isEqualTo("skipped");

        List<TeachingWorkflowEvent> events = TeachingWorkflowProgressModel.progressWorkflowEvents(
                new TeachingHandoutTemplateService().resolveFor(request), List.of(), List.of(), List.of(), null,
                ProgressPhase.CONTENT_GENERATING, RetrievalOutcome.completed(), RetrievalOutcome.completed(),
                RetrievalOutcome.degraded("规范试卷检索暂不可用，已保留教师资料。"));
        assertThat(events).filteredOn(event -> event.eventId().equals("evidence"))
                .singleElement().extracting(TeachingWorkflowEvent::status).isEqualTo("degraded");
    }

    private static class RetrievalFixtureService extends TeachingWorkflowService {
        private final TeachingEvidence teacherHit;

        private RetrievalFixtureService(Path root, TeachingEvidence teacherHit) {
            super(root, retrievalService(root), new InMemoryTeachingTaskStore(),
                    new StudentMemoryReuseService(new InMemoryStudentMemoryStore()), null,
                    new InMemoryAgentTraceStore(), new TeachingHandoutTemplateService(), Optional.empty(), Runnable::run);
            this.teacherHit = teacherHit;
            this.evidenceTaskExecutor = Runnable::run;
        }

        @Override
        protected List<TeachingEvidence> retrieveTextbookEvidence(TeachingTaskRequest request, TeachingRequestContext context) {
            return List.of();
        }

        @Override
        protected List<TeachingEvidence> retrieveTeacherResourceEvidence(TeachingTaskRequest request, TeachingRequestContext context) {
            return List.of(teacherHit);
        }

        @Override
        protected List<TeachingEvidence> retrieveCanonicalMathPaperEvidence(TeachingTaskRequest request) {
            throw new IllegalStateException("Canonical collection gaokao_math is unavailable");
        }
    }

    private static TextbookRetrievalService retrievalService(Path root) {
        return TextbookRetrievalServiceFixture.service(
                new TextbookCatalogReader(), new TextbookChunkReader(),
                new com.doob.mathagent.retrieval.LocalTextbookBm25SearchEngine(),
                new com.doob.mathagent.retrieval.NoopRetrievalAuditSink());
    }
}
