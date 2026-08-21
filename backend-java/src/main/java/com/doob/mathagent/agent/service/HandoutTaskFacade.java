package com.doob.mathagent.agent.service;

import com.doob.mathagent.agent.dto.MultiAgentWritingRequest;
import com.doob.mathagent.agent.vo.AgentRunExecuteResponse;
import com.doob.mathagent.agent.vo.AgentTraceResponse;
import com.doob.mathagent.agent.vo.MultiAgentWritingArtifactExportResponse;
import com.doob.mathagent.agent.vo.MultiAgentWritingTraceResponse;
import com.doob.mathagent.agent.vo.MultiAgentWritingResponse;
import com.doob.mathagent.infrastructure.security.RequestSubject;
import com.doob.mathagent.teaching.TeachingRequestContext;
import com.doob.mathagent.teaching.TeachingWorkflowNode;
import com.doob.mathagent.teaching.dto.TeachingTaskRequest;
import com.doob.mathagent.teaching.service.LectureTaskSubmissionService;
import com.doob.mathagent.teaching.service.TeachingHandoutPdfExportService;
import com.doob.mathagent.teaching.service.TeachingWorkflowService;
import com.doob.mathagent.teaching.vo.TeachingTaskResponse;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.springframework.stereotype.Service;

/**
 * Compatibility boundary for the retired agent-writing API.
 *
 * <p>The browser may continue to call the old endpoint during the canary, but every request is converted to exactly
 * one teaching task. This keeps ownership, resume and publication state in the MySQL-authoritative teaching store
 * instead of maintaining a second workflow record.</p>
 */
@Service
public class HandoutTaskFacade {

    /** The task service accepts a positive evidence limit even when the legacy request contains no references. */
    private static final int DEFAULT_EVIDENCE_LIMIT = 1;
    /** Bounded legacy references preserve the teaching retrieval contract without using an unbounded browser list. */
    private static final int MAX_EVIDENCE_LIMIT = 24;
    private static final String CLIENT_REQUEST_PREFIX = "writing-";
    private static final String HASH_ALGORITHM = "SHA-256";
    /** Legacy export payloads are transport-only and expire after the same bounded window as the former endpoint. */
    private static final Duration EXPORT_TTL = Duration.ofMinutes(30);

    private final LectureTaskSubmissionService submissionService;
    private final TeachingWorkflowService workflowService;
    private final TeachingHandoutPdfExportService pdfExportService;

    /**
     * Uses the durable submitter for create and resume so an outbox event, not the HTTP request, starts execution.
     */
    public HandoutTaskFacade(
            LectureTaskSubmissionService submissionService,
            TeachingWorkflowService workflowService,
            TeachingHandoutPdfExportService pdfExportService) {
        this.submissionService = submissionService;
        this.workflowService = workflowService;
        this.pdfExportService = pdfExportService;
    }

    /** Creates or returns the one durable teaching task represented by a legacy write request. */
    public MultiAgentWritingResponse submit(MultiAgentWritingRequest request, RequestSubject subject) {
        TeachingTaskResponse task = submissionService.submit(toTeachingTaskRequest(request), contextFor(subject));
        return project(task);
    }

    /** The asynchronous legacy endpoint has the same durable creation semantics as the canonical teaching endpoint. */
    public MultiAgentWritingResponse startAsync(MultiAgentWritingRequest request, RequestSubject subject) {
        return submit(request, subject);
    }

    /**
     * Starts an MCP submission with its caller-scoped idempotency key when supplied.
     *
     * <p>The key is transport metadata only. It never participates in the writer input, evidence selection, or
     * published handout, but lets a caller recover one uncertain POST without forcing separate fresh runs to reuse
     * an older content-derived task.</p>
     */
    public MultiAgentWritingResponse startAsync(
            MultiAgentWritingRequest request,
            RequestSubject subject,
            String clientRequestId) {
        TeachingTaskResponse task = submissionService.submit(
                toTeachingTaskRequest(request, clientRequestId),
                contextFor(subject));
        return project(task);
    }

    /** Reads an owned task through the teaching store; workflowId is intentionally only a compatibility alias. */
    public MultiAgentWritingResponse get(String workflowId, RequestSubject subject) {
        TeachingTaskResponse task = workflowService.get(workflowId, contextFor(subject))
                .orElseThrow(() -> new IllegalArgumentException("Teaching task not found"));
        return project(task);
    }

    /** Resumes the original durable task and enqueues a distinct outbox retry event without creating another task. */
    public MultiAgentWritingResponse resume(String workflowId, RequestSubject subject) {
        TeachingTaskResponse task = submissionService.resume(workflowId, contextFor(subject), workflowService);
        return project(task);
    }

    /**
     * Projects owner-visible teaching artifacts into the temporary legacy shape without reading the retired workflow
     * store. The teaching task remains the only persisted source for every version and structured review section.
     */
    public MultiAgentWritingArtifact artifact(String workflowId, RequestSubject subject) {
        TeachingTaskResponse task = ownedTask(workflowId, subject);
        List<MultiAgentWritingArtifact.StageArtifact> stages = List.of(
                artifactStage("teacher_writer", "CoursewareAgent", task.teacherHandoutLatex(), task.status().name()),
                artifactStage("student_writer", "TeacherAssistantAgent", task.studentHandoutLatex(), task.status().name()),
                artifactStage("lecture_writer", "HandoutFormatterAgent", task.lectureHandoutLatex(), task.status().name()));
        List<MultiAgentWritingArtifact.StructuredSection> sections = List.of(
                section("teacher", "教师版讲义", "teacher_writer", task.draftSections().teacherExplanation()),
                section("student", "学生版讲义", "student_writer", task.draftSections().studentWorksheet()),
                section("lecture", "课堂投影", "lecture_writer", String.join("\n\n", task.draftSections().lectureCards())));
        return new MultiAgentWritingArtifact(
                task.taskId(), task.tenantId(), task.subjectType(), task.subjectId(), task.status().name(),
                new AgentRunExecuteResponse.TokenUsage(0, 0, 0), stages, sections, task.handoutLatex());
    }

    /**
     * Projects durable teaching events rather than querying legacy agent traces. Unknown provider cost remains -1
     * with {@code costKnown=false}; no task snapshot is allowed to invent provider usage or price values.
     */
    public MultiAgentWritingTraceResponse traces(String workflowId, RequestSubject subject) {
        TeachingTaskResponse task = ownedTask(workflowId, subject);
        List<AgentTraceResponse> events = task.workflowEvents().stream()
                .map(event -> new AgentTraceResponse(
                        event.eventId(), task.taskId() + ":" + event.eventType(), null,
                        task.tenantId(), task.subjectType(), task.subjectId(), event.sourceName(),
                        "teaching-task", "", event.status(), -1.0d, List.of(), List.of(), event.artifactRefs(),
                        List.of(), new AgentRunExecuteResponse.TokenUsage(0, 0, 0), event.summary(), List.of(),
                        -1.0d, false))
                .toList();
        return new MultiAgentWritingTraceResponse(
                task.taskId(), task.tenantId(), task.subjectType(), task.subjectId(), events.size(),
                new AgentRunExecuteResponse.TokenUsage(0, 0, 0), events);
    }

    /**
     * Exports only task-owned versions through the Java publication renderer. The compatibility endpoint never reads
     * the retired workflow store and it never creates a PDFBox fallback when XeLaTeX rejects the publication gate.
     */
    public MultiAgentWritingArtifactExportResponse export(
            String workflowId,
            String format,
            String headerText,
            String footerText,
            RequestSubject subject) {
        TeachingTaskResponse task = ownedTask(workflowId, subject);
        String normalizedFormat = normalizeExportFormat(format);
        // Teaching task page chrome is persisted at creation time. Ignoring a later legacy header would be unsafe,
        // so the compatibility route rejects mutation rather than silently publishing mismatched audit content.
        if ((headerText != null && !headerText.isBlank()) || (footerText != null && !footerText.isBlank())) {
            throw new IllegalArgumentException("Export page chrome is fixed by the teaching task");
        }
        ExportPayload payload = switch (normalizedFormat) {
            case "markdown" -> new ExportPayload("handout.md", "text/markdown; charset=UTF-8",
                    markdownFor(task, "teacher").getBytes(StandardCharsets.UTF_8));
            case "latex" -> new ExportPayload("handout.tex", "application/x-tex; charset=UTF-8",
                    TeachingHandoutPdfExportService.sanitizeLatexForExport(task.handoutLatexFor("teacher"))
                            .getBytes(StandardCharsets.UTF_8));
            case "pdf", "pdf-teacher" -> pdfPayload(task, "teacher", "handout-teacher.pdf");
            case "pdf-student" -> pdfPayload(task, "student", "handout-student.pdf");
            case "pdf-lecture" -> pdfPayload(task, "lecture", "handout-lecture.pdf");
            case "zip" -> zipPayload(task);
            default -> throw new IllegalArgumentException("Unsupported artifact export format: " + normalizedFormat);
        };
        byte[] bytes = payload.bytes();
        return new MultiAgentWritingArtifactExportResponse(
                UUID.randomUUID().toString(), task.taskId(), normalizedFormat, payload.fileName(), payload.mimeType(),
                bytes.length, sha256Bytes(bytes), Base64.getEncoder().encodeToString(bytes), Instant.now().plus(EXPORT_TTL));
    }

    /**
     * Maps the legacy request into the only public handout business request.
     *
     * <p>The stable digest becomes the teaching idempotency key. It includes every legacy field that can affect
     * generation, so an HTTP retry returns the same task while a material request change creates a new task.</p>
     */
    static TeachingTaskRequest toTeachingTaskRequest(MultiAgentWritingRequest request) {
        return toTeachingTaskRequest(request, null);
    }

    /**
     * Maps MCP-only idempotency metadata into the canonical teaching request while retaining the legacy digest when
     * the caller did not provide a key.
     */
    static TeachingTaskRequest toTeachingTaskRequest(MultiAgentWritingRequest request, String suppliedClientRequestId) {
        MultiAgentWritingRequest normalized = request == null
                ? new MultiAgentWritingRequest("", "", List.of(), false, "", "")
                : request.normalize();
        int evidenceLimit = Math.max(DEFAULT_EVIDENCE_LIMIT,
                Math.min(MAX_EVIDENCE_LIMIT, normalized.evidenceRefs().size()));
        return new TeachingTaskRequest(
                resolvedClientRequestId(normalized, suppliedClientRequestId),
                normalized.questionText(),
                normalized.writingGoal(),
                evidenceLimit,
                null,
                null,
                null,
                null,
                null,
                null,
                normalized.preferredProviderName(),
                normalized.preferredModelCode(),
                null);
    }

    /** Converts a persisted task snapshot into the response shape consumed by existing canary clients. */
    static MultiAgentWritingResponse project(TeachingTaskResponse task) {
        List<MultiAgentWritingResponse.StageResult> stages = task.nodes().stream()
                .map(HandoutTaskFacade::projectNode)
                .toList();
        String status = task.status().name();
        String message = task.errorMessage() == null || task.errorMessage().isBlank()
                ? "Teaching task " + status.toLowerCase(java.util.Locale.ROOT)
                : task.errorMessage();
        // The teaching snapshot intentionally has no provider ledger fields. A zero here represents no ledger data,
        // never a price/cost claim; the immutable usage ledger becomes the source once Task 6 projection lands.
        AgentRunExecuteResponse.TokenUsage noLedgerUsage = new AgentRunExecuteResponse.TokenUsage(0, 0, 0);
        return new MultiAgentWritingResponse(
                task.taskId(), task.tenantId(), task.subjectType(), task.subjectId(), status,
                null, null, stages, noLedgerUsage, message);
    }

    /** Projects durable workflow-node status without recreating old agent plans or provider traces. */
    private static MultiAgentWritingResponse.StageResult projectNode(TeachingWorkflowNode node) {
        return new MultiAgentWritingResponse.StageResult(
                node.code(), "teaching-task", "", "", "", node.status(),
                new AgentRunExecuteResponse.TokenUsage(0, 0, 0), node.summary(), "", 0L);
    }

    /** Reads one task only after the teaching service has checked the backend-resolved owner context. */
    private TeachingTaskResponse ownedTask(String workflowId, RequestSubject subject) {
        return workflowService.get(workflowId, contextFor(subject))
                .orElseThrow(() -> new IllegalArgumentException("Teaching task not found"));
    }

    /** Builds one compatibility stage from a task-owned rendered version, not from a model response cache. */
    private static MultiAgentWritingArtifact.StageArtifact artifactStage(
            String stageCode,
            String agentCode,
            String content,
            String status) {
        return new MultiAgentWritingArtifact.StageArtifact(
                stageCode, agentCode, "", "teaching-task", "", status, content == null ? "" : content);
    }

    /** Keeps the three persisted audience artifacts visibly separate for legacy review clients. */
    private static MultiAgentWritingArtifact.StructuredSection section(
            String code,
            String title,
            String stageCode,
            String content) {
        return new MultiAgentWritingArtifact.StructuredSection(
                code, title, stageCode, content == null ? "" : content, List.of(), List.of(), List.of());
    }

    /** Reads the review-safe audience draft rather than converting or re-generating the published TeX body. */
    private static String markdownFor(TeachingTaskResponse task, String version) {
        return switch (version) {
            case "student" -> task.draftSections().studentWorksheet();
            case "lecture" -> String.join("\n\n", task.draftSections().lectureCards());
            default -> task.draftSections().teacherExplanation();
        };
    }

    /** Delegates PDF compilation and gate enforcement to the existing teaching publication service. */
    private ExportPayload pdfPayload(TeachingTaskResponse task, String version, String filename) {
        TeachingHandoutPdfExportService.RenderedHandoutPdf rendered = pdfExportService.renderForPublication(task, version);
        return new ExportPayload(filename, "application/pdf", rendered.bytes());
    }

    /** Packages the three independently publication-gated PDFs; no unrendered source is substituted on failure. */
    private ExportPayload zipPayload(TeachingTaskResponse task) {
        try (ByteArrayOutputStream output = new ByteArrayOutputStream(); ZipOutputStream archive = new ZipOutputStream(output)) {
            addZipEntry(archive, "teacher.pdf", pdfPayload(task, "teacher", "teacher.pdf").bytes());
            addZipEntry(archive, "student.pdf", pdfPayload(task, "student", "student.pdf").bytes());
            addZipEntry(archive, "lecture.pdf", pdfPayload(task, "lecture", "lecture.pdf").bytes());
            archive.finish();
            return new ExportPayload("handout-versions.zip", "application/zip", output.toByteArray());
        } catch (IOException exception) {
            throw new IllegalStateException("Could not package teaching handouts", exception);
        }
    }

    /** Writes a bounded in-memory publication byte array as one named archive item. */
    private static void addZipEntry(ZipOutputStream archive, String name, byte[] bytes) throws IOException {
        archive.putNextEntry(new ZipEntry(name));
        archive.write(bytes);
        archive.closeEntry();
    }

    /** Normalizes supported compatibility formats without accepting a caller-controlled filename or MIME type. */
    private static String normalizeExportFormat(String format) {
        String normalized = format == null ? "markdown" : format.strip().toLowerCase(Locale.ROOT);
        return normalized.isEmpty() ? "markdown" : normalized;
    }

    /** Hashes output bytes after XeLaTeX/publication gates have completed, preserving transport integrity metadata. */
    private static String sha256Bytes(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance(HASH_ALGORITHM).digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(HASH_ALGORITHM + " is unavailable", exception);
        }
    }

    /** Immutable transport payload used only inside the legacy response projection. */
    private record ExportPayload(String fileName, String mimeType, byte[] bytes) {
    }

    /** Creates the teaching authorization context only from the server-resolved session subject. */
    private static TeachingRequestContext contextFor(RequestSubject subject) {
        RequestSubject normalized = subject == null ? RequestSubject.anonymous(null, null) : subject.normalize();
        if (normalized.subjectId() == null || normalized.subjectId().isBlank()) {
            throw new IllegalArgumentException("Authenticated subject is required for handout tasks");
        }
        return new TeachingRequestContext(
                normalized.tenantId(), normalized.subjectType(), normalized.subjectId(), normalized.deviceId());
    }

    /** Uses SHA-256 rather than raw prompt content so durable idempotency keys never expose question text. */
    private static String stableClientRequestId(MultiAgentWritingRequest request) {
        String canonical = String.join("\u001f",
                request.writingGoal(), request.questionText(), String.join("\u001e", request.evidenceRefs()),
                Boolean.toString(request.dryRun()), request.preferredProviderName(), request.preferredModelCode());
        try {
            byte[] digest = MessageDigest.getInstance(HASH_ALGORITHM).digest(canonical.getBytes(StandardCharsets.UTF_8));
            return CLIENT_REQUEST_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(HASH_ALGORITHM + " is unavailable", exception);
        }
    }

    /**
     * Keeps externally supplied request IDs bounded and storage-safe without accepting opaque Unicode or whitespace
     * values that cannot be reproduced reliably by an MCP client after a disconnect.
     */
    private static String resolvedClientRequestId(MultiAgentWritingRequest request, String suppliedClientRequestId) {
        if (suppliedClientRequestId == null || suppliedClientRequestId.isBlank()) {
            return stableClientRequestId(request);
        }
        String normalized = suppliedClientRequestId.strip();
        if (normalized.length() > 128 || !normalized.matches("[A-Za-z0-9][A-Za-z0-9._:-]*")) {
            throw new IllegalArgumentException(
                    "clientRequestId must be 1-128 ASCII letters, digits, '.', '_', ':', or '-' characters");
        }
        return normalized;
    }
}
