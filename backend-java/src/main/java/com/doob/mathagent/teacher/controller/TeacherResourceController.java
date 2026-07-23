package com.doob.mathagent.teacher.controller;

import com.doob.mathagent.infrastructure.security.RequestSubject;
import com.doob.mathagent.infrastructure.security.RequestSubjectResolver;
import com.doob.mathagent.teacher.dto.TeacherResourceRegistrationRequest;
import com.doob.mathagent.teacher.support.TeacherResourceCapabilityVerifier;
import com.doob.mathagent.teacher.search.audit.TeacherResourceBlockSearchAuditEvent;
import com.doob.mathagent.teacher.search.audit.TeacherResourceBlockSearchAuditLookup;
import com.doob.mathagent.teacher.service.TeacherResourceBlockSearchService;
import com.doob.mathagent.teacher.support.TeacherResourceRegistrationCommand;
import com.doob.mathagent.teacher.support.TeacherResourceSourceTypePolicy;
import com.doob.mathagent.teacher.search.TeacherResourceSearchFilter;
import com.doob.mathagent.teacher.service.TeacherResourceService;
import com.doob.mathagent.teacher.service.TeacherResourceAssetService;
import com.doob.mathagent.teacher.support.TeacherResourceTitleResolver;
import com.doob.mathagent.teacher.block.TeacherDocumentBlockStore;
import com.doob.mathagent.teacher.service.TeacherResourceUploadService;
import com.doob.mathagent.teacher.sync.TeacherSourceSyncCheckpointQueryService;
import com.doob.mathagent.teacher.sync.mq.SynchronousTeacherSourceSyncCommandDispatcher;
import com.doob.mathagent.teacher.sync.mq.TeacherSourceSyncCommand;
import com.doob.mathagent.teacher.sync.mq.TeacherSourceSyncCommandDispatcher;
import com.doob.mathagent.teacher.service.TeacherSourceSyncExecutionService;
import com.doob.mathagent.teacher.service.TeacherSourceSyncJobService;
import com.doob.mathagent.teacher.block.TeacherDocumentBlockResponse;
import com.doob.mathagent.teacher.search.TeacherResourceBlockSearchResponse;
import com.doob.mathagent.teacher.document.TeacherResourceDocumentResponse;
import com.doob.mathagent.teacher.vo.TeacherSourceSyncCheckpointResponse;
import com.doob.mathagent.teacher.vo.TeacherSourceSyncJobResponse;
import com.doob.mathagent.vector.service.TeacherResourceImageClipSearchRequest;
import com.doob.mathagent.vector.service.TeacherResourceImageClipSearchResponse;
import com.doob.mathagent.vector.service.TeacherResourceImageClipService;
import jakarta.servlet.http.HttpServletRequest;
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
import org.springframework.web.multipart.MultipartFile;
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
    private static final String RESOURCES_UPLOAD_PATH = "/api/teacher/resources/upload";

    private final TeacherResourceService teacherResourceService;
    private final TeacherSourceSyncJobService syncJobService;
    private final TeacherSourceSyncExecutionService syncExecutionService;
    private final TeacherSourceSyncCommandDispatcher syncCommandDispatcher;
    private final TeacherResourceBlockSearchService blockSearchService;
    private final TeacherResourceBlockSearchAuditLookup blockSearchAuditLookup;
    private final TeacherSourceSyncCheckpointQueryService checkpointQueryService;
    private final TeacherDocumentBlockStore blockStore;
    private final TeacherResourceAssetService assetService;
    private final TeacherResourceUploadService uploadService;
    private final TeacherResourceImageClipService imageClipService;
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
                new SynchronousTeacherSourceSyncCommandDispatcher(syncExecutionService),
                blockSearchService,
                blockSearchAuditLookup,
                checkpointQueryService,
                blockStore,
                TeacherResourceAssetService.disabled(),
                TeacherResourceUploadService.disabled(),
                null,
                subjectResolver,
                capabilityVerifier);
    }

    /**
     * Compatibility constructor for focused controller tests and callers that execute work in-process.
     * Production component wiring selects the overload below and injects the RabbitMQ dispatcher.
     */
    public TeacherResourceController(
            TeacherResourceService teacherResourceService,
            TeacherSourceSyncJobService syncJobService,
            TeacherSourceSyncExecutionService syncExecutionService,
            TeacherResourceBlockSearchService blockSearchService,
            TeacherResourceBlockSearchAuditLookup blockSearchAuditLookup,
            TeacherSourceSyncCheckpointQueryService checkpointQueryService,
            TeacherDocumentBlockStore blockStore,
            TeacherResourceAssetService assetService,
            TeacherResourceUploadService uploadService,
            RequestSubjectResolver subjectResolver,
            TeacherResourceCapabilityVerifier capabilityVerifier) {
        this(
                teacherResourceService,
                syncJobService,
                syncExecutionService,
                new SynchronousTeacherSourceSyncCommandDispatcher(syncExecutionService),
                blockSearchService,
                blockSearchAuditLookup,
                checkpointQueryService,
                blockStore,
                assetService,
                uploadService,
                null,
                subjectResolver,
                capabilityVerifier);
    }

    @Autowired
    public TeacherResourceController(
            TeacherResourceService teacherResourceService,
            TeacherSourceSyncJobService syncJobService,
            TeacherSourceSyncExecutionService syncExecutionService,
            TeacherSourceSyncCommandDispatcher syncCommandDispatcher,
            TeacherResourceBlockSearchService blockSearchService,
            TeacherResourceBlockSearchAuditLookup blockSearchAuditLookup,
            TeacherSourceSyncCheckpointQueryService checkpointQueryService,
            TeacherDocumentBlockStore blockStore,
            TeacherResourceAssetService assetService,
            TeacherResourceUploadService uploadService,
            TeacherResourceImageClipService imageClipService,
            RequestSubjectResolver subjectResolver,
            TeacherResourceCapabilityVerifier capabilityVerifier) {
        this.teacherResourceService = Objects.requireNonNull(teacherResourceService, "teacherResourceService");
        this.syncJobService = Objects.requireNonNull(syncJobService, "syncJobService");
        this.syncExecutionService = Objects.requireNonNull(syncExecutionService, "syncExecutionService");
        this.syncCommandDispatcher = Objects.requireNonNull(syncCommandDispatcher, "syncCommandDispatcher");
        this.blockSearchService = Objects.requireNonNull(blockSearchService, "blockSearchService");
        this.blockSearchAuditLookup = Objects.requireNonNull(blockSearchAuditLookup, "blockSearchAuditLookup");
        this.checkpointQueryService = Objects.requireNonNull(checkpointQueryService, "checkpointQueryService");
        this.blockStore = Objects.requireNonNull(blockStore, "blockStore");
        this.assetService = Objects.requireNonNull(assetService, "assetService");
        this.uploadService = Objects.requireNonNull(uploadService, "uploadService");
        this.imageClipService = imageClipService;
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
     * Saves browser-uploaded files into a backend-managed local directory, then registers that directory as a normal
     * teacher resource so the existing sync-job/parser/vector pipeline can ingest it unchanged.
     */
    @PostMapping(value = RESOURCES_UPLOAD_PATH, consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public TeacherResourceDocumentResponse uploadAndRegister(
            @RequestParam("files") List<MultipartFile> files,
            @RequestParam(value = "sourceType", required = false) String sourceType,
            @RequestParam(value = "title", required = false) String title,
            @RequestParam(value = "permissionScope", required = false) String permissionScope,
            @RequestParam(value = "parseMode", required = false) String parseMode,
            HttpServletRequest httpRequest) {
        RequestSubject subject = subjectResolver.resolve(httpRequest);
        if (!capabilityVerifier.verify(
                headerOrNull(httpRequest, "X-Capability-Token"),
                REGISTER_ACTION,
                RESOURCES_UPLOAD_PATH,
                headerOrNull(httpRequest, "X-Request-Hash"),
                subject)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Capability token required for teacher resource upload");
        }
        try {
            RequestSubject normalized = subject.normalize();
            String normalizedSourceType = TeacherResourceSourceTypePolicy.normalizeForRegistration(sourceType);
            if ("feishu".equalsIgnoreCase(normalizedSourceType)) {
                throw new IllegalArgumentException("Upload endpoint does not accept feishu sourceType; use register with originalUrl instead");
            }
            TeacherResourceUploadService.StoredUpload upload = uploadService.store(files, normalized);
            /*
             * Browser uploads are stored under a backend-generated UUID directory. When callers leave title blank, use
             * the original upload name captured by the upload service rather than leaking that opaque staging path into
             * the teacher-facing document title.
             */
            String resolvedTitle = TeacherResourceTitleResolver.resolveOrDefault(
                    title,
                    normalizedSourceType,
                    null,
                    upload.suggestedTitle());
            TeacherResourceRegistrationRequest registrationRequest = new TeacherResourceRegistrationRequest(
                    normalizedSourceType,
                    resolvedTitle,
                    null,
                    upload.rootPath().toString(),
                    permissionScope,
                    null,
                    parseMode);
            return teacherResourceService.register(enrich(registrationRequest, normalized));
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
     * <p>The search API now accepts only logical {@code library} selectors. Raw {@code sourceType} remains document
     * metadata for ingestion/debugging, but it is no longer exposed as a retrieval filter because mixing storage
     * implementation names with retrieval libraries made AI callers leak across QQ/Feishu/mock/textbook boundaries.</p>
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
            @RequestParam(value = "library", required = false) List<String> libraries,
            @RequestParam(value = "tag", required = false) List<String> tags,
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
                            libraries,
                            tags));
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, exception.getMessage(), exception);
        }
    }

    /**
     * Searches only rendered teacher-resource page assets through the private CLIP collection.  The request image is
     * a data URI supplied by the browser; no local path is accepted, so the backend remains the only file boundary.
     */
    @PostMapping("/api/teacher/resources/image-search")
    public TeacherResourceImageClipSearchResponse searchImages(
            @RequestBody TeacherResourceImageClipSearchRequest request,
            HttpServletRequest httpRequest) {
        if (imageClipService == null) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Teacher image CLIP is not configured");
        }
        RequestSubject subject = subjectResolver.resolve(httpRequest).normalize();
        if (!"teacher".equals(subject.subjectType()) && !"admin".equals(subject.subjectType())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Teacher image CLIP requires teacher or admin role");
        }
        try {
            TeacherResourceImageClipSearchRequest normalized = request == null
                    ? new TeacherResourceImageClipSearchRequest(null, null, 10, List.of()) : request;
            return imageClipService.search(subject.tenantId(), subject.subjectType(), subject.subjectId(),
                    normalized.query(), normalized.image(), normalized.normalizedLimit(), normalized.documentIds());
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage(), exception);
        }
    }

    /**
     * Backward-compatible test helper for callers that do not pass metadata filters.
     */
    public TeacherResourceBlockSearchResponse searchBlocks(
            String query,
            int limit,
            HttpServletRequest httpRequest) {
        return searchBlocks(query, limit, null, null, null, null, httpRequest);
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
        syncCommandDispatcher.dispatch(command(TeacherSourceSyncCommand.EXECUTE, subject, documentId, jobId));
        return syncJobService.findVisibleJob(subject.tenantId(), subject.subjectType(), subject.subjectId(), documentId, jobId);
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
        syncCommandDispatcher.dispatch(command(TeacherSourceSyncCommand.RESUME, subject, documentId, jobId));
        return syncJobService.findVisibleJob(subject.tenantId(), subject.subjectType(), subject.subjectId(), documentId, jobId);
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

    /** Builds the token-free command after the controller has performed the relevant capability verification. */
    private static TeacherSourceSyncCommand command(
            String action,
            RequestSubject subject,
            String documentId,
            String jobId) {
        RequestSubject normalized = subject.normalize();
        return new TeacherSourceSyncCommand(
                TeacherSourceSyncCommand.CURRENT_SCHEMA_VERSION,
                action,
                normalized.tenantId(),
                normalized.subjectType(),
                normalized.subjectId(),
                documentId,
                jobId);
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

