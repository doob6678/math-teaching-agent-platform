package com.doob.mathagent.teaching;

import static org.assertj.core.api.Assertions.assertThat;

import com.doob.mathagent.agent.service.InMemoryAgentTraceStore;
import com.doob.mathagent.infrastructure.security.RequestSubjectResolver;
import com.doob.mathagent.memory.service.InMemoryStudentMemoryStore;
import com.doob.mathagent.memory.service.StudentMemoryReuseService;
import com.doob.mathagent.resources.TextbookCatalogReader;
import com.doob.mathagent.resources.TextbookChunkReader;
import com.doob.mathagent.retrieval.LocalTextbookBm25SearchEngine;
import com.doob.mathagent.retrieval.NoopRetrievalAuditSink;
import com.doob.mathagent.retrieval.TextbookRetrievalService;
import com.doob.mathagent.teaching.controller.TeachingTaskController;
import com.doob.mathagent.teaching.dto.TeachingHandoutBatchExportRequest;
import com.doob.mathagent.teaching.dto.TeachingHumanFeedbackRequest;
import com.doob.mathagent.teaching.dto.TeachingTaskRequest;
import com.doob.mathagent.teaching.service.TeachingHandoutBatchExportService;
import com.doob.mathagent.teaching.service.TeachingHandoutPdfExportService;
import com.doob.mathagent.teaching.service.InMemoryTeachingHumanFeedbackStore;
import com.doob.mathagent.teaching.service.InMemoryTeachingTaskStore;
import com.doob.mathagent.teaching.service.TeachingAiDraftService;
import com.doob.mathagent.teaching.service.TeachingCapabilityVerifier;
import com.doob.mathagent.teaching.service.TeachingHumanFeedbackService;
import com.doob.mathagent.teaching.service.TeachingWorkflowService;
import com.doob.mathagent.teaching.vo.TeachingHandoutBatchExportResponse;
import com.doob.mathagent.teaching.vo.TeachingHumanFeedbackResponse;
import com.doob.mathagent.teaching.vo.TeachingTaskResponse;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;

class TeachingTaskControllerTest {

    @TempDir
    Path tempDir;

    private static TeachingWorkflowService testWorkflowService(
            Path processedBooksRoot,
            TextbookRetrievalService retrievalService,
            InMemoryTeachingTaskStore taskStore,
            StudentMemoryReuseService memoryReuseService) {
        return new TeachingWorkflowService(
                processedBooksRoot,
                retrievalService,
                taskStore,
                memoryReuseService,
                TeachingAiDraftServiceFixture.disabled(),
                new InMemoryAgentTraceStore());
    }

    private static TeachingTaskController testController(
            TeachingWorkflowService workflowService,
            RequestSubjectResolver subjectResolver,
            TeachingCapabilityVerifier capabilityVerifier,
            TeachingHandoutPdfExportService pdfExportService,
            TeachingHandoutBatchExportService batchExportService) {
        return new TeachingTaskController(
                workflowService,
                subjectResolver,
                capabilityVerifier,
                pdfExportService,
                batchExportService,
                new TeachingHumanFeedbackService(new InMemoryTeachingHumanFeedbackStore()));
    }

    private static TeachingHandoutBatchExportService batchExportService(TeachingHandoutPdfExportService pdfExportService) {
        return new TeachingHandoutBatchExportService(pdfExportService, Clock.systemUTC(), Duration.ofMinutes(30));
    }

    @Test
    void exposesSubmitAndResumeContractForTeachingTask() throws Exception {
        Path root = createTextbookCorpus();
        TextbookRetrievalService retrievalService = com.doob.mathagent.retrieval.TextbookRetrievalServiceFixture.service(
                new TextbookCatalogReader(),
                new TextbookChunkReader(),
                new LocalTextbookBm25SearchEngine(),
                new NoopRetrievalAuditSink());
        TeachingWorkflowService service = testWorkflowService(
                root,
                retrievalService,
                new InMemoryTeachingTaskStore(),
                new StudentMemoryReuseService(new InMemoryStudentMemoryStore()));
        TeachingTaskController controller = testController(
                service,
                teacherResolver(),
                (token, action, path, requestHash, subject) -> true,
                new TeachingHandoutPdfExportService(),
                batchExportService(new TeachingHandoutPdfExportService()));
        TeachingTaskRequest request = new TeachingTaskRequest(
                "client-001",
                "How do I solve D(-1)?",
                "Understand a function-domain definition problem",
                3);

        TeachingTaskResponse submitted = controller.submit(request, null);
        TeachingTaskResponse loaded = controller.get(submitted.taskId(), null);
        ResponseEntity<String> exported = controller.exportLatex(submitted.taskId(), null);
        ResponseEntity<String> preview = controller.previewLatex(submitted.taskId(), null);
        ResponseEntity<byte[]> exportedPdf = controller.exportPdf(submitted.taskId(), null);
        ResponseEntity<String> studentPreview = controller.previewLatexVersion(submitted.taskId(), "student", null);
        ResponseEntity<byte[]> teacherPdf = controller.exportPdfVersion(submitted.taskId(), "teacher", null);

        assertThat(loaded.taskId()).isEqualTo(submitted.taskId());
        assertThat(loaded.status()).isEqualTo(TeachingTaskStatus.COMPLETED);
        assertThat(exported.getBody()).contains("\\section");
        assertThat(preview.getBody()).contains("\\section");
        assertThat(studentPreview.getBody()).contains("\\section", "\\vspace");
        assertThat(preview.getHeaders().getContentDisposition().isInline()).isTrue();
        assertThat(exported.getHeaders().getContentDisposition().getFilename()).isEqualTo(submitted.taskId() + ".tex");
        assertThat(exportedPdf.getBody()).startsWith(new byte[] {'%', 'P', 'D', 'F'});
        assertThat(teacherPdf.getBody()).startsWith(new byte[] {'%', 'P', 'D', 'F'});
        assertThat(exportedPdf.getHeaders().getContentDisposition().getFilename()).isEqualTo(submitted.taskId() + ".pdf");
        assertThat(loaded.handoutLatex()).contains("\\section");
    }

    @Test
    void rejectsTeachingSubmitWithoutAcceptedCapabilityToken() throws Exception {
        TeachingWorkflowService service = testWorkflowService(
                createTextbookCorpus(),
                com.doob.mathagent.retrieval.TextbookRetrievalServiceFixture.service(
                        new TextbookCatalogReader(),
                        new TextbookChunkReader(),
                        new LocalTextbookBm25SearchEngine(),
                        new NoopRetrievalAuditSink()),
                new InMemoryTeachingTaskStore(),
                new StudentMemoryReuseService(new InMemoryStudentMemoryStore()));
        TeachingTaskController controller = testController(
                service,
                teacherResolver(),
                (token, action, path, requestHash, subject) -> false,
                new TeachingHandoutPdfExportService(),
                batchExportService(new TeachingHandoutPdfExportService()));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> controller.submit(
                        new TeachingTaskRequest(
                                "client-002",
                                "How do I solve D(-1)?",
                                "Understand a function-domain definition problem",
                                3),
                        null))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Capability token");
    }

    @Test
    void rejectsLatexExportWithoutAcceptedCapabilityToken() throws Exception {
        TeachingWorkflowService service = testWorkflowService(
                createTextbookCorpus(),
                com.doob.mathagent.retrieval.TextbookRetrievalServiceFixture.service(
                        new TextbookCatalogReader(),
                        new TextbookChunkReader(),
                        new LocalTextbookBm25SearchEngine(),
                        new NoopRetrievalAuditSink()),
                new InMemoryTeachingTaskStore(),
                new StudentMemoryReuseService(new InMemoryStudentMemoryStore()));
        TeachingTaskController setupController = testController(
                service,
                teacherResolver(),
                (token, action, path, requestHash, subject) -> true,
                new TeachingHandoutPdfExportService(),
                batchExportService(new TeachingHandoutPdfExportService()));
        TeachingTaskResponse submitted = setupController.submit(
                new TeachingTaskRequest("client-003", "question", "goal", 3),
                null);
        TeachingTaskController protectedController = testController(
                service,
                teacherResolver(),
                (token, action, path, requestHash, subject) -> false,
                new TeachingHandoutPdfExportService(),
                batchExportService(new TeachingHandoutPdfExportService()));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> protectedController.exportLatex(submitted.taskId(), null))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Capability token");
    }

    @Test
    void rejectsPdfExportWithoutAcceptedCapabilityToken() throws Exception {
        TeachingWorkflowService service = testWorkflowService(
                createTextbookCorpus(),
                com.doob.mathagent.retrieval.TextbookRetrievalServiceFixture.service(
                        new TextbookCatalogReader(),
                        new TextbookChunkReader(),
                        new LocalTextbookBm25SearchEngine(),
                        new NoopRetrievalAuditSink()),
                new InMemoryTeachingTaskStore(),
                new StudentMemoryReuseService(new InMemoryStudentMemoryStore()));
        TeachingTaskController setupController = testController(
                service,
                teacherResolver(),
                (token, action, path, requestHash, subject) -> true,
                new TeachingHandoutPdfExportService(),
                batchExportService(new TeachingHandoutPdfExportService()));
        TeachingTaskResponse submitted = setupController.submit(
                new TeachingTaskRequest("client-004", "question", "goal", 3),
                null);
        TeachingTaskController protectedController = testController(
                service,
                teacherResolver(),
                (token, action, path, requestHash, subject) -> false,
                new TeachingHandoutPdfExportService(),
                batchExportService(new TeachingHandoutPdfExportService()));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> protectedController.exportPdf(submitted.taskId(), null))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Capability token");
    }

    @Test
    void rejectsPreviewAndBatchZipWithoutAcceptedCapabilityToken() throws Exception {
        TeachingWorkflowService service = testWorkflowService(
                createTextbookCorpus(),
                com.doob.mathagent.retrieval.TextbookRetrievalServiceFixture.service(
                        new TextbookCatalogReader(),
                        new TextbookChunkReader(),
                        new LocalTextbookBm25SearchEngine(),
                        new NoopRetrievalAuditSink()),
                new InMemoryTeachingTaskStore(),
                new StudentMemoryReuseService(new InMemoryStudentMemoryStore()));
        TeachingHandoutPdfExportService pdfExportService = new TeachingHandoutPdfExportService();
        TeachingHandoutBatchExportService batchExportService = batchExportService(pdfExportService);
        TeachingTaskController setupController = testController(
                service,
                teacherResolver(),
                (token, action, path, requestHash, subject) -> true,
                pdfExportService,
                batchExportService);
        TeachingTaskResponse submitted = setupController.submit(
                new TeachingTaskRequest("client-protected-batch", "question", "goal", 3),
                null);
        TeachingHandoutBatchExportResponse batch = setupController.createBatchZip(
                new TeachingHandoutBatchExportRequest(List.of(submitted.taskId()), List.of(), List.of()),
                null);
        TeachingTaskController protectedController = testController(
                service,
                teacherResolver(),
                (token, action, path, requestHash, subject) -> false,
                pdfExportService,
                batchExportService);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> protectedController.previewLatex(submitted.taskId(), null))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Capability token");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> protectedController.createBatchZip(
                        new TeachingHandoutBatchExportRequest(List.of(submitted.taskId()), List.of(), List.of()),
                        null))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Capability token");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> protectedController.downloadBatchZip(batch.batchId(), null))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Capability token");
    }

    @Test
    void studentCannotPreviewOrExportTeacherHandoutVersion() throws Exception {
        TeachingWorkflowService service = testWorkflowService(
                createTextbookCorpus(),
                com.doob.mathagent.retrieval.TextbookRetrievalServiceFixture.service(
                        new TextbookCatalogReader(),
                        new TextbookChunkReader(),
                        new LocalTextbookBm25SearchEngine(),
                        new NoopRetrievalAuditSink()),
                new InMemoryTeachingTaskStore(),
                new StudentMemoryReuseService(new InMemoryStudentMemoryStore()));
        TeachingHandoutPdfExportService pdfExportService = new TeachingHandoutPdfExportService();
        TeachingTaskController controller = testController(
                service,
                studentResolver(),
                (token, action, path, requestHash, subject) -> true,
                pdfExportService,
                batchExportService(pdfExportService));
        TeachingTaskResponse submitted = controller.submit(
                new TeachingTaskRequest("client-student-version", "question", "goal", 3),
                null);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> controller.previewLatexVersion(
                        submitted.taskId(),
                        "teacher",
                        null))
                .isInstanceOfSatisfying(ResponseStatusException.class, exception ->
                        assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> controller.exportLatexVersion(
                        submitted.taskId(),
                        "teacher",
                        null))
                .isInstanceOfSatisfying(ResponseStatusException.class, exception ->
                        assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> controller.exportPdfVersion(
                        submitted.taskId(),
                        "teacher",
                        null))
                .isInstanceOfSatisfying(ResponseStatusException.class, exception ->
                        assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
        assertThat(controller.previewLatexVersion(submitted.taskId(), "student", null).getBody())
                .contains("\\section", "\\vspace")
                .doesNotContain("Teacher");
    }

    @Test
    void createsTemporaryBatchZipForOwnedTasksAndDownloadsIt() throws Exception {
        TeachingWorkflowService service = testWorkflowService(
                createTextbookCorpus(),
                com.doob.mathagent.retrieval.TextbookRetrievalServiceFixture.service(
                        new TextbookCatalogReader(),
                        new TextbookChunkReader(),
                        new LocalTextbookBm25SearchEngine(),
                        new NoopRetrievalAuditSink()),
                new InMemoryTeachingTaskStore(),
                new StudentMemoryReuseService(new InMemoryStudentMemoryStore()));
        TeachingHandoutPdfExportService pdfExportService = new TeachingHandoutPdfExportService();
        TeachingTaskController controller = testController(
                service,
                teacherResolver(),
                (token, action, path, requestHash, subject) -> true,
                pdfExportService,
                batchExportService(pdfExportService));
        TeachingTaskResponse first = controller.submit(
                new TeachingTaskRequest("client-batch-1", "question 1", "goal 1", 3),
                null);
        TeachingTaskResponse second = controller.submit(
                new TeachingTaskRequest("client-batch-2", "question 2", "goal 2", 3),
                null);

        TeachingHandoutBatchExportResponse batch = controller.createBatchZip(
                new TeachingHandoutBatchExportRequest(
                        List.of(first.taskId(), second.taskId()),
                        List.of("folder-algebra"),
                        List.of("grade-10/functions")),
                null);
        ResponseEntity<byte[]> downloaded = controller.downloadBatchZip(batch.batchId(), null);

        assertThat(batch.status()).isEqualTo("COMPLETED");
        assertThat(batch.requestedCount()).isEqualTo(2);
        assertThat(batch.exportedCount()).isEqualTo(2);
        assertThat(batch.folderIds()).containsExactly("folder-algebra");
        assertThat(batch.folderPaths()).containsExactly("grade-10/functions");
        assertThat(batch.expiresAt()).isAfter(Instant.now().minusSeconds(5));
        assertThat(downloaded.getBody()).startsWith(new byte[] {'P', 'K'});
        assertThat(downloaded.getHeaders().getContentDisposition().getFilename()).isEqualTo(batch.batchId() + ".zip");
        assertThat(zipEntries(downloaded.getBody())).contains(
                "grade-10/functions/" + first.taskId() + ".tex",
                "grade-10/functions/" + first.taskId() + ".pdf",
                "grade-10/functions/" + second.taskId() + ".tex",
                "grade-10/functions/" + second.taskId() + ".pdf",
                "manifest.txt");
    }

    @Test
    void rejectsExpiredTemporaryBatchZipDownloads() throws Exception {
        MutableClock clock = new MutableClock(Instant.parse("2026-06-28T08:00:00Z"));
        TeachingWorkflowService service = testWorkflowService(
                createTextbookCorpus(),
                com.doob.mathagent.retrieval.TextbookRetrievalServiceFixture.service(
                        new TextbookCatalogReader(),
                        new TextbookChunkReader(),
                        new LocalTextbookBm25SearchEngine(),
                        new NoopRetrievalAuditSink()),
                new InMemoryTeachingTaskStore(),
                new StudentMemoryReuseService(new InMemoryStudentMemoryStore()));
        TeachingHandoutPdfExportService pdfExportService = new TeachingHandoutPdfExportService();
        TeachingTaskController controller = testController(
                service,
                teacherResolver(),
                (token, action, path, requestHash, subject) -> true,
                pdfExportService,
                new TeachingHandoutBatchExportService(pdfExportService, clock, Duration.ofMinutes(30)));
        TeachingTaskResponse task = controller.submit(
                new TeachingTaskRequest("client-expired", "question", "goal", 3),
                null);
        TeachingHandoutBatchExportResponse batch = controller.createBatchZip(
                new TeachingHandoutBatchExportRequest(List.of(task.taskId()), List.of(), List.of()),
                null);

        clock.advance(Duration.ofMinutes(31));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> controller.downloadBatchZip(batch.batchId(), null))
                .isInstanceOfSatisfying(ResponseStatusException.class, exception ->
                        assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.GONE));
    }

    @Test
    void submitsAndListsHumanFeedbackForOwnedTeachingTask() throws Exception {
        TeachingWorkflowService service = testWorkflowService(
                createTextbookCorpus(),
                com.doob.mathagent.retrieval.TextbookRetrievalServiceFixture.service(
                        new TextbookCatalogReader(),
                        new TextbookChunkReader(),
                        new LocalTextbookBm25SearchEngine(),
                        new NoopRetrievalAuditSink()),
                new InMemoryTeachingTaskStore(),
                new StudentMemoryReuseService(new InMemoryStudentMemoryStore()));
        TeachingTaskController controller = testController(
                service,
                teacherResolver(),
                (token, action, path, requestHash, subject) -> true,
                new TeachingHandoutPdfExportService(),
                batchExportService(new TeachingHandoutPdfExportService()));
        TeachingTaskResponse task = controller.submit(
                new TeachingTaskRequest("client-feedback", "question", "goal", 3),
                null);

        TeachingHumanFeedbackResponse feedback = controller.submitHumanFeedback(
                task.taskId(),
                new TeachingHumanFeedbackRequest(4, "needs_revision", "second step needs more detail"),
                null);
        List<TeachingHumanFeedbackResponse> feedbackList = controller.listHumanFeedback(task.taskId(), null);

        assertThat(feedback.taskId()).isEqualTo(task.taskId());
        assertThat(feedback.rating()).isEqualTo(4);
        assertThat(feedback.decision()).isEqualTo("needs_revision");
        assertThat(feedback.comment()).contains("second step");
        assertThat(feedbackList).extracting(TeachingHumanFeedbackResponse::feedbackId)
                .containsExactly(feedback.feedbackId());
    }

    @Test
    void rejectsHumanFeedbackWithoutAcceptedCapabilityToken() throws Exception {
        TeachingWorkflowService service = testWorkflowService(
                createTextbookCorpus(),
                com.doob.mathagent.retrieval.TextbookRetrievalServiceFixture.service(
                        new TextbookCatalogReader(),
                        new TextbookChunkReader(),
                        new LocalTextbookBm25SearchEngine(),
                        new NoopRetrievalAuditSink()),
                new InMemoryTeachingTaskStore(),
                new StudentMemoryReuseService(new InMemoryStudentMemoryStore()));
        TeachingTaskController setupController = testController(
                service,
                teacherResolver(),
                (token, action, path, requestHash, subject) -> true,
                new TeachingHandoutPdfExportService(),
                batchExportService(new TeachingHandoutPdfExportService()));
        TeachingTaskResponse task = setupController.submit(
                new TeachingTaskRequest("client-feedback-protected", "question", "goal", 3),
                null);
        TeachingTaskController protectedController = testController(
                service,
                teacherResolver(),
                (token, action, path, requestHash, subject) -> false,
                new TeachingHandoutPdfExportService(),
                batchExportService(new TeachingHandoutPdfExportService()));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> protectedController.submitHumanFeedback(
                        task.taskId(),
                        new TeachingHumanFeedbackRequest(5, "helpful", "handout is usable"),
                        null))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Capability token");
    }

    private Path createTextbookCorpus() throws Exception {
        Path root = tempDir.resolve("processed_books");
        Path bookRoot = root.resolve("book_a");
        Files.createDirectories(bookRoot.resolve("jsonl"));
        Files.writeString(root.resolve("catalog.jsonl"), """
                {"doc_id":"book_a","book_name":"Textbook A","volume":"required volume 1","book_root":"%s","manifest":"%s","chunk_count":1,"page_count":1,"ai_ok":false}
                """.formatted(escape(bookRoot), escape(bookRoot.resolve("manifest.json"))));
        Files.writeString(bookRoot.resolve("jsonl/chunks.jsonl"), """
                {"chunk_id":"book_a_p101_text_001","doc_id":"book_a","book_name":"Textbook A","volume":"required volume 1","chapter_path":["Chapter 3 Functions"],"page_no":101,"printed_page_no":"98","chunk_type":"page_summary","section_title":"Function concept","text":"function concept piecewise function definition domain range set D x0 increment d","formula_text":"D(x_0)=\\\\{d\\\\in R|f(x_0+d)>f(x_0)\\\\}","image_rel_paths":[],"source_page_image":"pages/p101.png"}
                """);
        return root;
    }

    private static String escape(Path path) {
        return path.toString().replace("\\", "\\\\");
    }

    private static List<String> zipEntries(byte[] zipBytes) throws Exception {
        try (ZipInputStream input = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
            java.util.ArrayList<String> names = new java.util.ArrayList<>();
            java.util.zip.ZipEntry entry;
            while ((entry = input.getNextEntry()) != null) {
                names.add(entry.getName());
            }
            return names;
        }
    }

    private static RequestSubjectResolver teacherResolver() {
        return request -> new com.doob.mathagent.infrastructure.security.RequestSubject("school-a", "teacher", "teacher-001", "dev-device");
    }

    private static RequestSubjectResolver studentResolver() {
        return request -> new com.doob.mathagent.infrastructure.security.RequestSubject("school-a", "student", "student-001", "dev-device");
    }

    private static final class MutableClock extends Clock {

        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }

        void advance(Duration duration) {
            instant = instant.plus(duration);
        }
    }
}

