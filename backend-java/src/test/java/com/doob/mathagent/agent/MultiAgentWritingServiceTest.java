package com.doob.mathagent.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.doob.mathagent.agent.dto.MultiAgentWritingRequest;
import com.doob.mathagent.agent.service.AgentRunExecutionService;
import com.doob.mathagent.agent.service.AgentRunPlanService;
import com.doob.mathagent.agent.service.AiChatGateway;
import com.doob.mathagent.agent.service.AiChatRequest;
import com.doob.mathagent.agent.service.AiChatResult;
import com.doob.mathagent.agent.service.InMemoryAgentConcurrencyGuard;
import com.doob.mathagent.agent.service.InMemoryAgentTraceStore;
import com.doob.mathagent.agent.service.InMemoryMultiAgentWritingWorkflowStore;
import com.doob.mathagent.agent.service.MultiAgentWritingService;
import com.doob.mathagent.agent.service.MultiAgentWritingWorkflowRecord;
import com.doob.mathagent.agent.service.MultiAgentWritingWorkflowStore;
import com.doob.mathagent.agent.vo.AgentRunExecuteResponse;
import com.doob.mathagent.agent.vo.MultiAgentWritingResponse;
import com.doob.mathagent.infrastructure.ai.AiProviderCatalog;
import com.doob.mathagent.infrastructure.ai.AiProviderProperties;
import com.doob.mathagent.infrastructure.security.RequestSubject;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.TaskExecutor;

class MultiAgentWritingServiceTest {

    @Test
    void runsOnlyEvidenceAndThreeParallelPublishableVariants() {
        StageAwareGateway gateway = topologyGateway(Map.of());
        MultiAgentWritingService service = service(
                new InMemoryAgentTraceStore(), new InMemoryMultiAgentWritingWorkflowStore(), gateway);

        MultiAgentWritingResponse response = service.run(request(false), subject());

        assertThat(response.stages()).extracting(MultiAgentWritingResponse.StageResult::stageCode)
                .containsExactly("resource_curation", "teacher_writer", "student_writer", "lecture_writer");
        assertThat(gateway.stageCodes()).doesNotContain(
                "template_selection", "outline_planning", "source_review", "student_safety_review", "layout_review", "merge_coordinator");
    }

    @Test
    void runsControlledEvidenceOutlineAndThreeVersionWritingTopology() {
        CapturingGateway gateway = new CapturingGateway(List.of(
                new AiChatResult("dashscope", "qwen3.6-flash", 8, 4, 12, "resources recorded", "evidence pack"),
                new AiChatResult("dashscope", "qwen3.6-flash", 7, 3, 10, "template recorded", "{\"content\":\"template selection\"}"),
                new AiChatResult("dashscope", "qwen3.6-flash", 9, 5, 14, "outline recorded", "{\"content\":\"shared outline\"}"),
                new AiChatResult("dashscope", "qwen3.6-flash", 11, 7, 18, "teacher recorded", "{\"teacherExplanation\":\"teacher handout\"}"),
                new AiChatResult("dashscope", "qwen3.6-flash", 10, 6, 16, "student recorded", "{\"studentWorksheet\":\"student worksheet\"}"),
                new AiChatResult("dashscope", "qwen3.6-flash", 9, 5, 14, "lecture recorded", "{\"lectureCards\":\"16:10 lecture cards\"}"),
                new AiChatResult("dashscope", "qwen3.6-flash", 6, 3, 9, "source review recorded", reviewJson("source review")),
                new AiChatResult("dashscope", "qwen3.6-flash", 6, 3, 9, "student review recorded", reviewJson("student safety review")),
                new AiChatResult("dashscope", "qwen3.6-flash", 6, 3, 9, "layout review recorded", reviewJson("layout review")),
                new AiChatResult("dashscope", "qwen3.6-flash", 8, 4, 12, "merge recorded", "{\"markdown\":\"merged handout\"}")));
        InMemoryAgentTraceStore traceStore = new InMemoryAgentTraceStore();
        InMemoryMultiAgentWritingWorkflowStore workflowStore = new InMemoryMultiAgentWritingWorkflowStore();
        MultiAgentWritingService service = service(traceStore, workflowStore, gateway);

        MultiAgentWritingResponse response = service.run(request(false), subject());

        assertThat(response.status()).isEqualTo("COMPLETED");
        assertThat(response.stages()).extracting(MultiAgentWritingResponse.StageResult::stageCode)
                .containsExactly(
                        "resource_curation", "teacher_writer", "student_writer", "lecture_writer");
        assertThat(response.totalUsage().totalTokens()).isEqualTo(54);
        // This fixture intentionally uses a sequential gateway; the dedicated topology test above verifies the
        // three concurrent writer payloads without relying on nondeterministic completion order.
        assertThat(response.stages()).hasSize(4);
        assertThat(traceStore.find(response.stages().get(3).traceId()).orElseThrow().diagnosticEvents())
                .extracting(com.doob.mathagent.agent.service.AgentTraceRecord.DiagnosticEvent::eventType)
                .containsExactly("MODEL_CALL_SUCCEEDED", "JSON_PARSE_SUCCEEDED");
        assertThat(service.find(response.workflowId(), subject()).orElseThrow().status()).isEqualTo("COMPLETED");
        assertThat(workflowStore.findVisible(
                        response.workflowId(),
                        new RequestSubject("school-a", "teacher", "teacher-2", "device-1")))
                .isEmpty();
    }

    @Test
    void laterWritingStagesReceiveCompletedStageArtifacts() {
        StageAwareGateway gateway = topologyGateway(Map.of(
                "resource_curation", "evidence pack",
                "teacher_writer", "teacher-only-answer",
                "student_writer", "student worksheet",
                "lecture_writer", "lecture cards"));
        MultiAgentWritingService service = service(
                new InMemoryAgentTraceStore(),
                new InMemoryMultiAgentWritingWorkflowStore(),
                gateway);

        service.run(request(false), subject());

        assertThat(gateway.requestFor("student_writer").userInputSummary())
                .contains("resource_curation", "evidence pack")
                .doesNotContain("teacher-only-answer");
    }

    @Test
    void artifactExposesStructuredSectionsForMergeAndCollaboration() {
        StageAwareGateway gateway = topologyGateway(Map.of(
                "resource_curation", "evidence pack",
                "template_selection", "template choice",
                "outline_planning", "shared outline",
                "teacher_writer", "Explain the vector angle theorem.",
                "student_writer", "Practice with one scaffolded problem.",
                "lecture_writer", "Lecture cards for projection.",
                "source_review", "Check theorem prerequisites before exercises.",
                "student_safety_review", "No answer leakage.",
                "layout_review", "Notation must stay consistent.",
                "merge_coordinator", "# Final Handout\nFormatted for classroom use."));
        MultiAgentWritingService service = service(
                new InMemoryAgentTraceStore(),
                new InMemoryMultiAgentWritingWorkflowStore(),
                gateway);

        MultiAgentWritingResponse response = service.run(request(false), subject());

        var artifact = service.artifact(response.workflowId(), subject());
        assertThat(artifact.sections()).extracting(section -> section.sectionCode())
                .contains("teacher-explanation", "student-worksheet", "lecture-cards");
        assertThat(artifact.sections().stream()
                .filter(section -> "teacher-explanation".equals(section.sectionCode()))
                .findFirst()
                .orElseThrow()
                .artifactRefs())
                .containsExactly("PUBLIC_TEXTBOOK:space-vector:angle");
        assertThat(artifact.sections().stream()
                .filter(section -> "teacher-explanation".equals(section.sectionCode()))
                .findFirst()
                .orElseThrow()
                .risks())
                .containsExactly("Diagram still needs a visual check.");
        assertThat(artifact.mergedMarkdown())
                .contains("## Teacher Explanation", "Explain the vector angle theorem.")
                .doesNotContain("\"teacherExplanation\"");
    }

    @Test
    void startsThreeVersionWritersConcurrentlyWithoutPassingTeacherAnswersToStudentWriter() {
        CountDownLatch writersStarted = new CountDownLatch(3);
        AtomicBoolean writersObservedParallel = new AtomicBoolean(false);
        StageAwareGateway gateway = topologyGateway(Map.of(
                "outline_planning", "shared outline",
                "teacher_writer", "teacher-only-answer",
                "student_writer", "student worksheet",
                "lecture_writer", "lecture cards"),
                writersStarted,
                writersObservedParallel);
        MultiAgentWritingService service = service(
                new InMemoryAgentTraceStore(),
                new InMemoryMultiAgentWritingWorkflowStore(),
                gateway);

        MultiAgentWritingResponse response = service.run(request(false), subject());

        assertThat(response.stages()).hasSize(4);
        assertThat(writersObservedParallel).isTrue();
        assertThat(gateway.requestFor("student_writer").userInputSummary())
                .contains("resource_curation")
                .doesNotContain("teacher-only-answer");
    }

    @Test
    void rejectsStudentSubjectBeforeAnyModelCall() {
        CapturingGateway gateway = new CapturingGateway(List.of());
        MultiAgentWritingService service = service(
                new InMemoryAgentTraceStore(),
                new InMemoryMultiAgentWritingWorkflowStore(),
                gateway);

        assertThatThrownBy(() -> service.run(
                        new MultiAgentWritingRequest("student handout", "function", List.of(), false, "", ""),
                        new RequestSubject("school-a", "student", "student-1", "device-1")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("teacher or admin");
        assertThat(gateway.requests()).isEmpty();
    }

    @Test
    void startsAsyncWorkflowAndCompletesWhenExecutorRuns() {
        StageAwareGateway gateway = topologyGateway(Map.of());
        InMemoryMultiAgentWritingWorkflowStore workflowStore = new InMemoryMultiAgentWritingWorkflowStore();
        CapturingTaskExecutor taskExecutor = new CapturingTaskExecutor();
        MultiAgentWritingService service = service(
                new InMemoryAgentTraceStore(),
                workflowStore,
                taskExecutor,
                gateway);

        MultiAgentWritingResponse started = service.startAsync(request(false), subject());

        assertThat(started.status()).isEqualTo("RUNNING");
        assertThat(gateway.requests()).isEmpty();

        taskExecutor.runNext();

        MultiAgentWritingResponse completed = service.find(started.workflowId(), subject()).orElseThrow();
        assertThat(completed.status()).isEqualTo("COMPLETED");
        assertThat(completed.stages()).hasSize(4);
        assertThat(completed.totalUsage().totalTokens()).isPositive();
    }

    @Test
    void resumesFailedWorkflowFromFirstMissingStageWithoutRepeatingCompletedDraft() {
        StageAwareGateway gateway = topologyGateway(Map.of());
        InMemoryAgentTraceStore traceStore = new InMemoryAgentTraceStore();
        InMemoryMultiAgentWritingWorkflowStore workflowStore = new InMemoryMultiAgentWritingWorkflowStore();
        workflowStore.save(new MultiAgentWritingWorkflowRecord(
                "workflow-resume-123",
                "school-a",
                "teacher",
                "teacher-1",
                "FAILED",
                Instant.parse("2026-06-30T00:00:00Z"),
                Instant.parse("2026-06-30T00:01:00Z"),
                List.of(new MultiAgentWritingResponse.StageResult(
                        "resource_curation",
                        "TeacherAssistantAgent",
                        "trace-resource-curation",
                        "dashscope",
                        "qwen3.6-flash",
                        "COMPLETED",
                        new AgentRunExecuteResponse.TokenUsage(11, 7, 18),
                        "resource curation recorded",
                        "evidence pack")),
                new AgentRunExecuteResponse.TokenUsage(11, 7, 18),
                "failed after draft"));
        MultiAgentWritingService service = service(traceStore, workflowStore, gateway);

        MultiAgentWritingResponse response = service.resume("workflow-resume-123", request(false), subject());

        assertThat(response.status()).isEqualTo("COMPLETED");
        assertThat(response.stages()).extracting(MultiAgentWritingResponse.StageResult::stageCode)
                .containsExactly(
                        "resource_curation", "teacher_writer", "student_writer", "lecture_writer");
        assertThat(gateway.stageCodes()).doesNotContain("resource_curation");
        assertThat(gateway.requests()).extracting(AiChatRequest::agentCode)
                .contains("CoursewareAgent", "HandoutFormatterAgent", "TeacherAssistantAgent");
        assertThat(traceStore.find(response.stages().get(1).traceId()).orElseThrow().planId())
                .isEqualTo("workflow-resume-123:teacher_writer");
    }

    @Test
    void persistsFailedWorkflowStatusWithCompletedStages() {
        CapturingGateway gateway = new CapturingGateway(List.of(
                new AiChatResult("dashscope", "qwen3.6-flash", 11, 7, 18, "draft recorded", "draft")));
        CapturingWorkflowStore workflowStore = new CapturingWorkflowStore();
        MultiAgentWritingService service = service(new InMemoryAgentTraceStore(), workflowStore, gateway);

        assertThatThrownBy(() -> service.run(request(false), subject()))
                .isInstanceOf(RuntimeException.class);

        assertThat(gateway.requests()).hasSizeGreaterThanOrEqualTo(4);
        assertThat(workflowStore.saved()).extracting(MultiAgentWritingWorkflowRecord::status)
                .containsExactly("RUNNING", "RUNNING", "FAILED");
        assertThat(workflowStore.saved().getLast().stages()).hasSize(1);
        assertThat(workflowStore.saved().getLast().message()).contains("failed");
    }

    @Test
    void preservesSuccessfulParallelBranchesAndResumesOnlyTheFailedWriter() {
        BranchFailureGateway gateway = new BranchFailureGateway();
        CapturingWorkflowStore workflowStore = new CapturingWorkflowStore();
        MultiAgentWritingService service = service(
                new InMemoryAgentTraceStore(),
                workflowStore,
                gateway);

        assertThatThrownBy(() -> service.run(request(false), subject()))
                .isInstanceOf(RuntimeException.class);

        MultiAgentWritingWorkflowRecord failed = workflowStore.saved().getLast();
        assertThat(failed.status()).isEqualTo("FAILED");
        assertThat(failed.stages()).extracting(MultiAgentWritingResponse.StageResult::stageCode)
                .contains("resource_curation", "teacher_writer")
                .doesNotContain("student_writer");

        gateway.failStudent(false);
        MultiAgentWritingResponse resumed = service.resume(failed.workflowId(), request(false), subject());

        assertThat(resumed.status()).isEqualTo("COMPLETED");
        assertThat(gateway.callsFor("teacher_writer")).isEqualTo(1);
        // The first failed structured call is retried by the shared JSON-repair policy before resume adds one success.
        assertThat(gateway.callsFor("student_writer")).isEqualTo(4);
        assertThat(resumed.stages()).extracting(MultiAgentWritingResponse.StageResult::stageCode)
                .containsExactly(
                        "resource_curation", "teacher_writer", "student_writer", "lecture_writer");
    }

    @Test
    void boundsEvidenceReferencesBeforeSendingEachAgentContext() {
        StageAwareGateway gateway = topologyGateway(Map.of());
        MultiAgentWritingService service = service(
                new InMemoryAgentTraceStore(),
                new InMemoryMultiAgentWritingWorkflowStore(),
                gateway);
        List<String> oversizedEvidence = java.util.stream.IntStream.range(0, 80)
                .mapToObj(index -> "TEACHER_PRIVATE:document-" + index + ":" + "x".repeat(900))
                .toList();

        service.run(new MultiAgentWritingRequest(
                "teacher handout",
                "space vector angle",
                oversizedEvidence,
                false,
                "dashscope",
                "qwen3.6-flash"), subject());

        assertThat(gateway.requestFor("resource_curation").evidenceRefs())
                .hasSizeLessThanOrEqualTo(24)
                .allSatisfy(reference -> assertThat(reference).hasSizeLessThanOrEqualTo(240));
    }

    private static MultiAgentWritingService service(
            InMemoryAgentTraceStore traceStore,
            MultiAgentWritingWorkflowStore workflowStore,
            AiChatGateway gateway) {
        return service(traceStore, workflowStore, Runnable::run, gateway);
    }

    private static MultiAgentWritingService service(
            InMemoryAgentTraceStore traceStore,
            MultiAgentWritingWorkflowStore workflowStore,
            TaskExecutor taskExecutor,
            AiChatGateway gateway) {
        AiProviderCatalog catalog = providerCatalog();
        return new MultiAgentWritingService(
                new AgentRunPlanService(catalog),
                new AgentRunExecutionService(
                        traceStore,
                        new InMemoryAgentConcurrencyGuard(),
                        gateway,
                        catalog,
                        Clock.systemUTC()),
                workflowStore,
                taskExecutor);
    }

    private static MultiAgentWritingRequest request(boolean dryRun) {
        return new MultiAgentWritingRequest(
                "teacher handout",
                "space vector angle",
                List.of("PUBLIC_TEXTBOOK:space-vector:angle"),
                dryRun,
                "dashscope",
                "qwen3.6-flash");
    }

    private static RequestSubject subject() {
        return new RequestSubject("school-a", "teacher", "teacher-1", "device-1");
    }

    private static AiProviderCatalog providerCatalog() {
        AiProviderProperties properties = new AiProviderProperties();
        properties.setDefaultProvider("dashscope");
        properties.getDashscope().setApiKey("dashscope-key");
        properties.getDashscope().setChatModel("qwen3.6-flash");
        return new AiProviderCatalog(properties);
    }

    private static String reviewJson(String text) {
        return "{\"review\":\"" + text + "\",\"status\":\"ok\"}";
    }

    /** Supplies stage-addressed structured output so concurrent test scheduling cannot swap model fixtures. */
    private static StageAwareGateway topologyGateway(Map<String, String> contentOverrides) {
        return topologyGateway(contentOverrides, null, null);
    }

    private static StageAwareGateway topologyGateway(
            Map<String, String> contentOverrides,
            CountDownLatch writersStarted,
            AtomicBoolean writersObservedParallel) {
        Map<String, AiChatResult> outcomes = new LinkedHashMap<>();
        for (String stageCode : List.of(
                "resource_curation", "template_selection", "outline_planning",
                "teacher_writer", "student_writer", "lecture_writer",
                "source_review", "student_safety_review", "layout_review", "merge_coordinator")) {
            String content = contentOverrides.getOrDefault(stageCode, stageCode + " content");
            outcomes.put(stageCode, new AiChatResult(
                    "dashscope",
                    "qwen3.6-flash",
                    8,
                    4,
                    12,
                    stageCode + " recorded",
                    topologyJson(stageCode, content)));
        }
        return new StageAwareGateway(outcomes, writersStarted, writersObservedParallel);
    }

    /** Mirrors the real structured contracts for every stage in the controlled topology. */
    private static String topologyJson(String stageCode, String content) {
        String value = jsonString(content);
        return switch (stageCode) {
            case "resource_curation" -> content;
            case "template_selection", "outline_planning" -> "{\"content\":" + value + "}";
            case "teacher_writer" -> "{\"teacherExplanation\":" + value
                    + ",\"sourceRefs\":[\"PUBLIC_TEXTBOOK:space-vector:angle\"]"
                    + ",\"risks\":[\"Diagram still needs a visual check.\"]}";
            case "student_writer" -> "{\"studentWorksheet\":" + value + "}";
            case "lecture_writer" -> "{\"lectureCards\":" + value + "}";
            case "source_review", "student_safety_review", "layout_review" -> reviewJson(content);
            case "merge_coordinator" -> "{\"markdown\":" + value + "}";
            default -> throw new IllegalArgumentException("Unknown topology stage " + stageCode);
        };
    }

    private static String jsonString(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"";
    }

    private static final class StageAwareGateway implements AiChatGateway {
        private final Map<String, AiChatResult> outcomes;
        private final Map<String, AiChatRequest> requestsByStage = new LinkedHashMap<>();
        private final CountDownLatch writersStarted;
        private final AtomicBoolean writersObservedParallel;

        private StageAwareGateway(
                Map<String, AiChatResult> outcomes,
                CountDownLatch writersStarted,
                AtomicBoolean writersObservedParallel) {
            this.outcomes = Map.copyOf(outcomes);
            this.writersStarted = writersStarted;
            this.writersObservedParallel = writersObservedParallel;
        }

        @Override
        public AiChatResult call(AiChatRequest request) {
            String stageCode = stageCode(request.userInputSummary());
            AiChatResult outcome;
            synchronized (this) {
                requestsByStage.put(stageCode, request);
                outcome = outcomes.get(stageCode);
            }
            if (outcome == null) {
                throw new IllegalStateException("No test output for stage " + stageCode);
            }
            observeParallelWriters(stageCode);
            return outcome;
        }

        private void observeParallelWriters(String stageCode) {
            if (writersStarted == null || !List.of("teacher_writer", "student_writer", "lecture_writer").contains(stageCode)) {
                return;
            }
            writersStarted.countDown();
            try {
                writersObservedParallel.set(writersStarted.await(1, TimeUnit.SECONDS));
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while observing parallel writers", exception);
            }
        }

        private synchronized List<AiChatRequest> requests() {
            return List.copyOf(requestsByStage.values());
        }

        private synchronized AiChatRequest requestFor(String stageCode) {
            AiChatRequest request = requestsByStage.get(stageCode);
            if (request == null) {
                throw new AssertionError("No request captured for stage " + stageCode);
            }
            return request;
        }

        private synchronized List<String> stageCodes() {
            return List.copyOf(requestsByStage.keySet());
        }

        private static String stageCode(String userInputSummary) {
            String prefix = "stage=";
            int start = userInputSummary.indexOf(prefix);
            int end = userInputSummary.indexOf(';', start);
            if (start < 0 || end < 0) {
                throw new IllegalStateException("Test request did not carry a stage code");
            }
            return userInputSummary.substring(start + prefix.length(), end);
        }
    }

    private static final class CapturingGateway implements AiChatGateway {
        private final List<AiChatResult> outcomes;
        private final List<AiChatRequest> requests = new ArrayList<>();
        private int index;

        private CapturingGateway(List<AiChatResult> outcomes) {
            this.outcomes = outcomes;
        }

        @Override
        public synchronized AiChatResult call(AiChatRequest request) {
            requests.add(request);
            return outcomes.get(index++);
        }

        private synchronized List<AiChatRequest> requests() {
            return List.copyOf(requests);
        }
    }

    private static final class CapturingTaskExecutor implements TaskExecutor {
        private final List<Runnable> tasks = new ArrayList<>();

        @Override
        public void execute(Runnable task) {
            tasks.add(task);
        }

        private void runNext() {
            tasks.removeFirst().run();
        }
    }

    private static final class CapturingWorkflowStore implements MultiAgentWritingWorkflowStore {
        private final List<MultiAgentWritingWorkflowRecord> saved = new ArrayList<>();

        @Override
        public MultiAgentWritingWorkflowRecord save(MultiAgentWritingWorkflowRecord record) {
            MultiAgentWritingWorkflowRecord normalized = record.normalize();
            saved.add(normalized);
            return normalized;
        }

        @Override
        public Optional<MultiAgentWritingWorkflowRecord> findVisible(String workflowId, RequestSubject subject) {
            return saved.stream()
                    .filter(record -> record.workflowId().equals(workflowId))
                    .reduce((first, latest) -> latest);
        }

        private List<MultiAgentWritingWorkflowRecord> saved() {
            return saved;
        }
    }

    /** Fails only the student branch once, after the teacher branch has completed. */
    private static final class BranchFailureGateway implements AiChatGateway {
        private final Map<String, AtomicInteger> calls = new java.util.concurrent.ConcurrentHashMap<>();
        private final CountDownLatch teacherCompleted = new CountDownLatch(1);
        private final AtomicBoolean failStudent = new AtomicBoolean(true);

        @Override
        public AiChatResult call(AiChatRequest request) {
            String stage = StageAwareGateway.stageCode(request.userInputSummary());
            calls.computeIfAbsent(stage, ignored -> new AtomicInteger()).incrementAndGet();
            if ("teacher_writer".equals(stage)) {
                teacherCompleted.countDown();
            }
            if ("student_writer".equals(stage) && failStudent.get()) {
                try {
                    teacherCompleted.await(1, TimeUnit.SECONDS);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                }
                throw new IllegalStateException("student branch failed");
            }
            String content = switch (stage) {
                case "teacher_writer" -> "{\"teacherExplanation\":\"teacher\"}";
                case "student_writer" -> "{\"studentWorksheet\":\"student\"}";
                case "lecture_writer" -> "{\"lectureCards\":\"lecture\"}";
                case "merge_coordinator" -> "{\"markdown\":\"merged\"}";
                case "template_selection", "outline_planning" -> "{\"content\":\"" + stage + "\"}";
                case "source_review", "student_safety_review", "layout_review" -> reviewJson(stage);
                default -> stage + " content";
            };
            return new AiChatResult("dashscope", "qwen3.6-flash", 8, 4, 12, stage + " recorded", content);
        }

        private void failStudent(boolean value) {
            failStudent.set(value);
        }

        private int callsFor(String stage) {
            return calls.getOrDefault(stage, new AtomicInteger()).get();
        }
    }
}
