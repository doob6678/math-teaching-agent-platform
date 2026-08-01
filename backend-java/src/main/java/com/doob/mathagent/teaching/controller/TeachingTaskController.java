package com.doob.mathagent.teaching.controller;

import com.doob.mathagent.infrastructure.security.RequestSubject;
import com.doob.mathagent.infrastructure.security.RequestSubjectResolver;
import com.doob.mathagent.teaching.TeachingRequestContext;
import com.doob.mathagent.teaching.TeachingReviewPolicy;
import com.doob.mathagent.teaching.dto.TeachingHandoutBatchExportRequest;
import com.doob.mathagent.teaching.dto.TeachingHandoutVersionUpdateRequest;
import com.doob.mathagent.teaching.dto.TeachingHumanFeedbackRequest;
import com.doob.mathagent.teaching.dto.TeachingReviewDecisionRequest;
import com.doob.mathagent.teaching.dto.TeachingTaskRequest;
import com.doob.mathagent.teaching.service.TeachingHandoutBatchExportRecord;
import com.doob.mathagent.teaching.service.TeachingHandoutBatchExportService;
import com.doob.mathagent.teaching.service.TeachingHumanFeedbackService;
import com.doob.mathagent.teaching.service.TeachingReviewAuditService;
import com.doob.mathagent.teaching.service.TeachingHandoutPdfExportService;
import com.doob.mathagent.teaching.service.TeachingHandoutTemplatePreviewService;
import com.doob.mathagent.teaching.service.TeachingHandoutTemplateService;
import com.doob.mathagent.teaching.service.LectureTaskSubmissionService;
import com.doob.mathagent.teaching.service.TeachingTaskEventStreamService;
import com.doob.mathagent.teaching.service.TeachingWorkflowService;
import com.doob.mathagent.teaching.vo.TeachingHandoutBatchExportResponse;
import com.doob.mathagent.teaching.vo.TeachingHandoutTemplateResponse;
import com.doob.mathagent.teaching.vo.TeachingHumanFeedbackResponse;
import com.doob.mathagent.teaching.vo.TeachingTaskResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.transaction.annotation.Transactional;

/**
 * 教学任务接口：前端提交任务后可通过 taskId 持续查询结果，支持页面离开后的恢复。
 */
@RestController
public class TeachingTaskController {

    private static final String TEACHING_TASKS_PATH = "/api/teaching/tasks";
    private static final String HANDOUT_RENDERER_HEADER = "X-Handout-Renderer";
    private static final String HANDOUT_PAGE_COUNT_HEADER = "X-Handout-Page-Count";

    private final TeachingWorkflowService workflowService;
    private final LectureTaskSubmissionService lectureTaskSubmissionService;
    private final RequestSubjectResolver subjectResolver;
    private final TeachingHandoutPdfExportService pdfExportService;
    private final TeachingHandoutBatchExportService batchExportService;
    private final TeachingHumanFeedbackService feedbackService;
    private final TeachingReviewAuditService reviewAuditService;
    private final TeachingHandoutTemplateService handoutTemplateService;
    private final TeachingHandoutTemplatePreviewService handoutTemplatePreviewService;
    private final TeachingTaskEventStreamService eventStreamService;

    /**
     * 注入教学编排服务。
     */
    @Autowired
    public TeachingTaskController(
            TeachingWorkflowService workflowService,
            LectureTaskSubmissionService lectureTaskSubmissionService,
            RequestSubjectResolver subjectResolver,
            TeachingHandoutPdfExportService pdfExportService,
            TeachingHandoutBatchExportService batchExportService,
            TeachingHumanFeedbackService feedbackService,
            TeachingReviewAuditService reviewAuditService,
            TeachingHandoutTemplateService handoutTemplateService,
            TeachingHandoutTemplatePreviewService handoutTemplatePreviewService,
            TeachingTaskEventStreamService eventStreamService) {
        this.workflowService = workflowService;
        this.lectureTaskSubmissionService = lectureTaskSubmissionService;
        this.subjectResolver = subjectResolver;
        this.pdfExportService = pdfExportService;
        this.batchExportService = batchExportService;
        this.feedbackService = feedbackService;
        this.reviewAuditService = reviewAuditService;
        this.handoutTemplateService = handoutTemplateService;
        this.handoutTemplatePreviewService = handoutTemplatePreviewService;
        this.eventStreamService = eventStreamService;
    }

    /**
     * Backward-compatible constructor that uses the built-in template registry.
     */
    public TeachingTaskController(
            TeachingWorkflowService workflowService,
            RequestSubjectResolver subjectResolver,
            TeachingHandoutPdfExportService pdfExportService,
            TeachingHandoutBatchExportService batchExportService,
            TeachingHumanFeedbackService feedbackService) {
        this(
                workflowService,
                null,
                subjectResolver,
                pdfExportService,
                batchExportService,
                feedbackService,
                new TeachingReviewAuditService(new com.doob.mathagent.teaching.service.InMemoryTeachingReviewAuditStore()),
                new TeachingHandoutTemplateService(),
                new TeachingHandoutTemplatePreviewService(new TeachingHandoutTemplateService()),
                new TeachingTaskEventStreamService());
    }

    /**
     * 提交教学任务；当前阶段同步完成，后续可切换为异步队列。
     */
    @PostMapping("/api/teaching/tasks")
    public TeachingTaskResponse submit(
            @Valid @RequestBody TeachingTaskRequest request,
            HttpServletRequest httpRequest) {
        RequestSubject subject = subjectResolver.resolve(httpRequest);
        // The HTTP request creates only durable MySQL state. The outbox publisher later sends the opaque taskId.
        TeachingTaskResponse response = lectureTaskSubmissionService == null
                ? workflowService.submit(request, requestContext(subject))
                : lectureTaskSubmissionService.submit(request, requestContext(subject));
        return visibleToSubject(response, subject);
    }

    /**
     * Lists recent teaching tasks owned by the backend-resolved session subject.
     */
    @GetMapping("/api/teaching/tasks")
    public List<TeachingTaskResponse> list(
            @RequestParam(defaultValue = "20") int limit,
            HttpServletRequest httpRequest) {
        RequestSubject subject = subjectResolver.resolve(httpRequest);
        return workflowService.listRecent(requestContext(subject), limit).stream()
                .map(task -> visibleToSubject(task, subject))
                .toList();
    }

    /**
     * Lists backend-owned handout templates that may be selected during teaching-task creation.
     */
    @GetMapping("/api/teaching/handout-templates")
    public List<TeachingHandoutTemplateResponse> listTemplates() {
        return handoutTemplateService.list().stream()
                .map(TeachingTaskController::sanitizeTemplateForFrontend)
                .toList();
    }

    /**
     * Returns a real first-page PNG preview for PDF-backed templates without exposing filesystem paths to the browser.
     */
    @GetMapping("/api/teaching/handout-templates/{templateCode}/preview.png")
    public ResponseEntity<byte[]> previewTemplateImage(
            @PathVariable String templateCode,
            HttpServletRequest httpRequest) {
        RequestSubject subject = subjectResolver.resolve(httpRequest);
        if ("anonymous".equalsIgnoreCase(subject.subjectType())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Login required for template preview");
        }
        byte[] png = handoutTemplatePreviewService.renderPreviewPng(templateCode)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Teaching template preview not found"));
        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_PNG)
                .body(png);
    }

    /**
     * 按 taskId 查询任务结果；只允许任务归属主体读取。
     */
    /**
     * Returns the original template reference PDF for real multipage frontend preview.
     */
    @GetMapping("/api/teaching/handout-templates/{templateCode}/reference.pdf")
    public ResponseEntity<byte[]> previewTemplatePdf(
            @PathVariable String templateCode,
            HttpServletRequest httpRequest) {
        RequestSubject subject = subjectResolver.resolve(httpRequest);
        if ("anonymous".equalsIgnoreCase(subject.subjectType())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Login required for template preview");
        }
        byte[] pdf = handoutTemplatePreviewService.loadReferencePdf(templateCode)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Teaching template reference PDF not found"));
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .body(pdf);
    }

    @GetMapping("/api/teaching/tasks/{taskId}")
    public TeachingTaskResponse get(
            @PathVariable String taskId,
            HttpServletRequest httpRequest) {
        RequestSubject subject = subjectResolver.resolve(httpRequest);
        return workflowService.get(taskId, requestContext(subject))
                .map(task -> visibleToSubject(task, subject))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Teaching task not found"));
    }

    /** Resumes an owned failed task using its original task ID and idempotency identity. */
    @PostMapping("/api/teaching/tasks/{taskId}/resume")
    public TeachingTaskResponse resume(
            @PathVariable String taskId,
            HttpServletRequest httpRequest) {
        RequestSubject subject = subjectResolver.resolve(httpRequest);
        try {
            TeachingTaskResponse response = lectureTaskSubmissionService == null
                    ? workflowService.resume(taskId, requestContext(subject))
                    : lectureTaskSubmissionService.resume(taskId, requestContext(subject), workflowService);
            return visibleToSubject(response, subject);
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage());
        } catch (IllegalStateException exception) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, exception.getMessage());
        }
    }

    /** Approves or rejects a quality-gated handout without repeating any model call. */
    @PostMapping("/api/teaching/tasks/{taskId}/review")
    @Transactional
    public TeachingTaskResponse decideReview(
            @PathVariable String taskId,
            @Valid @RequestBody TeachingReviewDecisionRequest request,
            HttpServletRequest httpRequest) {
        RequestSubject subject = subjectResolver.resolve(httpRequest);
        try {
            TeachingRequestContext context = requestContext(subject);
            TeachingTaskResponse before = workflowService.get(taskId, context)
                    .orElseThrow(() -> new IllegalArgumentException("Teaching task not found"));
            TeachingTaskResponse decided = workflowService.decideReview(
                    taskId, context, request.normalizedDecision(), request.normalizedComment());
            // Feedback storage is append-only in MySQL.  The review record links the exact common draft and the
            // released version hashes without persisting raw private handout text a second time.
            feedbackService.submit(taskId, context, new TeachingHumanFeedbackRequest(
                    "APPROVE".equals(request.normalizedDecision()) ? 5 : 1,
                    "handout_" + request.normalizedDecision().toLowerCase(java.util.Locale.ROOT),
                    request.normalizedComment(),
                    Map.of(
                            "reviewedAt", Instant.now().toString(),
                            "policy", TeachingReviewPolicy.fromEnvironment().name(),
                            "draftHash", sha256(before.aiDraft() == null ? "" : before.aiDraft().content()),
                            "qualityStatus", before.mergeResult().status(),
                            "teacherVersionHash", sha256(decided.teacherHandoutLatex()),
                            "studentVersionHash", sha256(decided.studentHandoutLatex()),
                            "lectureVersionHash", sha256(decided.lectureHandoutLatex()),
                            "publishedStatus", decided.status().name())));
            reviewAuditService.record(
                    taskId,
                    context,
                    TeachingReviewPolicy.fromEnvironment(),
                    request.normalizedDecision(),
                    request.normalizedComment(),
                    sha256(before.aiDraft() == null ? "" : before.aiDraft().content()),
                    before.mergeResult().status(),
                    sha256(decided.teacherHandoutLatex()),
                    sha256(decided.studentHandoutLatex()),
                    sha256(decided.lectureHandoutLatex()),
                    decided.status().name());
            return decided;
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage(), exception);
        } catch (IllegalStateException exception) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, exception.getMessage(), exception);
        }
    }

    /** Hashes review snapshots for immutable audit linkage without writing source material into the feedback row. */
    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest((value == null ? "" : value).getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    /**
     * Streams user-visible snapshots from the same owned task record returned by the normal read endpoint.
     *
     * <p>The first snapshot is delivered immediately. Subsequent snapshots appear only after a durable state change,
     * while an ordinary GET remains the reconnect and page-recovery contract.</p>
     */
    @GetMapping(value = "/api/teaching/tasks/{taskId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(
            @PathVariable String taskId,
            HttpServletRequest httpRequest) {
        TeachingRequestContext context = requestContext(subjectResolver.resolve(httpRequest));
        if (workflowService.get(taskId, context).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Teaching task not found");
        }
        return eventStreamService.stream(() -> workflowService.get(taskId, context)
                .map(task -> visibleToSubject(task, subjectResolver.resolve(httpRequest))));
    }

    /**
     * Saves one editable version back onto its original owned task. The workflow service re-applies generation-time
     * safety filters and verifies task ownership before writing the durable task snapshot.
     */
    @PutMapping("/api/teaching/tasks/{taskId}/handout/{version}")
    public TeachingTaskResponse updateHandoutVersion(
            @PathVariable String taskId,
            @PathVariable String version,
            @Valid @RequestBody TeachingHandoutVersionUpdateRequest request,
            HttpServletRequest httpRequest) {
        String normalizedVersion = normalizeHandoutVersion(version);
        RequestSubject subject = subjectResolver.resolve(httpRequest);
        requireHandoutVersionAllowed(normalizedVersion, subject);
        try {
            return workflowService.updateHandoutVersion(
                    taskId,
                    normalizedVersion,
                    request.normalizedLatex(),
                    requestContext(subject));
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage());
        } catch (IllegalStateException exception) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, exception.getMessage());
        }
    }

    /**
     * 从后端会话身份读取租户、主体和设备信息，用于任务隔离和审计。
     */
    /**
     * Exports the LaTeX handout after session identity and task ownership checks.
     */
    @GetMapping("/api/teaching/tasks/{taskId}/handout/latex")
    public ResponseEntity<String> exportLatex(
            @PathVariable String taskId,
            HttpServletRequest httpRequest) {
        RequestSubject subject = subjectResolver.resolve(httpRequest);
        TeachingTaskResponse task = workflowService.get(taskId, requestContext(subject))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Teaching task not found"));
        String effectiveVersion = defaultHandoutVersion(subject);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/x-tex;charset=UTF-8"))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(task.taskId() + ".tex", StandardCharsets.UTF_8)
                        .build()
                        .toString())
                .body(TeachingHandoutPdfExportService.sanitizeLatexForExport(task.handoutLatexFor(effectiveVersion)));
    }

    /**
     * Exports a specific LaTeX handout version after role and task ownership checks.
     */
    @GetMapping("/api/teaching/tasks/{taskId}/handout/{version}/latex")
    public ResponseEntity<String> exportLatexVersion(
            @PathVariable String taskId,
            @PathVariable String version,
            HttpServletRequest httpRequest) {
        String normalizedVersion = normalizeHandoutVersion(version);
        RequestSubject subject = subjectResolver.resolve(httpRequest);
        requireHandoutVersionAllowed(normalizedVersion, subject);
        TeachingTaskResponse task = workflowService.get(taskId, requestContext(subject))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Teaching task not found"));
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/x-tex;charset=UTF-8"))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(task.taskId() + "-" + normalizedVersion + ".tex", StandardCharsets.UTF_8)
                        .build()
                        .toString())
                .body(TeachingHandoutPdfExportService.sanitizeLatexForExport(task.handoutLatexFor(normalizedVersion)));
    }

    /**
     * Previews the LaTeX handout inline after session identity and task ownership checks.
     */
    @GetMapping("/api/teaching/tasks/{taskId}/handout/latex/preview")
    public ResponseEntity<String> previewLatex(
            @PathVariable String taskId,
            HttpServletRequest httpRequest) {
        RequestSubject subject = subjectResolver.resolve(httpRequest);
        TeachingTaskResponse task = workflowService.get(taskId, requestContext(subject))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Teaching task not found"));
        String effectiveVersion = defaultHandoutVersion(subject);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/x-tex;charset=UTF-8"))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline()
                        .filename(task.taskId() + ".tex", StandardCharsets.UTF_8)
                        .build()
                        .toString())
                .body(TeachingHandoutPdfExportService.sanitizeLatexForExport(task.handoutLatexFor(effectiveVersion)));
    }

    /**
     * Previews a specific handout version inline after role and task ownership checks.
     */
    @GetMapping("/api/teaching/tasks/{taskId}/handout/{version}/latex/preview")
    public ResponseEntity<String> previewLatexVersion(
            @PathVariable String taskId,
            @PathVariable String version,
            HttpServletRequest httpRequest) {
        String normalizedVersion = normalizeHandoutVersion(version);
        RequestSubject subject = subjectResolver.resolve(httpRequest);
        requireHandoutVersionAllowed(normalizedVersion, subject);
        TeachingTaskResponse task = workflowService.get(taskId, requestContext(subject))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Teaching task not found"));
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/x-tex;charset=UTF-8"))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline()
                        .filename(task.taskId() + "-" + normalizedVersion + ".tex", StandardCharsets.UTF_8)
                        .build()
                        .toString())
                .body(TeachingHandoutPdfExportService.sanitizeLatexForExport(task.handoutLatexFor(normalizedVersion)));
    }

    /**
     * Exports the PDF handout after session identity and task ownership checks.
     */
    @GetMapping("/api/teaching/tasks/{taskId}/handout/pdf")
    public ResponseEntity<byte[]> exportPdf(
            @PathVariable String taskId,
            HttpServletRequest httpRequest) {
        RequestSubject subject = subjectResolver.resolve(httpRequest);
        TeachingTaskResponse task = workflowService.get(taskId, requestContext(subject))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Teaching task not found"));
        String effectiveVersion = defaultHandoutVersion(subject);
        TeachingHandoutPdfExportService.RenderedHandoutPdf rendered = renderForPublication(task, effectiveVersion);
        return pdfResponse(rendered, handoutFileName(task, effectiveVersion), false);
    }

    /**
     * Exports a specific PDF handout version after role and task ownership checks.
     */
    @GetMapping("/api/teaching/tasks/{taskId}/handout/{version}/pdf")
    public ResponseEntity<byte[]> exportPdfVersion(
            @PathVariable String taskId,
            @PathVariable String version,
            HttpServletRequest httpRequest) {
        String normalizedVersion = normalizeHandoutVersion(version);
        RequestSubject subject = subjectResolver.resolve(httpRequest);
        requireHandoutVersionAllowed(normalizedVersion, subject);
        TeachingTaskResponse task = workflowService.get(taskId, requestContext(subject))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Teaching task not found"));
        TeachingHandoutPdfExportService.RenderedHandoutPdf rendered = renderForPublication(task, normalizedVersion);
        return pdfResponse(rendered, handoutFileName(task, normalizedVersion), false);
    }

    /**
     * Previews the default PDF handout inline after session identity and task ownership checks.
     */
    @GetMapping("/api/teaching/tasks/{taskId}/handout/pdf/preview")
    public ResponseEntity<byte[]> previewPdf(
            @PathVariable String taskId,
            HttpServletRequest httpRequest) {
        RequestSubject subject = subjectResolver.resolve(httpRequest);
        TeachingTaskResponse task = workflowService.get(taskId, requestContext(subject))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Teaching task not found"));
        String effectiveVersion = defaultHandoutVersion(subject);
        TeachingHandoutPdfExportService.RenderedHandoutPdf rendered = renderForPublication(task, effectiveVersion);
        return pdfResponse(rendered, handoutFileName(task, effectiveVersion), true);
    }

    /**
     * Previews a specific PDF handout version inline after role and task ownership checks.
     */
    @GetMapping("/api/teaching/tasks/{taskId}/handout/{version}/pdf/preview")
    public ResponseEntity<byte[]> previewPdfVersion(
            @PathVariable String taskId,
            @PathVariable String version,
            HttpServletRequest httpRequest) {
        String normalizedVersion = normalizeHandoutVersion(version);
        RequestSubject subject = subjectResolver.resolve(httpRequest);
        requireHandoutVersionAllowed(normalizedVersion, subject);
        TeachingTaskResponse task = workflowService.get(taskId, requestContext(subject))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Teaching task not found"));
        TeachingHandoutPdfExportService.RenderedHandoutPdf rendered = renderForPublication(task, normalizedVersion);
        return pdfResponse(rendered, handoutFileName(task, normalizedVersion), true);
    }

    /** Maps the shared publication gate to one consistent HTTP contract for preview and download routes. */
    private TeachingHandoutPdfExportService.RenderedHandoutPdf renderForPublication(
            TeachingTaskResponse task,
            String version) {
        try {
            return pdfExportService.renderForPublication(task, version);
        } catch (IllegalStateException exception) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, exception.getMessage(), exception);
        }
    }

    private static ResponseEntity<byte[]> pdfResponse(
            TeachingHandoutPdfExportService.RenderedHandoutPdf rendered,
            String fileName,
            boolean inline) {
        ContentDisposition disposition = inline
                ? ContentDisposition.inline().filename(fileName, StandardCharsets.UTF_8).build()
                : ContentDisposition.attachment().filename(fileName, StandardCharsets.UTF_8).build();
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .header(HANDOUT_RENDERER_HEADER, rendered.renderer())
                .header(HANDOUT_PAGE_COUNT_HEADER, Integer.toString(rendered.pageCount()))
                .body(rendered.bytes());
    }

    /**
     * Builds a stable human-readable filename from the persisted learning goal.
     *
     * <p>UUID-only filenames made downloaded handouts impossible to identify and encouraged users to rename files
     * manually.  The task id remains an internal lookup key; the export name is deliberately based on visible
     * subject text and the requested audience version.  Removing filesystem separators and control characters keeps
     * the value safe for both Windows and Linux download clients.</p>
     */
    private static String handoutFileName(TeachingTaskResponse task, String version) {
        String topic = task.learningGoal() == null ? "数学讲义" : task.learningGoal().strip();
        String safeTopic = topic.replaceAll("[\\p{Cntrl}\\\\/:*?\"<>|]+", " ")
                .replaceAll("\\s+", " ")
                .strip();
        if (safeTopic.isBlank()) {
            safeTopic = "数学讲义";
        }
        String versionCode = version == null ? "" : version.toLowerCase(Locale.ROOT);
        return switch (versionCode) {
            case "student" -> safeTopic + "（学生讲义）（学生版）.pdf";
            case "lecture" -> safeTopic + "（讲解版）.pdf";
            default -> safeTopic + "（教师讲义）（教师版）.pdf";
        };
    }

    /**
     * Creates a short-lived ZIP package for owned teaching handouts.
     */
    @PostMapping("/api/teaching/handouts/batch/zip")
    public TeachingHandoutBatchExportResponse createBatchZip(
            @Valid @RequestBody TeachingHandoutBatchExportRequest request,
            HttpServletRequest httpRequest) {
        RequestSubject subject = subjectResolver.resolve(httpRequest);
        TeachingRequestContext context = requestContext(subject);
        TeachingHandoutBatchExportRequest normalized = request.normalize();
        List<TeachingTaskResponse> tasks = normalized.taskIds().stream()
                .map(taskId -> workflowService.get(taskId, context)
                        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Teaching task not found")))
                .toList();
        try {
            validateBatchPublicationTasks(tasks, subject);
            return batchExportService.create(normalized, context, tasks);
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage(), exception);
        } catch (IllegalStateException exception) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, exception.getMessage(), exception);
        }
    }

    /** Applies the same page-count contract to every version the requested ZIP will publish. */
    private void validateBatchPublicationTasks(List<TeachingTaskResponse> tasks, RequestSubject subject) {
        for (TeachingTaskResponse task : tasks) {
            if (canUseTeacherHandout(subject)) {
                pdfExportService.renderForPublication(task, "teacher");
                pdfExportService.renderForPublication(task, "lecture");
            }
            pdfExportService.renderForPublication(task, "student");
        }
    }

    /**
     * Downloads a short-lived ZIP package after session identity and owner checks.
     */
    @GetMapping("/api/teaching/handouts/batch/zip/{batchId}/download")
    public ResponseEntity<byte[]> downloadBatchZip(
            @PathVariable String batchId,
            HttpServletRequest httpRequest) {
        RequestSubject subject = subjectResolver.resolve(httpRequest);
        if (batchExportService.isExpired(batchId)) {
            throw new ResponseStatusException(HttpStatus.GONE, "Temporary batch ZIP expired");
        }
        TeachingHandoutBatchExportRecord record = batchExportService.findDownload(batchId, requestContext(subject))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Batch ZIP not found"));
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/zip"))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(record.response().batchId() + ".zip", StandardCharsets.UTF_8)
                        .build()
                        .toString())
                .body(record.zipBytes());
    }

    /**
     * Records human feedback after session identity and task ownership checks.
     */
    @PostMapping("/api/teaching/tasks/{taskId}/feedback")
    public TeachingHumanFeedbackResponse submitHumanFeedback(
            @PathVariable String taskId,
            @Valid @RequestBody TeachingHumanFeedbackRequest request,
            HttpServletRequest httpRequest) {
        RequestSubject subject = subjectResolver.resolve(httpRequest);
        TeachingRequestContext context = requestContext(subject);
        workflowService.get(taskId, context)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Teaching task not found"));
        return feedbackService.submit(taskId, context, request);
    }

    /**
     * Lists human feedback records attached to an owned teaching task.
     */
    @GetMapping("/api/teaching/tasks/{taskId}/feedback")
    public List<TeachingHumanFeedbackResponse> listHumanFeedback(
            @PathVariable String taskId,
            HttpServletRequest httpRequest) {
        TeachingRequestContext context = requestContext(subjectResolver.resolve(httpRequest));
        workflowService.get(taskId, context)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Teaching task not found"));
        return feedbackService.list(taskId, context);
    }

    private static TeachingRequestContext requestContext(RequestSubject subject) {
        RequestSubject normalized = subject.normalize();
        return new TeachingRequestContext(
                normalized.tenantId(),
                normalized.subjectType(),
                normalized.subjectId(),
                normalized.deviceId());
    }

    /** Applies the same answer-redaction contract to every generic task response, not only the practice endpoint. */
    private static TeachingTaskResponse visibleToSubject(TeachingTaskResponse task, RequestSubject subject) {
        if (task == null || subject == null) {
            return task;
        }
        return "student".equals(subject.normalize().subjectType()) ? task.studentSafe() : task;
    }

    /**
     * Normalizes handout version path variables to the two backend-supported variants.
     */
    private static String normalizeHandoutVersion(String version) {
        if ("teacher".equalsIgnoreCase(version)) {
            return "teacher";
        }
        if ("student".equalsIgnoreCase(version)) {
            return "student";
        }
        if ("lecture".equalsIgnoreCase(version)) {
            return "lecture";
        }
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported handout version");
    }

    /**
     * Returns the safest default handout version for the backend-resolved role.
     */
    private static String defaultHandoutVersion(RequestSubject subject) {
        return canUseTeacherHandout(subject) ? "teacher" : "student";
    }

    /**
     * Blocks student sessions from teacher-only handout sources even if the frontend requests that route.
     */
    private static void requireHandoutVersionAllowed(String version, RequestSubject subject) {
        if (("teacher".equals(version) || "lecture".equals(version)) && !canUseTeacherHandout(subject)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Teacher handout version requires teacher role");
        }
    }

    /**
     * Teacher/admin roles may view detailed solutions; students are limited to blank/student handouts.
     */
    private static boolean canUseTeacherHandout(RequestSubject subject) {
        RequestSubject normalized = subject.normalize();
        return "teacher".equals(normalized.subjectType()) || "admin".equals(normalized.subjectType());
    }

    /**
     * Reads a non-authoritative request header used for capability token verification.
     */
    private static String headerOrNull(HttpServletRequest request, String name) {
        if (request == null) {
            return null;
        }
        String value = request.getHeader(name);
        return value == null || value.isBlank() ? null : value.strip();
    }

    /**
     * Frontend template cards should never receive local filesystem paths. Real previews go through
     * protected PNG/PDF endpoints that resolve templateCode on the backend.
     */
    private static TeachingHandoutTemplateResponse sanitizeTemplateForFrontend(TeachingHandoutTemplateResponse template) {
        return new TeachingHandoutTemplateResponse(
                template.templateCode(),
                template.displayName(),
                template.sourceType(),
                template.audience(),
                template.description(),
                template.category(),
                template.visualStyle(),
                template.difficultyBands(),
                template.tags(),
                template.referenceTitle(),
                null,
                template.referencePreview(),
                template.blankSpaceEm(),
                template.questionGapEm());
    }
}
