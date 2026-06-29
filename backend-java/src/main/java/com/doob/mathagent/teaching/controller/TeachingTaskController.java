package com.doob.mathagent.teaching.controller;

import com.doob.mathagent.infrastructure.security.RequestSubject;
import com.doob.mathagent.infrastructure.security.RequestSubjectResolver;
import com.doob.mathagent.teaching.TeachingRequestContext;
import com.doob.mathagent.teaching.dto.TeachingHandoutBatchExportRequest;
import com.doob.mathagent.teaching.dto.TeachingTaskRequest;
import com.doob.mathagent.teaching.service.TeachingHandoutBatchExportRecord;
import com.doob.mathagent.teaching.service.TeachingHandoutBatchExportService;
import com.doob.mathagent.teaching.service.TeachingCapabilityVerifier;
import com.doob.mathagent.teaching.service.TeachingHandoutPdfExportService;
import com.doob.mathagent.teaching.service.TeachingWorkflowService;
import com.doob.mathagent.teaching.vo.TeachingHandoutBatchExportResponse;
import com.doob.mathagent.teaching.vo.TeachingTaskResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * 教学任务接口：前端提交任务后可通过 taskId 持续查询结果，支持页面离开后的恢复。
 */
@RestController
public class TeachingTaskController {

    private static final String TEACHING_SUBMIT_ACTION = "teaching:submit";
    private static final String TEACHING_HANDOUT_LATEX_EXPORT_ACTION = "teaching-handout:export-latex";
    private static final String TEACHING_HANDOUT_LATEX_PREVIEW_ACTION = "teaching-handout:preview-latex";
    private static final String TEACHING_HANDOUT_PDF_EXPORT_ACTION = "teaching-handout:export-pdf";
    private static final String TEACHING_HANDOUT_BATCH_ZIP_EXPORT_ACTION = "teaching-handout:batch-export-zip";
    private static final String TEACHING_HANDOUT_BATCH_ZIP_DOWNLOAD_ACTION = "teaching-handout:batch-download-zip";
    private static final String TEACHING_TASKS_PATH = "/api/teaching/tasks";
    private static final String TEACHING_BATCH_ZIP_PATH = "/api/teaching/handouts/batch/zip";

    private final TeachingWorkflowService workflowService;
    private final RequestSubjectResolver subjectResolver;
    private final TeachingCapabilityVerifier capabilityVerifier;
    private final TeachingHandoutPdfExportService pdfExportService;
    private final TeachingHandoutBatchExportService batchExportService;

    /**
     * 注入教学编排服务。
     */
    public TeachingTaskController(
            TeachingWorkflowService workflowService,
            RequestSubjectResolver subjectResolver,
            TeachingCapabilityVerifier capabilityVerifier,
            TeachingHandoutPdfExportService pdfExportService,
            TeachingHandoutBatchExportService batchExportService) {
        this.workflowService = workflowService;
        this.subjectResolver = subjectResolver;
        this.capabilityVerifier = capabilityVerifier;
        this.pdfExportService = pdfExportService;
        this.batchExportService = batchExportService;
    }

    /**
     * 提交教学任务；当前阶段同步完成，后续可切换为异步队列。
     */
    @PostMapping("/api/teaching/tasks")
    public TeachingTaskResponse submit(
            @Valid @RequestBody TeachingTaskRequest request,
            HttpServletRequest httpRequest) {
        RequestSubject subject = subjectResolver.resolve(httpRequest);
        if (!capabilityVerifier.verify(
                headerOrNull(httpRequest, "X-Capability-Token"),
                TEACHING_SUBMIT_ACTION,
                TEACHING_TASKS_PATH,
                headerOrNull(httpRequest, "X-Request-Hash"),
                subject)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Capability token required for teaching submit");
        }
        return workflowService.submit(request, requestContext(subject));
    }

    /**
     * 按 taskId 查询任务结果；只允许任务归属主体读取。
     */
    @GetMapping("/api/teaching/tasks/{taskId}")
    public TeachingTaskResponse get(
            @PathVariable String taskId,
            HttpServletRequest httpRequest) {
        return workflowService.get(taskId, requestContext(subjectResolver.resolve(httpRequest)))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Teaching task not found"));
    }

    /**
     * 从后端会话身份读取租户、主体和设备信息，用于任务隔离和审计。
     */
    /**
     * Exports the LaTeX handout for an owned teaching task after consuming a one-time capability token.
     */
    @GetMapping("/api/teaching/tasks/{taskId}/handout/latex")
    public ResponseEntity<String> exportLatex(
            @PathVariable String taskId,
            HttpServletRequest httpRequest) {
        RequestSubject subject = subjectResolver.resolve(httpRequest);
        String path = TEACHING_TASKS_PATH + "/" + taskId + "/handout/latex";
        if (!capabilityVerifier.verify(
                headerOrNull(httpRequest, "X-Capability-Token"),
                TEACHING_HANDOUT_LATEX_EXPORT_ACTION,
                path,
                headerOrNull(httpRequest, "X-Request-Hash"),
                subject)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Capability token required for LaTeX export");
        }
        TeachingTaskResponse task = workflowService.get(taskId, requestContext(subject))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Teaching task not found"));
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/x-tex;charset=UTF-8"))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(task.taskId() + ".tex", StandardCharsets.UTF_8)
                        .build()
                        .toString())
                .body(task.handoutLatex());
    }

    /**
     * Exports a specific LaTeX handout version after consuming a version-bound capability token.
     */
    @GetMapping("/api/teaching/tasks/{taskId}/handout/{version}/latex")
    public ResponseEntity<String> exportLatexVersion(
            @PathVariable String taskId,
            @PathVariable String version,
            HttpServletRequest httpRequest) {
        String normalizedVersion = normalizeHandoutVersion(version);
        RequestSubject subject = subjectResolver.resolve(httpRequest);
        String path = TEACHING_TASKS_PATH + "/" + taskId + "/handout/" + normalizedVersion + "/latex";
        if (!capabilityVerifier.verify(
                headerOrNull(httpRequest, "X-Capability-Token"),
                TEACHING_HANDOUT_LATEX_EXPORT_ACTION,
                path,
                headerOrNull(httpRequest, "X-Request-Hash"),
                subject)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Capability token required for LaTeX export");
        }
        TeachingTaskResponse task = workflowService.get(taskId, requestContext(subject))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Teaching task not found"));
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/x-tex;charset=UTF-8"))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(task.taskId() + "-" + normalizedVersion + ".tex", StandardCharsets.UTF_8)
                        .build()
                        .toString())
                .body(task.handoutLatexFor(normalizedVersion));
    }

    /**
     * Previews the LaTeX handout inline after consuming a one-time capability token.
     */
    @GetMapping("/api/teaching/tasks/{taskId}/handout/latex/preview")
    public ResponseEntity<String> previewLatex(
            @PathVariable String taskId,
            HttpServletRequest httpRequest) {
        RequestSubject subject = subjectResolver.resolve(httpRequest);
        String path = TEACHING_TASKS_PATH + "/" + taskId + "/handout/latex/preview";
        if (!capabilityVerifier.verify(
                headerOrNull(httpRequest, "X-Capability-Token"),
                TEACHING_HANDOUT_LATEX_PREVIEW_ACTION,
                path,
                headerOrNull(httpRequest, "X-Request-Hash"),
                subject)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Capability token required for LaTeX preview");
        }
        TeachingTaskResponse task = workflowService.get(taskId, requestContext(subject))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Teaching task not found"));
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/x-tex;charset=UTF-8"))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline()
                        .filename(task.taskId() + ".tex", StandardCharsets.UTF_8)
                        .build()
                        .toString())
                .body(task.handoutLatex());
    }

    /**
     * Previews a specific handout version inline after consuming a version-bound capability token.
     */
    @GetMapping("/api/teaching/tasks/{taskId}/handout/{version}/latex/preview")
    public ResponseEntity<String> previewLatexVersion(
            @PathVariable String taskId,
            @PathVariable String version,
            HttpServletRequest httpRequest) {
        String normalizedVersion = normalizeHandoutVersion(version);
        RequestSubject subject = subjectResolver.resolve(httpRequest);
        String path = TEACHING_TASKS_PATH + "/" + taskId + "/handout/" + normalizedVersion + "/latex/preview";
        if (!capabilityVerifier.verify(
                headerOrNull(httpRequest, "X-Capability-Token"),
                TEACHING_HANDOUT_LATEX_PREVIEW_ACTION,
                path,
                headerOrNull(httpRequest, "X-Request-Hash"),
                subject)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Capability token required for LaTeX preview");
        }
        TeachingTaskResponse task = workflowService.get(taskId, requestContext(subject))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Teaching task not found"));
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/x-tex;charset=UTF-8"))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline()
                        .filename(task.taskId() + "-" + normalizedVersion + ".tex", StandardCharsets.UTF_8)
                        .build()
                        .toString())
                .body(task.handoutLatexFor(normalizedVersion));
    }

    /**
     * Exports the PDF handout for an owned teaching task after consuming a one-time capability token.
     */
    @GetMapping("/api/teaching/tasks/{taskId}/handout/pdf")
    public ResponseEntity<byte[]> exportPdf(
            @PathVariable String taskId,
            HttpServletRequest httpRequest) {
        RequestSubject subject = subjectResolver.resolve(httpRequest);
        String path = TEACHING_TASKS_PATH + "/" + taskId + "/handout/pdf";
        if (!capabilityVerifier.verify(
                headerOrNull(httpRequest, "X-Capability-Token"),
                TEACHING_HANDOUT_PDF_EXPORT_ACTION,
                path,
                headerOrNull(httpRequest, "X-Request-Hash"),
                subject)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Capability token required for PDF export");
        }
        TeachingTaskResponse task = workflowService.get(taskId, requestContext(subject))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Teaching task not found"));
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(task.taskId() + ".pdf", StandardCharsets.UTF_8)
                        .build()
                        .toString())
                .body(pdfExportService.render(task));
    }

    /**
     * Exports a specific PDF handout version after capability and owner checks.
     */
    @GetMapping("/api/teaching/tasks/{taskId}/handout/{version}/pdf")
    public ResponseEntity<byte[]> exportPdfVersion(
            @PathVariable String taskId,
            @PathVariable String version,
            HttpServletRequest httpRequest) {
        String normalizedVersion = normalizeHandoutVersion(version);
        RequestSubject subject = subjectResolver.resolve(httpRequest);
        String path = TEACHING_TASKS_PATH + "/" + taskId + "/handout/" + normalizedVersion + "/pdf";
        if (!capabilityVerifier.verify(
                headerOrNull(httpRequest, "X-Capability-Token"),
                TEACHING_HANDOUT_PDF_EXPORT_ACTION,
                path,
                headerOrNull(httpRequest, "X-Request-Hash"),
                subject)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Capability token required for PDF export");
        }
        TeachingTaskResponse task = workflowService.get(taskId, requestContext(subject))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Teaching task not found"));
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(task.taskId() + "-" + normalizedVersion + ".pdf", StandardCharsets.UTF_8)
                        .build()
                        .toString())
                .body(pdfExportService.render(task, normalizedVersion));
    }

    /**
     * Creates a short-lived ZIP package for owned teaching handouts.
     */
    @PostMapping("/api/teaching/handouts/batch/zip")
    public TeachingHandoutBatchExportResponse createBatchZip(
            @Valid @RequestBody TeachingHandoutBatchExportRequest request,
            HttpServletRequest httpRequest) {
        RequestSubject subject = subjectResolver.resolve(httpRequest);
        if (!capabilityVerifier.verify(
                headerOrNull(httpRequest, "X-Capability-Token"),
                TEACHING_HANDOUT_BATCH_ZIP_EXPORT_ACTION,
                TEACHING_BATCH_ZIP_PATH,
                headerOrNull(httpRequest, "X-Request-Hash"),
                subject)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Capability token required for batch ZIP export");
        }
        TeachingRequestContext context = requestContext(subject);
        TeachingHandoutBatchExportRequest normalized = request.normalize();
        List<TeachingTaskResponse> tasks = normalized.taskIds().stream()
                .map(taskId -> workflowService.get(taskId, context)
                        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Teaching task not found")))
                .toList();
        try {
            return batchExportService.create(normalized, context, tasks);
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage(), exception);
        }
    }

    /**
     * Downloads a short-lived ZIP package after capability and owner checks.
     */
    @GetMapping("/api/teaching/handouts/batch/zip/{batchId}/download")
    public ResponseEntity<byte[]> downloadBatchZip(
            @PathVariable String batchId,
            HttpServletRequest httpRequest) {
        RequestSubject subject = subjectResolver.resolve(httpRequest);
        String path = TEACHING_BATCH_ZIP_PATH + "/" + batchId + "/download";
        if (!capabilityVerifier.verify(
                headerOrNull(httpRequest, "X-Capability-Token"),
                TEACHING_HANDOUT_BATCH_ZIP_DOWNLOAD_ACTION,
                path,
                headerOrNull(httpRequest, "X-Request-Hash"),
                subject)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Capability token required for batch ZIP download");
        }
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

    private static TeachingRequestContext requestContext(RequestSubject subject) {
        RequestSubject normalized = subject.normalize();
        return new TeachingRequestContext(
                normalized.tenantId(),
                normalized.subjectType(),
                normalized.subjectId(),
                normalized.deviceId());
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
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported handout version");
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
}
