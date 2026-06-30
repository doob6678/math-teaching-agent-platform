package com.doob.mathagent.teacher.controller;

import com.doob.mathagent.infrastructure.security.RequestSubject;
import com.doob.mathagent.infrastructure.security.RequestSubjectResolver;
import com.doob.mathagent.teacher.dto.TeacherResourceRegistrationRequest;
import com.doob.mathagent.teacher.service.TeacherResourceCapabilityVerifier;
import com.doob.mathagent.teacher.service.TeacherResourceBlockSearchService;
import com.doob.mathagent.teacher.service.TeacherResourceRegistrationCommand;
import com.doob.mathagent.teacher.service.TeacherResourceService;
import com.doob.mathagent.teacher.service.TeacherSourceSyncCheckpointQueryService;
import com.doob.mathagent.teacher.service.TeacherSourceSyncExecutionService;
import com.doob.mathagent.teacher.service.TeacherSourceSyncJobService;
import com.doob.mathagent.teacher.vo.TeacherResourceBlockSearchResponse;
import com.doob.mathagent.teacher.vo.TeacherResourceDocumentResponse;
import com.doob.mathagent.teacher.vo.TeacherSourceSyncCheckpointResponse;
import com.doob.mathagent.teacher.vo.TeacherSourceSyncJobResponse;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
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
    private static final String SYNC_EXECUTE_ACTION = "teacher-resource:sync-execute";
    private static final String SYNC_RESUME_ACTION = "teacher-resource:sync-resume";
    private static final String RESOURCES_PATH = "/api/teacher/resources";

    private final TeacherResourceService teacherResourceService;
    private final TeacherSourceSyncJobService syncJobService;
    private final TeacherSourceSyncExecutionService syncExecutionService;
    private final TeacherResourceBlockSearchService blockSearchService;
    private final TeacherSourceSyncCheckpointQueryService checkpointQueryService;
    private final RequestSubjectResolver subjectResolver;
    private final TeacherResourceCapabilityVerifier capabilityVerifier;

    /**
     * Creates a teacher resource controller.
     *
     * @param teacherResourceService teacher resource service
     * @param subjectResolver backend subject resolver
     */
    @Autowired
    public TeacherResourceController(
            TeacherResourceService teacherResourceService,
            TeacherSourceSyncJobService syncJobService,
            TeacherSourceSyncExecutionService syncExecutionService,
            TeacherResourceBlockSearchService blockSearchService,
            TeacherSourceSyncCheckpointQueryService checkpointQueryService,
            RequestSubjectResolver subjectResolver,
            TeacherResourceCapabilityVerifier capabilityVerifier) {
        this.teacherResourceService = teacherResourceService;
        this.syncJobService = syncJobService;
        this.syncExecutionService = syncExecutionService;
        this.blockSearchService = blockSearchService;
        this.checkpointQueryService = checkpointQueryService;
        this.subjectResolver = subjectResolver;
        this.capabilityVerifier = capabilityVerifier;
    }

    /**
     * Creates a controller without block search for older direct unit tests.
     *
     * @param teacherResourceService teacher resource service
     * @param syncJobService sync job service
     * @param syncExecutionService sync execution service
     * @param subjectResolver backend subject resolver
     * @param capabilityVerifier capability verifier
     */
    public TeacherResourceController(
            TeacherResourceService teacherResourceService,
            TeacherSourceSyncJobService syncJobService,
            TeacherSourceSyncExecutionService syncExecutionService,
            RequestSubjectResolver subjectResolver,
            TeacherResourceCapabilityVerifier capabilityVerifier) {
        this(
                teacherResourceService,
                syncJobService,
                syncExecutionService,
                null,
                null,
                subjectResolver,
                capabilityVerifier);
    }

    /**
     * Creates a controller with block search but without checkpoint query for focused tests.
     */
    public TeacherResourceController(
            TeacherResourceService teacherResourceService,
            TeacherSourceSyncJobService syncJobService,
            TeacherSourceSyncExecutionService syncExecutionService,
            TeacherResourceBlockSearchService blockSearchService,
            RequestSubjectResolver subjectResolver,
            TeacherResourceCapabilityVerifier capabilityVerifier) {
        this(
                teacherResourceService,
                syncJobService,
                syncExecutionService,
                blockSearchService,
                null,
                subjectResolver,
                capabilityVerifier);
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
     * Searches parsed blocks from teacher-managed resources visible to the backend subject.
     *
     * @param query search query
     * @param limit maximum hit count
     * @param httpRequest HTTP request containing backend session
     * @return visible parsed block search hits
     */
    @GetMapping("/api/teacher/resources/search")
    public TeacherResourceBlockSearchResponse searchBlocks(
            @RequestParam String query,
            @RequestParam(defaultValue = "10") int limit,
            HttpServletRequest httpRequest) {
        if (blockSearchService == null) {
            throw new ResponseStatusException(HttpStatus.NOT_IMPLEMENTED, "Teacher resource block search is not configured");
        }
        RequestSubject subject = subjectResolver.resolve(httpRequest).normalize();
        try {
            return blockSearchService.search(
                    subject.tenantId(),
                    subject.subjectType(),
                    subject.subjectId(),
                    query,
                    limit);
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, exception.getMessage(), exception);
        }
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
     * Executes a queued source synchronization job after a separate high-value capability check.
     *
     * @param documentId resource document id
     * @param jobId sync job id
     * @param httpRequest HTTP request containing capability headers
     * @return completed, running, or failed sync job state
     */
    @PostMapping("/api/teacher/resources/{documentId}/sync-jobs/{jobId}/execute")
    public TeacherSourceSyncJobResponse executeSyncJob(
            @PathVariable String documentId,
            @PathVariable String jobId,
            HttpServletRequest httpRequest) {
        RequestSubject subject = subjectResolver.resolve(httpRequest).normalize();
        String path = RESOURCES_PATH + "/" + documentId + "/sync-jobs/" + jobId + "/execute";
        if (!capabilityVerifier.verify(
                headerOrNull(httpRequest, "X-Capability-Token"),
                SYNC_EXECUTE_ACTION,
                path,
                headerOrNull(httpRequest, "X-Request-Hash"),
                subject)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Capability token required for teacher resource sync execution");
        }
        return syncExecutionService.execute(
                subject.tenantId(),
                subject.subjectType(),
                subject.subjectId(),
                documentId,
                jobId);
    }

    /**
     * Resumes a paused source synchronization job when a durable checkpoint is available.
     *
     * @param documentId resource document id
     * @param jobId sync job id
     * @param httpRequest HTTP request containing capability headers
     * @return resumed sync job state
     */
    @PostMapping("/api/teacher/resources/{documentId}/sync-jobs/{jobId}/resume")
    public TeacherSourceSyncJobResponse resumeSyncJob(
            @PathVariable String documentId,
            @PathVariable String jobId,
            HttpServletRequest httpRequest) {
        RequestSubject subject = subjectResolver.resolve(httpRequest).normalize();
        String path = RESOURCES_PATH + "/" + documentId + "/sync-jobs/" + jobId + "/resume";
        if (!capabilityVerifier.verify(
                headerOrNull(httpRequest, "X-Capability-Token"),
                SYNC_RESUME_ACTION,
                path,
                headerOrNull(httpRequest, "X-Request-Hash"),
                subject)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Capability token required for teacher resource sync resume");
        }
        return syncExecutionService.resume(
                subject.tenantId(),
                subject.subjectType(),
                subject.subjectId(),
                documentId,
                jobId);
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
     * Returns the latest durable checkpoint for a visible sync job.
     *
     * @param documentId resource document id
     * @param jobId sync job id
     * @param httpRequest HTTP request containing backend session
     * @return checkpoint when the job has started or paused after a partial download
     */
    @GetMapping("/api/teacher/resources/{documentId}/sync-jobs/{jobId}/checkpoint")
    public Optional<TeacherSourceSyncCheckpointResponse> getSyncCheckpoint(
            @PathVariable String documentId,
            @PathVariable String jobId,
            HttpServletRequest httpRequest) {
        if (checkpointQueryService == null) {
            throw new ResponseStatusException(HttpStatus.NOT_IMPLEMENTED, "Teacher source sync checkpoint query is not configured");
        }
        RequestSubject subject = subjectResolver.resolve(httpRequest).normalize();
        try {
            return checkpointQueryService.findCheckpoint(
                    subject.tenantId(),
                    subject.subjectType(),
                    subject.subjectId(),
                    documentId,
                    jobId);
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, exception.getMessage(), exception);
        }
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
