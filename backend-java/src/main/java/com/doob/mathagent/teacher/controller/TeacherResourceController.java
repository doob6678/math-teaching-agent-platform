package com.doob.mathagent.teacher.controller;

import com.doob.mathagent.infrastructure.security.RequestSubject;
import com.doob.mathagent.infrastructure.security.RequestSubjectResolver;
import com.doob.mathagent.teacher.dto.TeacherResourceRegistrationRequest;
import com.doob.mathagent.teacher.service.TeacherResourceCapabilityVerifier;
import com.doob.mathagent.teacher.service.TeacherResourceRegistrationCommand;
import com.doob.mathagent.teacher.service.TeacherResourceService;
import com.doob.mathagent.teacher.service.TeacherSourceSyncJobService;
import com.doob.mathagent.teacher.vo.TeacherResourceDocumentResponse;
import com.doob.mathagent.teacher.vo.TeacherSourceSyncJobResponse;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Teacher resource management API for Feishu, local folders, and teacher-owned question banks.
 */
@RestController
public class TeacherResourceController {

    private static final String REGISTER_ACTION = "teacher-resource:register";
    private static final String ARCHIVE_ACTION = "teacher-resource:archive";
    private static final String SYNC_ACTION = "teacher-resource:sync";
    private static final String RESOURCES_PATH = "/api/teacher/resources";

    private final TeacherResourceService teacherResourceService;
    private final TeacherSourceSyncJobService syncJobService;
    private final RequestSubjectResolver subjectResolver;
    private final TeacherResourceCapabilityVerifier capabilityVerifier;

    /**
     * Creates a teacher resource controller.
     *
     * @param teacherResourceService teacher resource service
     * @param subjectResolver backend subject resolver
     */
    public TeacherResourceController(
            TeacherResourceService teacherResourceService,
            TeacherSourceSyncJobService syncJobService,
            RequestSubjectResolver subjectResolver,
            TeacherResourceCapabilityVerifier capabilityVerifier) {
        this.teacherResourceService = teacherResourceService;
        this.syncJobService = syncJobService;
        this.subjectResolver = subjectResolver;
        this.capabilityVerifier = capabilityVerifier;
    }

    /**
     * Registers a teacher/admin resource source and returns preview/index status.
     *
     * @param request registration request body
     * @param httpRequest HTTP request containing tenant and subject headers
     * @return registered resource document
     */
    @PostMapping("/api/teacher/resources")
    public TeacherResourceDocumentResponse register(
            @RequestBody TeacherResourceRegistrationRequest request,
            HttpServletRequest httpRequest) {
        RequestSubject subject = subjectResolver.resolve(httpRequest);
        if (!capabilityVerifier.verify(
                headerOrNull(httpRequest, "X-Capability-Token"),
                REGISTER_ACTION,
                RESOURCES_PATH,
                headerOrNull(httpRequest, "X-Request-Hash"),
                subject)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Capability token required for teacher resource register");
        }
        return teacherResourceService.register(enrich(request, subject));
    }

    /**
     * Lists active resource sources visible to the current teacher/admin.
     *
     * @param httpRequest HTTP request containing tenant and subject headers
     * @return visible resource documents
     */
    @GetMapping("/api/teacher/resources")
    public List<TeacherResourceDocumentResponse> list(HttpServletRequest httpRequest) {
        RequestSubject subject = subjectResolver.resolve(httpRequest).normalize();
        return teacherResourceService.list(subject.tenantId(), subject.subjectType(), subject.subjectId());
    }

    /**
     * Archives a resource source instead of hard-deleting it so old RAG citations remain traceable.
     *
     * @param documentId resource document id
     * @param httpRequest HTTP request containing tenant and subject headers
     * @return archived resource document
     */
    @DeleteMapping("/api/teacher/resources/{documentId}")
    public TeacherResourceDocumentResponse archive(
            @PathVariable String documentId,
            HttpServletRequest httpRequest) {
        RequestSubject subject = subjectResolver.resolve(httpRequest).normalize();
        String path = RESOURCES_PATH + "/" + documentId;
        if (!capabilityVerifier.verify(
                headerOrNull(httpRequest, "X-Capability-Token"),
                ARCHIVE_ACTION,
                path,
                headerOrNull(httpRequest, "X-Request-Hash"),
                subject)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Capability token required for teacher resource archive");
        }
        return teacherResourceService.archive(
                subject.tenantId(),
                subject.subjectType(),
                subject.subjectId(),
                documentId);
    }

    /**
     * Queues a source synchronization job. The worker may later download, parse, embed and reindex the source.
     *
     * @param documentId resource document id
     * @param httpRequest HTTP request containing capability headers
     * @return queued sync job
     */
    @PostMapping("/api/teacher/resources/{documentId}/sync-jobs")
    public TeacherSourceSyncJobResponse createSyncJob(
            @PathVariable String documentId,
            HttpServletRequest httpRequest) {
        RequestSubject subject = subjectResolver.resolve(httpRequest).normalize();
        String path = RESOURCES_PATH + "/" + documentId + "/sync-jobs";
        if (!capabilityVerifier.verify(
                headerOrNull(httpRequest, "X-Capability-Token"),
                SYNC_ACTION,
                path,
                headerOrNull(httpRequest, "X-Request-Hash"),
                subject)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Capability token required for teacher resource sync");
        }
        return syncJobService.createSyncJob(
                subject.tenantId(),
                subject.subjectType(),
                subject.subjectId(),
                documentId);
    }

    /**
     * Lists synchronization jobs for a visible resource.
     *
     * @param documentId resource document id
     * @param httpRequest HTTP request containing backend session
     * @return sync jobs
     */
    @GetMapping("/api/teacher/resources/{documentId}/sync-jobs")
    public List<TeacherSourceSyncJobResponse> listSyncJobs(
            @PathVariable String documentId,
            HttpServletRequest httpRequest) {
        RequestSubject subject = subjectResolver.resolve(httpRequest).normalize();
        return syncJobService.listSyncJobs(
                subject.tenantId(),
                subject.subjectType(),
                subject.subjectId(),
                documentId);
    }

    /**
     * Merges request body fields with backend-resolved subject identity.
     *
     * @param request registration body
     * @param subject backend resolved subject
     * @return server-side registration command
     */
    private static TeacherResourceRegistrationCommand enrich(
            TeacherResourceRegistrationRequest request,
            RequestSubject subject) {
        RequestSubject normalized = subject.normalize();
        return TeacherResourceRegistrationCommand.fromRequest(
                normalized.tenantId(),
                normalized.subjectType(),
                normalized.subjectId(),
                request);
    }

    /**
     * Reads a non-authoritative request header used for capability token verification.
     *
     * @param request HTTP request
     * @param name header name
     * @return stripped header value or null
     */
    private static String headerOrNull(HttpServletRequest request, String name) {
        if (request == null) {
            return null;
        }
        String value = request.getHeader(name);
        return value == null || value.isBlank() ? null : value.strip();
    }
}
