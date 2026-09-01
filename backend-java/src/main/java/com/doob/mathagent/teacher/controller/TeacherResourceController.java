package com.doob.mathagent.teacher.controller;

import com.doob.mathagent.infrastructure.security.RequestSubject;
import com.doob.mathagent.infrastructure.security.RequestSubjectResolver;
import com.doob.mathagent.retrieval.CanonicalMathPaperAssetService;
import com.doob.mathagent.retrieval.CanonicalMathPaperRetrievalService;
import com.doob.mathagent.teaching.TeachingEvidence;
import com.doob.mathagent.teacher.dto.TeacherResourceRegistrationRequest;
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
import com.doob.mathagent.teacher.document.TeacherFileDocumentResponse;
import com.doob.mathagent.teacher.vo.TeacherSourceSyncCheckpointResponse;
import com.doob.mathagent.teacher.vo.TeacherSourceSyncJobResponse;
import com.doob.mathagent.teacher.vo.TeacherResourceAssetResponse;
import com.doob.mathagent.vector.service.TeacherResourceImageClipSearchRequest;
import com.doob.mathagent.vector.service.TeacherResourceImageClipSearchResponse;
import com.doob.mathagent.vector.service.TeacherResourceImageClipService;
import com.doob.mathagent.feishu.FeishuCredentialService;
import com.doob.mathagent.feishu.FeishuResourceBindingService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.Resource;
import org.springframework.core.env.Environment;
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

    /** Resource cards need only their newest sync job; later pages remain available through explicit query values. */
    private static final String FIRST_SYNC_JOB_PAGE = "1";
    private static final String RESOURCE_CARD_SYNC_JOB_PAGE_SIZE = "1";

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
    private final FeishuCredentialService feishuCredentialService;
    private final FeishuResourceBindingService feishuResourceBindingService;
    private final Environment environment;
    /** Optional public canonical-paper route; teacher-resource FILE search remains the default path. */
    private CanonicalMathPaperRetrievalService canonicalMathPaperRetrievalService;
    /** Canonical question-image reader shared with the MCP broker; optional so focused tests skip it. */
    private CanonicalMathPaperAssetService canonicalMathPaperAssetService;

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
            RequestSubjectResolver subjectResolver) {
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
                null,
                null,
                null);
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
            RequestSubjectResolver subjectResolver) {
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
                null,
                null,
                null);
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
            FeishuCredentialService feishuCredentialService,
            FeishuResourceBindingService feishuResourceBindingService,
            Environment environment) {
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
        this.feishuCredentialService = feishuCredentialService;
        this.feishuResourceBindingService = feishuResourceBindingService;
        this.environment = environment;
    }

    /** Allows the public teacher-resource endpoint to reuse the manifest-authorized canonical paper retriever. */
    @Autowired(required = false)
    public void setCanonicalMathPaperRetrievalService(
            CanonicalMathPaperRetrievalService canonicalMathPaperRetrievalService) {
        this.canonicalMathPaperRetrievalService = canonicalMathPaperRetrievalService;
    }

    /** Wires the same manifest-bound asset reader the MCP broker uses; kept optional for the test constructors. */
    @Autowired(required = false)
    public void setCanonicalMathPaperAssetService(
            CanonicalMathPaperAssetService canonicalMathPaperAssetService) {
        this.canonicalMathPaperAssetService = canonicalMathPaperAssetService;
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
        if (feishuCredentialService != null && request != null
                && "feishu".equalsIgnoreCase(request.sourceType())
                && feishuCredentialService.findActive(subject.normalize().tenantId(), subject.normalize().subjectId()) == null
                && !administratorBotCredentialsConfigured(subject)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "AUTH_REQUIRED");
        }
        try {
            TeacherResourceDocumentResponse registered = teacherResourceService.register(enrich(request, subject));
            if (feishuResourceBindingService != null && "feishu".equalsIgnoreCase(request.sourceType())
                    && feishuCredentialService.findActive(subject.normalize().tenantId(), subject.normalize().subjectId()) != null) {
                feishuResourceBindingService.bind(subject.normalize().tenantId(), registered.documentId(), subject.normalize().subjectId());
            }
            return registered;
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage(), exception);
        }
    }

    /** Allows only administrators to use deployment bot credentials for tenant-public shared folders. */
    private boolean administratorBotCredentialsConfigured(RequestSubject subject) {
        RequestSubject normalized = subject.normalize();
        return "admin".equals(normalized.subjectType())
                && firstConfigured("FEISHU_APP_ID", "FEISHU_APPID", "APP_ID")
                && firstConfigured("FEISHU_APP_SECRET", "FEISHU_APPSECRET", "APP_SECRET");
    }

    /** Resolves documented aliases without ever exposing the credential value. */
    private boolean firstConfigured(String... names) {
        if (environment == null) return false;
        for (String name : names) {
            String value = environment.getProperty(name, "");
            if (value != null && !value.isBlank()) return true;
        }
        return false;
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
        try {
            RequestSubject normalized = subject.normalize();
            // Browser files are teacher uploads regardless of a form field supplied by an older client.  Keeping this
            // assignment at the HTTP boundary makes a later filename change incapable of changing the library.
            String normalizedSourceType = "teacher_resource";
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
     * <p>{@code library} and {@code sourceType} are two HTTP aliases for the same retrieval dimension: the filter
     * record stores normalized source-library selectors (feishu, qq_bundle, gaokao, mock_exam, ...) in its
     * {@code sourceTypes} field, exactly like the MCP tool that merges its {@code libraries}/{@code sourceTypes}
     * arguments before building the filter. The record has no dedicated "libraries" dimension and {@code tags}
     * matches free text instead of corpus selection, so dropping {@code library} into either of them would break
     * existing {@code ?library=} callers; the aliases are therefore merged. Before this merge only {@code library}
     * reached the filter, so {@code ?sourceType=gaokao} could never route into the canonical gaokao branch.</p>
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
            @RequestParam(value = "sourceType", required = false) List<String> sourceTypes,
            @RequestParam(value = "tag", required = false) List<String> tags,
            HttpServletRequest httpRequest) {
        RequestSubject subject = subjectResolver.resolve(httpRequest).normalize();
        requireTeacherOrAdmin(subject);
        // 两个别名并入 of() 的第 3 参（sourceTypes 即库选择器维度）；of() 会统一去空白、转小写并去重。
        List<String> librarySelectors = new java.util.ArrayList<>();
        if (libraries != null) {
            librarySelectors.addAll(libraries);
        }
        if (sourceTypes != null) {
            librarySelectors.addAll(sourceTypes);
        }
        TeacherResourceSearchFilter filter = TeacherResourceSearchFilter.of(
                permissionScopes,
                documentIds,
                librarySelectors,
                tags);
        try {
            if (filter.sourceTypes().contains("gaokao")) {
                return searchCanonicalMathPapers(query, limit);
            }
            return blockSearchService.search(
                    subject.tenantId(),
                    subject.subjectType(),
                    subject.subjectId(),
                    query,
                    limit,
                    "/api/teacher/resources/search",
                    filter);
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, exception.getMessage(), exception);
        }
    }

    /**
     * Adapts already-authorized canonical evidence to the teacher search response without making it a teacher FILE.
     * An explicit gaokao selector is exclusive, so no Feishu or mock-exam fallback can leak into this route.
     */
    private TeacherResourceBlockSearchResponse searchCanonicalMathPapers(
            String query,
            int limit) {
        int safeLimit = Math.max(1, Math.min(12, limit));
        List<TeachingEvidence> evidence = canonicalMathPaperRetrievalService == null
                ? List.of()
                : canonicalMathPaperRetrievalService.search(query, safeLimit);
        List<TeacherResourceBlockSearchResponse.Hit> hits = evidence.stream()
                .map(TeacherResourceController::canonicalPaperHit)
                .limit(safeLimit)
                .toList();
        return new TeacherResourceBlockSearchResponse(
                UUID.randomUUID().toString(),
                query == null ? "" : query.strip(),
                safeLimit,
                "canonical_gaokao_manifest_authorized",
                hits.size(),
                hits,
                new TeacherResourceBlockSearchResponse.CandidateFunnel(
                        List.of(), List.of(), List.of(), List.of(),
                        hits.stream()
                                .map(TeacherResourceBlockSearchResponse.Hit::fileDocumentId)
                                .filter(value -> value != null && !value.isBlank())
                                .distinct()
                                .toList(),
                        hits.stream()
                                .map(TeacherResourceBlockSearchResponse.Hit::blockId)
                                .filter(value -> value != null && !value.isBlank())
                                .distinct()
                                .toList(),
                        hits.size(),
                        false,
                        java.util.Map.of(),
                        List.of(),
                        List.of(),
                        hits.isEmpty() ? "no_canonical_hits" : "none"));
    }

    private static TeacherResourceBlockSearchResponse.Hit canonicalPaperHit(TeachingEvidence evidence) {
        String questionNumber = evidence.canonicalQuestionNumber();
        String documentRef = evidence.sourceDocumentId();
        String blockId = evidence.chunkId();
        String sourcePath = evidence.sourceTitle();
        String evidenceRef = evidence.chunkId();
        return new TeacherResourceBlockSearchResponse.Hit(
                documentRef,
                evidence.sourceTitle(),
                "gaokao",
                "PUBLIC_GAOKAO",
                blockId.isBlank() ? evidenceRef : blockId,
                "question",
                0,
                "",
                "",
                evidence.pageNo(),
                sourcePath,
                sourcePath,
                "reference",
                List.of(),
                evidence.chunkId().isBlank() ? List.of(evidenceRef) : List.of(evidenceRef),
                evidence.snippet(),
                evidence.snippet(),
                0.0d,
                evidence.assetIds(),
                List.of(),
                "",
                documentRef,
                "",
                "");
    }

    /**
     * Streams one canonical gaokao question image to a signed-in teacher/admin over plain HTTP.
     *
     * <p>This exposes the MCP broker's existing canonical image materialization
     * ({@link CanonicalMathPaperAssetService#openVisibleAsset(String, RequestSubject)}) as a read-only HTTP view;
     * it adds no new authorization logic. Visibility is still decided by the manifest binding rechecked inside the
     * service, so unknown or tampered asset ids resolve to 404 exactly like the broker path. The body is streamed
     * from the {@code FileSystemResource} instead of being copied into memory to keep concurrent image reads cheap.</p>
     */
    @GetMapping("/api/teacher/resources/gaokao/assets/{assetId}/image")
    public ResponseEntity<Resource> readGaokaoAssetImage(
            @PathVariable String assetId,
            HttpServletRequest httpRequest) {
        RequestSubject subject = subjectResolver.resolve(httpRequest).normalize();
        requireTeacherOrAdmin(subject);
        if (canonicalMathPaperAssetService == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Canonical gaokao asset service is not configured");
        }
        CanonicalMathPaperAssetService.VisibleAsset asset = canonicalMathPaperAssetService
                .openVisibleAsset(assetId, subject)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Canonical gaokao asset not found"));
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(asset.mimeType()))
                .body(asset.resource());
    }

    /**
     * Searches only rendered teacher-resource page assets through the private CLIP collection. The request image is
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
     * Compatibility overload for focused controller tests that predate the {@code sourceType} alias parameter;
     * HTTP routing always lands on the full overload above where both aliases are merged.
     */
    public TeacherResourceBlockSearchResponse searchBlocks(
            String query,
            int limit,
            List<String> permissionScopes,
            List<String> documentIds,
            List<String> libraries,
            List<String> tags,
            HttpServletRequest httpRequest) {
        return searchBlocks(query, limit, permissionScopes, documentIds, libraries, null, tags, httpRequest);
    }

    /**
     * Lists a bounded page of searchable physical FILE documents below one visible Feishu ROOT.
     * The ROOT is only the authorization/synchronization scope; every returned id is an independent persisted FILE.
     */
    @GetMapping("/api/teacher/resources/{documentId}/files")
    public List<TeacherFileDocumentResponse> listPhysicalFiles(
            @PathVariable String documentId,
            @RequestParam(defaultValue = "512") int limit,
            HttpServletRequest httpRequest) {
        RequestSubject subject = subjectResolver.resolve(httpRequest).normalize();
        try {
            return teacherResourceService.listPhysicalFiles(
                    subject.tenantId(), subject.subjectType(), subject.subjectId(), documentId, limit);
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Teacher resource FILE documents not found", exception);
        }
    }

    /**
     * Lists parsed blocks for one visible physical FILE document.
     *
     * <p>ROOT rows are deliberately rejected here. The management endpoint is bounded to a FILE-local page so it cannot
     * turn a shared ROOT into an unbounded document or benchmark oracle.</p>
     */
    @GetMapping("/api/teacher/resources/{documentId}/blocks")
    public List<TeacherDocumentBlockResponse> listBlocks(
            @PathVariable String documentId,
            @RequestParam(defaultValue = "512") int limit,
            HttpServletRequest httpRequest) {
        RequestSubject subject = subjectResolver.resolve(httpRequest).normalize();
        try {
            if (limit < 1 || limit > 512) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "limit must be between 1 and 512");
            }
            boolean visibleFile = teacherResourceService.isVisiblePhysicalFile(
                    subject.tenantId(), subject.subjectType(), subject.subjectId(), documentId);
            if (!visibleFile) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Teacher resource FILE not found");
            }
            return blockStore.listBlocksForFile(subject.tenantId(), documentId, limit, null);
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Teacher resource FILE not found", exception);
        }
    }

    /** Compatibility helper retained for focused controller tests and non-HTTP callers. */
    public List<TeacherDocumentBlockResponse> listBlocks(
            String documentId,
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
     * Lists metadata for active image assets attached to a visible teacher resource.
     *
     * <p>Only metadata is returned here. The image bytes remain behind the existing opaque asset-id endpoint,
     * keeping storage keys and provider tokens out of the response while using the same visibility boundary as blocks.</p>
     */
    @GetMapping("/api/teacher/resources/{documentId}/assets")
    public List<TeacherResourceAssetResponse> listAssets(
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
        return assetService.listActiveImageAssets(subject.tenantId(), documentId);
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
     * @param httpRequest HTTP request containing the authenticated session
     * @return queued sync job
     */
    @PostMapping("/api/teacher/resources/{documentId}/sync-jobs")
    public TeacherSourceSyncJobResponse createSyncJob(
            @PathVariable String documentId,
            HttpServletRequest httpRequest) {
        RequestSubject subject = subjectResolver.resolve(httpRequest).normalize();
        return syncJobService.createSyncJob(
                subject.tenantId(),
                subject.subjectType(),
                subject.subjectId(),
                documentId);
    }

    /**
     * Executes a queued source synchronization job for the authenticated teacher/admin.
     *
     * @param documentId resource document id
     * @param jobId sync job id
     * @param httpRequest HTTP request containing the authenticated session
     * @return completed, running, or failed sync job state
     */
    @PostMapping("/api/teacher/resources/{documentId}/sync-jobs/{jobId}/execute")
    public TeacherSourceSyncJobResponse executeSyncJob(
            @PathVariable String documentId,
            @PathVariable String jobId,
            HttpServletRequest httpRequest) {
        RequestSubject subject = subjectResolver.resolve(httpRequest).normalize();
        syncCommandDispatcher.dispatch(command(TeacherSourceSyncCommand.EXECUTE, subject, documentId, jobId));
        return syncJobService.findVisibleJob(subject.tenantId(), subject.subjectType(), subject.subjectId(), documentId, jobId);
    }

    /**
     * Resumes a paused source synchronization job when a durable checkpoint is available.
     *
     * @param documentId resource document id
     * @param jobId sync job id
     * @param httpRequest HTTP request containing the authenticated session
     * @return resumed sync job state
     */
    @PostMapping("/api/teacher/resources/{documentId}/sync-jobs/{jobId}/resume")
    public TeacherSourceSyncJobResponse resumeSyncJob(
            @PathVariable String documentId,
            @PathVariable String jobId,
            HttpServletRequest httpRequest) {
        RequestSubject subject = subjectResolver.resolve(httpRequest).normalize();
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
            @RequestParam(defaultValue = FIRST_SYNC_JOB_PAGE) int page,
            @RequestParam(defaultValue = RESOURCE_CARD_SYNC_JOB_PAGE_SIZE) int pageSize,
            HttpServletRequest httpRequest) {
        RequestSubject subject = subjectResolver.resolve(httpRequest).normalize();
        return syncJobService.listSyncJobs(
                subject.tenantId(),
                subject.subjectType(),
                subject.subjectId(),
                documentId,
                page,
                pageSize);
    }

    /** Keeps controller-level callers/tests on the card-safe first page while HTTP clients use explicit pagination. */
    public List<TeacherSourceSyncJobResponse> listSyncJobs(
            String documentId,
            HttpServletRequest httpRequest) {
        return listSyncJobs(documentId, Integer.parseInt(FIRST_SYNC_JOB_PAGE),
                Integer.parseInt(RESOURCE_CARD_SYNC_JOB_PAGE_SIZE), httpRequest);
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

    /** Builds the command with the backend-resolved user and tenant identity. */
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
     * Ensures direct controller calls cannot bypass teacher/admin audit visibility rules.
     */
    private static void requireTeacherOrAdmin(RequestSubject subject) {
        String role = subject.subjectType();
        if (!"teacher".equals(role) && !"admin".equals(role)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Teacher resource audit requires teacher or admin role");
        }
    }
}

