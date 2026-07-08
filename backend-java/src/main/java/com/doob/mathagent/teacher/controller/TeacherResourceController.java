package com.doob.mathagent.teacher.controller;

import com.doob.mathagent.infrastructure.security.RequestSubject;
import com.doob.mathagent.infrastructure.security.RequestSubjectResolver;
import com.doob.mathagent.teacher.dto.TeacherResourceRegistrationRequest;
import com.doob.mathagent.teacher.service.TeacherResourceCapabilityVerifier;
import com.doob.mathagent.teacher.service.TeacherResourceBlockSearchAuditEvent;
import com.doob.mathagent.teacher.service.TeacherResourceBlockSearchAuditLookup;
import com.doob.mathagent.teacher.service.TeacherResourceBlockSearchService;
import com.doob.mathagent.teacher.service.TeacherResourceRegistrationCommand;
import com.doob.mathagent.teacher.service.TeacherResourceSearchFilter;
import com.doob.mathagent.teacher.service.TeacherResourceService;
import com.doob.mathagent.teacher.service.TeacherResourceAssetService;
import com.doob.mathagent.teacher.service.TeacherDocumentBlockStore;
import com.doob.mathagent.teacher.service.TeacherSourceSyncCheckpointQueryService;
import com.doob.mathagent.teacher.service.TeacherSourceSyncExecutionService;
import com.doob.mathagent.teacher.service.TeacherSourceSyncJobService;
import com.doob.mathagent.teacher.vo.TeacherDocumentBlockResponse;
import com.doob.mathagent.teacher.vo.TeacherResourceBlockSearchResponse;
import com.doob.mathagent.teacher.vo.TeacherResourceDocumentResponse;
import com.doob.mathagent.teacher.vo.TeacherSourceSyncCheckpointResponse;
import com.doob.mathagent.teacher.vo.TeacherSourceSyncJobResponse;
import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
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
    private final TeacherResourceBlockSearchAuditLookup blockSearchAuditLookup;
    private final TeacherSourceSyncCheckpointQueryService checkpointQueryService;
    private final TeacherDocumentBlockStore blockStore;
    private final TeacherResourceAssetService assetService;
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
            TeacherSourceSyncExecutionService syncExecutionService,
            TeacherResourceBlockSearchService blockSearchService,
            TeacherResourceBlockSearchAuditLookup blockSearchAuditLookup,
            TeacherSourceSyncCheckpointQueryService checkpointQueryService,
            TeacherDocumentBlockStore blockStore,
            RequestSubjectResolver subjectResolver,
            TeacherResourceCapabilityVerifier capabilityVerifier) {
        this(
                teacherResourceService,
                syncJobService,
                syncExecutionService,
                blockSearchService,
                blockSearchAuditLookup,
                checkpointQueryService,
                blockStore,
                TeacherResourceAssetService.disabled(),
                subjectResolver,
                capabilityVerifier);
    }

    @Autowired
    public TeacherResourceController(
            TeacherResourceService teacherResourceService,
            TeacherSourceSyncJobService syncJobService,
            TeacherSourceSyncExecutionService syncExecutionService,
            TeacherResourceBlockSearchService blockSearchService,
            TeacherResourceBlockSearchAuditLookup blockSearchAuditLookup,
            TeacherSourceSyncCheckpointQueryService checkpointQueryService,
            TeacherDocumentBlockStore blockStore,
            TeacherResourceAssetService assetService,
            RequestSubjectResolver subjectResolver,
            TeacherResourceCapabilityVerifier capabilityVerifier) {
        this.teacherResourceService = Objects.requireNonNull(teacherResourceService, "teacherResourceService");
        this.syncJobService = Objects.requireNonNull(syncJobService, "syncJobService");
        this.syncExecutionService = Objects.requireNonNull(syncExecutionService, "syncExecutionService");
        this.blockSearchService = Objects.requireNonNull(blockSearchService, "blockSearchService");
        this.blockSearchAuditLookup = Objects.requireNonNull(blockSearchAuditLookup, "blockSearchAuditLookup");
        this.checkpointQueryService = Objects.requireNonNull(checkpointQueryService, "checkpointQueryService");
        this.blockStore = Objects.requireNonNull(blockStore, "blockStore");
        this.assetService = Objects.requireNonNull(assetService, "assetService");
        this.subjectResolver = Objects.requireNonNull(subjectResolver, "subjectResolver");
        this.capabilityVerifier = Objects.requireNonNull(capabilityVerifier, "capabilityVerifier");
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
        try {
            return teacherResourceService.register(enrich(request, subject));
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage(), exception);
        }
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
            @RequestParam(value = "permissionScope", required = false) List<String> permissionScopes,
            @RequestParam(value = "documentId", required = false) List<String> documentIds,
            @RequestParam(value = "sourceType", required = false) List<String> sourceTypes,
            @RequestParam(value = "library", required = false) List<String> libraries,
            @RequestParam(value = "tag", required = false) List<String> tags,
            @RequestParam(value = "strategy", required = false) String strategy,
            HttpServletRequest httpRequest) {
        RequestSubject subject = subjectResolver.resolve(httpRequest).normalize();
        try {
            return blockSearchService.search(
                    subject.tenantId(),
                    subject.subjectType(),
                    subject.subjectId(),
                    query,
                    limit,
                    "/api/teacher/resources/search",
                    TeacherResourceSearchFilter.of(
                            permissionScopes,
                            documentIds,
                            mergeLibrarySelectors(sourceTypes, libraries),
                            tags),
                    strategy);
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, exception.getMessage(), exception);
        }
    }

    /**
     * Backward-compatible test helper for callers that do not pass metadata filters.
     */
    public TeacherResourceBlockSearchResponse searchBlocks(
            String query,
            int limit,
            HttpServletRequest httpRequest) {
        return searchBlocks(query, limit, null, null, null, null, null, null, httpRequest);
    }

    /**
     * Accepts both legacy sourceType selectors and the clearer library alias. Both ultimately route into the same
     * logical-library resolver so existing clients keep working while AI callers can express intent with a less
     * implementation-specific parameter name.
     */
    private static List<String> mergeLibrarySelectors(List<String> sourceTypes, List<String> libraries) {
        LinkedHashSet<String> selectors = new LinkedHashSet<>();
        if (sourceTypes != null) {
            for (String sourceType : sourceTypes) {
                if (sourceType != null && !sourceType.isBlank()) {
                    selectors.add(sourceType.strip());
                }
            }
        }
        if (libraries != null) {
            for (String library : libraries) {
                if (library != null && !library.isBlank()) {
                    selectors.add(library.strip());
                }
            }
        }
        return List.copyOf(selectors);
    }

    /**
     * Lists parsed blocks for one visible teacher resource.
     *
     * This read-only endpoint exists so evaluation tooling can build strict RAG recall sets from real parsed blocks
     * instead of inventing ground-truth ids. It must keep the same teacher/admin visibility boundary as resource list
     * and search; do not replace it with a direct file or database shortcut in benchmark code.
     *
     * @param documentId resource document id
     * @param httpRequest HTTP request containing backend session
     * @return active parsed document blocks for the visible resource
     */
    @GetMapping("/api/teacher/resources/{documentId}/blocks")
    public List<TeacherDocumentBlockResponse> listBlocks(
            @PathVariable String documentId,
            HttpServletRequest httpRequest) {
        RequestSubject subject = subjectResolver.resolve(httpRequest).normalize();
        boolean visible = teacherResourceService
                .list(subject.tenantId(), subject.subjectType(), subject.subjectId())
                .stream()
                .anyMatch(resource -> resource.documentId().equals(documentId));
        if (!visible) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Teacher resource not found");
        }
        return blockStore.listByDocument(subject.tenantId(), documentId);
    }

    /**
     * Streams an extracted PDF/DOCX/Feishu image through backend authorization.
     *
     * The response deliberately exposes only an opaque asset id and a file stream. Do not replace this with static
     * file serving: storage paths, Feishu image tokens, and object-store keys are not client-facing identifiers.
     */
    @GetMapping("/api/teacher/resources/assets/{assetId}")
    public ResponseEntity<Resource> readAsset(
            @PathVariable String assetId,
            HttpServletRequest httpRequest) {
        RequestSubject subject = subjectResolver.resolve(httpRequest).normalize();
        try {
            TeacherResourceAssetService.VisibleAsset asset = assetService.openVisibleAsset(assetId, subject);
            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType(asset.mimeType()))
                    .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline()
                            .filename(asset.fileName())
                            .build()
                            .toString())
                    .body(asset.resource());
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Teacher resource asset not found", exception);
        }
    }

    /**
     * Returns a recent teacher resource block search audit event visible to the current teacher/admin.
     *
     * @param queryId server-generated search query id
     * @param httpRequest HTTP request containing backend session
     * @return retained audit event
     */
    @GetMapping("/api/teacher/resources/search/audit/{queryId}")
    public TeacherResourceBlockSearchAuditEvent searchAudit(
            @PathVariable String queryId,
            HttpServletRequest httpRequest) {
        RequestSubject subject = subjectResolver.resolve(httpRequest).normalize();
        requireTeacherOrAdmin(subject);
        TeacherResourceBlockSearchAuditEvent event = blockSearchAuditLookup.findByQueryId(queryId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Teacher resource search audit not found"));
        if (!subject.tenantId().equals(event.tenantId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Teacher resource search audit not found");
        }
        if ("teacher".equals(subject.subjectType()) && !subject.subjectId().equals(event.subjectId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Teacher resource search audit not found");
        }
        return event;
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

    /**
     * Ensures direct controller calls cannot bypass teacher/admin audit visibility rules.
     */
    private static void requireTeacherOrAdmin(RequestSubject subject) {
        String role = subject.subjectType();
        if (!"teacher".equals(role) && !"admin".equals(role)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Teacher resource audit requires teacher or admin role");
        }
    }
}
