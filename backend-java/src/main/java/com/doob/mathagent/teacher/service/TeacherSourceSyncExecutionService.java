package com.doob.mathagent.teacher.service;

import com.doob.mathagent.teacher.block.TeacherDocumentBlockResponse;
import com.doob.mathagent.teacher.block.TeacherDocumentBlockStore;
import com.doob.mathagent.teacher.document.TeacherResourceDocumentResponse;
import com.doob.mathagent.teacher.document.TeacherResourceStore;
import com.doob.mathagent.teacher.feishu.TeacherFeishuDownloadClient;
import com.doob.mathagent.teacher.feishu.TeacherFeishuDownloadException;
import com.doob.mathagent.feishu.FeishuCredential;
import com.doob.mathagent.feishu.FeishuCredentialService;
import com.doob.mathagent.feishu.FeishuResourceBindingService;
import com.doob.mathagent.teacher.formula.OmmlFormulaExtractor;
import com.doob.mathagent.teacher.formula.TeacherFormulaRecognitionClient;
import com.doob.mathagent.teacher.formula.TeacherFormulaRecognitionProperties;
import com.doob.mathagent.teacher.search.TeacherResourceGraphAlignmentService;
import com.doob.mathagent.teacher.support.TeacherResourceSourceIdentity;
import com.doob.mathagent.teacher.sync.TeacherSourceSyncCheckpointStore;
import com.doob.mathagent.teacher.sync.TeacherSourceSyncJobStore;
import com.doob.mathagent.teacher.sync.TeacherSourceSyncProperties;
import com.doob.mathagent.teacher.sync.TeacherSourceSyncManifestStore;
import com.doob.mathagent.teacher.vo.TeacherSourceSyncCheckpointResponse;
import com.doob.mathagent.teacher.vo.TeacherSourceSyncFailureResponse;
import com.doob.mathagent.teacher.vo.TeacherSourceSyncJobResponse;
import com.doob.mathagent.infrastructure.security.RequestSubject;
import com.doob.mathagent.vector.service.VectorIndexRebuildResponse;
import com.doob.mathagent.vector.service.VectorIndexService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.net.URLDecoder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.stream.Stream;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import javax.imageio.ImageIO;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.openxml4j.util.ZipSecureFile;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFPicture;
import org.apache.poi.xwpf.usermodel.XWPFPictureData;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.springframework.beans.factory.annotation.Autowired;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Executes queued teacher source synchronization jobs.
 *
 * Do not put @Service back on this class while the legacy test constructors exist. Spring picked a compatibility
 * constructor in real runs and disabled asset persistence. Production wiring is pinned in
 * TeacherSourceSyncExecutionConfiguration so DOCX/PDF/Feishu assets always use the real asset service.
 */
public class TeacherSourceSyncExecutionService {

    /** PDF text layers use private-use glyphs for several operators; those pages require visual formula recovery. */
    static final Pattern UNRESOLVED_PDF_MATH_GLYPH = Pattern.compile("[\\p{Co}□�]");

    /** POI's default 1,000 package-entry guard rejects real exam DOCX files with hundreds of formula/image parts. */
    static final int DEFAULT_DOCX_MAX_ZIP_ENTRIES = 5_000;
    /** Launch configuration is supplied by the local backend script and remains bounded to resist ZIP entry attacks. */
    static final String DOCX_MAX_ZIP_ENTRIES_PROPERTY = "math.agent.teacher.sync.docx-max-zip-entries";

    static final Logger LOGGER = LoggerFactory.getLogger(TeacherSourceSyncExecutionService.class);
    static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    static final int MAX_SCAN_DEPTH = 8;
    private static final String FILE_SPLIT_FINGERPRINT = "teacher-sync-file-v2";


    static final long FILE_RETRY_DELAY_SECONDS = 300L;
    static final int PDF_PAGE_RENDER_DPI = 144;
    static final String PDF_PAGE_RENDER_DPI_ENV = "MATH_AGENT_PDF_PAGE_RENDER_DPI";
    static final long NATIVE_PDF_RENDER_TIMEOUT_SECONDS = 30L;
    /** Page transcription is paid remote work; first 48 pages are enough to form a qualified ten-question seed. */
    static final int MAX_PAGE_TRANSCRIPTION_PAGES_PER_DOCUMENT = 48;
    static final String NATIVE_PDF_RENDERER = "pdftocairo.exe";
    static final String NATIVE_PDF_RENDERER_ENV = "MATH_AGENT_PDF_RENDERER_EXECUTABLE";
    static final Pattern MARKDOWN_IMAGE_PATTERN = Pattern.compile(
            "!\\[([^]]*)\\]\\(\\s*(?:<([^>]+)>|([^\\s)]+))", Pattern.CASE_INSENSITIVE);
    // Feishu's document export may materialize an image URL as either `src` or `href` (and may place
    // either attribute first). Keep the accepted forms aligned with the Python downloader so a successfully
    // downloaded image is still discovered and persisted during the Java-side local asset scan.
    static final Pattern HTML_IMAGE_TAG_PATTERN = Pattern.compile(
            "<img\\b[^>]*>", Pattern.CASE_INSENSITIVE);
    static final Pattern HTML_IMAGE_HREF_PATTERN = Pattern.compile(
            "\\bhref\\s*=\\s*(?:\\\"([^\\\"]*)\\\"|'([^']*)'|([^\\s>]+))",
            Pattern.CASE_INSENSITIVE);
    static final Pattern HTML_IMAGE_SRC_PATTERN = Pattern.compile(
            "\\bsrc\\s*=\\s*(?:\\\"([^\\\"]*)\\\"|'([^']*)'|([^\\s>]+))",
            Pattern.CASE_INSENSITIVE);
    static final Pattern PAGE_SECTION_PATTERN = Pattern.compile("^page\\s+(\\d+)$", Pattern.CASE_INSENSITIVE);
    /** Only pages with an explicit replacement glyph need an expensive full-page text repair call. */
    static final Pattern UNRESOLVED_MATHEMATICAL_OCR_GLYPH = Pattern.compile("[□�]");

    private final TeacherResourceStore resourceStore;
    private final TeacherSourceSyncJobStore jobStore;
    private final TeacherDocumentBlockStore blockStore;
    private final TeacherFeishuDownloadClient feishuDownloadClient;
    private final TeacherSourceSyncProperties syncProperties;
    private final TeacherSourceSyncCheckpointStore checkpointStore;
    private final VectorIndexService vectorIndexService;
    private final TeacherResourceGraphAlignmentService graphAlignmentService;
    private final TeacherResourceAssetService assetService;
    private final TeacherFormulaRecognitionClient formulaRecognitionClient;
    private final TeacherFormulaRecognitionProperties formulaRecognitionProperties;
    private final TeacherPageTranscriptionClient pageTranscriptionClient;
    private FeishuCredentialService feishuCredentialService;
    private FeishuResourceBindingService feishuResourceBindingService;
    private TeacherSourceSyncManifestStore manifestStore;
    private TeacherSourceFileReader sourceFileReader;

    public void setSourceFileReader(TeacherSourceFileReader sourceFileReader) {
        this.sourceFileReader = sourceFileReader;
    }

    /**
     * Creates a sync execution service.
     *
     * @param resourceStore source document store
     * @param jobStore sync job store
     * @param blockStore parsed block store
     */
    public TeacherSourceSyncExecutionService(
            TeacherResourceStore resourceStore,
            TeacherSourceSyncJobStore jobStore,
            TeacherDocumentBlockStore blockStore,
            TeacherFeishuDownloadClient feishuDownloadClient,
            TeacherSourceSyncProperties syncProperties,
            TeacherSourceSyncCheckpointStore checkpointStore,
            VectorIndexService vectorIndexService) {
        this(
                resourceStore,
                jobStore,
                blockStore,
                feishuDownloadClient,
                syncProperties,
                checkpointStore,
                vectorIndexService,
                TeacherResourceGraphAlignmentService.disabled(),
                TeacherResourceAssetService.disabled(),
                TeacherFormulaRecognitionClient.disabled(),
                new TeacherFormulaRecognitionProperties(false, 0, 2),
                TeacherPageTranscriptionClient.disabled());
    }

    /**
     * Production constructor with graph normalization.
     */
    public TeacherSourceSyncExecutionService(
            TeacherResourceStore resourceStore,
            TeacherSourceSyncJobStore jobStore,
            TeacherDocumentBlockStore blockStore,
            TeacherFeishuDownloadClient feishuDownloadClient,
            TeacherSourceSyncProperties syncProperties,
            TeacherSourceSyncCheckpointStore checkpointStore,
            VectorIndexService vectorIndexService,
            TeacherResourceGraphAlignmentService graphAlignmentService) {
        this(
                resourceStore,
                jobStore,
                blockStore,
                feishuDownloadClient,
                syncProperties,
                checkpointStore,
                vectorIndexService,
                graphAlignmentService,
                TeacherResourceAssetService.disabled(),
                TeacherFormulaRecognitionClient.disabled(),
                new TeacherFormulaRecognitionProperties(false, 0, 2),
                TeacherPageTranscriptionClient.disabled());
    }

    /**
     * Production constructor with graph normalization and persisted image assets.
     */
    public TeacherSourceSyncExecutionService(
            TeacherResourceStore resourceStore,
            TeacherSourceSyncJobStore jobStore,
            TeacherDocumentBlockStore blockStore,
            TeacherFeishuDownloadClient feishuDownloadClient,
            TeacherSourceSyncProperties syncProperties,
            TeacherSourceSyncCheckpointStore checkpointStore,
            VectorIndexService vectorIndexService,
            TeacherResourceGraphAlignmentService graphAlignmentService,
            TeacherResourceAssetService assetService) {
        this(
                resourceStore,
                jobStore,
                blockStore,
                feishuDownloadClient,
                syncProperties,
                checkpointStore,
                vectorIndexService,
                graphAlignmentService,
                assetService,
                TeacherFormulaRecognitionClient.disabled(),
                new TeacherFormulaRecognitionProperties(false, 0, 2),
                TeacherPageTranscriptionClient.disabled());
    }

    /**
     * Production constructor includes the worker-backed formula recognizer. Compatibility constructors above remain
     * deliberately offline for focused tests and must not be selected by Spring production wiring.
     */
    public TeacherSourceSyncExecutionService(
            TeacherResourceStore resourceStore,
            TeacherSourceSyncJobStore jobStore,
            TeacherDocumentBlockStore blockStore,
            TeacherFeishuDownloadClient feishuDownloadClient,

            TeacherSourceSyncProperties syncProperties,
            TeacherSourceSyncCheckpointStore checkpointStore,
            VectorIndexService vectorIndexService,
            TeacherResourceGraphAlignmentService graphAlignmentService,
            TeacherResourceAssetService assetService,
            TeacherFormulaRecognitionClient formulaRecognitionClient,
            TeacherFormulaRecognitionProperties formulaRecognitionProperties) {
        this(
                resourceStore, jobStore, blockStore, feishuDownloadClient, syncProperties, checkpointStore,
                vectorIndexService, graphAlignmentService, assetService, formulaRecognitionClient,
                formulaRecognitionProperties, TeacherPageTranscriptionClient.disabled());
    }

    /**
     * Production constructor adds page-level visible-text transcription after the image asset has been persisted and
     * authorized. Compatibility constructors intentionally keep this disabled so unit tests never call a provider.
     */
    public TeacherSourceSyncExecutionService(
            TeacherResourceStore resourceStore,
            TeacherSourceSyncJobStore jobStore,

            TeacherDocumentBlockStore blockStore,
            TeacherFeishuDownloadClient feishuDownloadClient,
            TeacherSourceSyncProperties syncProperties,
            TeacherSourceSyncCheckpointStore checkpointStore,
            VectorIndexService vectorIndexService,
            TeacherResourceGraphAlignmentService graphAlignmentService,
            TeacherResourceAssetService assetService,
            TeacherFormulaRecognitionClient formulaRecognitionClient,
            TeacherFormulaRecognitionProperties formulaRecognitionProperties,
            TeacherPageTranscriptionClient pageTranscriptionClient) {
        this.resourceStore = resourceStore;
        this.jobStore = jobStore;
        this.blockStore = blockStore;
        this.feishuDownloadClient = feishuDownloadClient;
        this.syncProperties = syncProperties;
        this.checkpointStore = checkpointStore;
        this.vectorIndexService = Objects.requireNonNull(vectorIndexService, "vectorIndexService is required");
        this.graphAlignmentService = Objects.requireNonNull(graphAlignmentService, "graphAlignmentService is required");
        this.assetService = Objects.requireNonNull(assetService, "assetService is required");
        this.formulaRecognitionClient = Objects.requireNonNull(formulaRecognitionClient, "formulaRecognitionClient is required");
        this.formulaRecognitionProperties = Objects.requireNonNull(
                formulaRecognitionProperties,
                "formulaRecognitionProperties is required");
        this.pageTranscriptionClient = Objects.requireNonNull(pageTranscriptionClient, "pageTranscriptionClient is required");
        this.feishuCredentialService = null;
        this.feishuResourceBindingService = null;
        // Startup evidence for the production wiring: only the boolean is logged, never a source page, path, prompt,
        // provider key, or teacher-private text. This makes a stale compatibility constructor immediately visible.
        LOGGER.info("teacher_source_sync_wiring assetPersistenceEnabled=true pageTranscriptionEnabled={}",
                this.pageTranscriptionClient.isEnabled());
    }

    /** Production constructor that resolves a tenant/user OAuth token immediately before Feishu I/O. */
    public TeacherSourceSyncExecutionService(
            TeacherResourceStore resourceStore, TeacherSourceSyncJobStore jobStore, TeacherDocumentBlockStore blockStore,
            TeacherFeishuDownloadClient feishuDownloadClient, TeacherSourceSyncProperties syncProperties,
            TeacherSourceSyncCheckpointStore checkpointStore, VectorIndexService vectorIndexService,
            TeacherResourceGraphAlignmentService graphAlignmentService, TeacherResourceAssetService assetService,
            TeacherFormulaRecognitionClient formulaRecognitionClient, TeacherFormulaRecognitionProperties formulaRecognitionProperties,
            TeacherPageTranscriptionClient pageTranscriptionClient, FeishuCredentialService feishuCredentialService) {
        this(resourceStore, jobStore, blockStore, feishuDownloadClient, syncProperties, checkpointStore, vectorIndexService,
                graphAlignmentService, assetService, formulaRecognitionClient, formulaRecognitionProperties, pageTranscriptionClient);
        this.feishuCredentialService = feishuCredentialService;
        this.feishuResourceBindingService = null;
    }

    /** Full production wiring with explicit resource-to-user authorization binding. */
    public TeacherSourceSyncExecutionService(
            TeacherResourceStore resourceStore, TeacherSourceSyncJobStore jobStore, TeacherDocumentBlockStore blockStore,
            TeacherFeishuDownloadClient feishuDownloadClient, TeacherSourceSyncProperties syncProperties,
            TeacherSourceSyncCheckpointStore checkpointStore, VectorIndexService vectorIndexService,
            TeacherResourceGraphAlignmentService graphAlignmentService, TeacherResourceAssetService assetService,
            TeacherFormulaRecognitionClient formulaRecognitionClient, TeacherFormulaRecognitionProperties formulaRecognitionProperties,
            TeacherPageTranscriptionClient pageTranscriptionClient, FeishuCredentialService credentials,
            FeishuResourceBindingService bindings) {
        this(resourceStore,jobStore,blockStore,feishuDownloadClient,syncProperties,checkpointStore,vectorIndexService,graphAlignmentService,
                assetService,formulaRecognitionClient,formulaRecognitionProperties,pageTranscriptionClient,credentials);
        this.feishuResourceBindingService = bindings;
        this.manifestStore = null;
    }

    /** Production constructor with file-level manifest persistence; older focused tests keep the null-safe overload. */
    public TeacherSourceSyncExecutionService(
            TeacherResourceStore resourceStore, TeacherSourceSyncJobStore jobStore, TeacherDocumentBlockStore blockStore,
            TeacherFeishuDownloadClient feishuDownloadClient, TeacherSourceSyncProperties syncProperties,
            TeacherSourceSyncCheckpointStore checkpointStore, VectorIndexService vectorIndexService,
            TeacherResourceGraphAlignmentService graphAlignmentService, TeacherResourceAssetService assetService,
            TeacherFormulaRecognitionClient formulaRecognitionClient, TeacherFormulaRecognitionProperties formulaRecognitionProperties,
            TeacherPageTranscriptionClient pageTranscriptionClient, FeishuCredentialService credentials,
            FeishuResourceBindingService bindings, TeacherSourceSyncManifestStore manifestStore) {
        this(resourceStore, jobStore, blockStore, feishuDownloadClient, syncProperties, checkpointStore, vectorIndexService,
                graphAlignmentService, assetService, formulaRecognitionClient, formulaRecognitionProperties,
                pageTranscriptionClient, credentials, bindings);
        this.manifestStore = manifestStore;
    }

    /**
     * Executes a queued synchronization job for a visible teacher/admin resource.
     *
     * @param tenantId tenant id
     * @param viewerRole backend-resolved viewer role
     * @param viewerSubjectId backend-resolved viewer subject id
     * @param documentId source document id
     * @param jobId sync job id
     * @return completed or failed job status
     */
    public TeacherSourceSyncJobResponse execute(
            String tenantId,
            String viewerRole,
            String viewerSubjectId,
            String documentId,
            String jobId) {
        return executeWithCheckpoint(tenantId, viewerRole, viewerSubjectId, documentId, jobId, null);
    }

    /**
     * Executes a queued or paused synchronization job, optionally resuming from a durable Feishu checkpoint.
     */
    private TeacherSourceSyncJobResponse executeWithCheckpoint(
            String tenantId,
            String viewerRole,
            String viewerSubjectId,
            String documentId,
            String jobId,
            TeacherSourceSyncCheckpointResponse resumeCheckpoint) {
        String normalizedTenantId = requireText(tenantId, "tenantId is required");
        String normalizedRole = requireText(viewerRole, "viewerRole is required").toLowerCase(Locale.ROOT);
        String normalizedSubjectId = requireText(viewerSubjectId, "viewerSubjectId is required");
        requireTeacherOrAdmin(normalizedRole);
        TeacherResourceDocumentResponse document = requireVisibleDocument(
                normalizedTenantId,
                normalizedRole,
                normalizedSubjectId,
                documentId);
        TeacherSourceSyncJobResponse job = requireJob(document.tenantId(), document.documentId(), jobId);
        boolean feishuSource = "feishu".equalsIgnoreCase(textOrDefault(document.sourceType(), ""));
        TeacherSourceSyncJobResponse running = updateJob(
                job,
                "running",
                feishuSource ? "download_running" : "parse_running",
                null,
                feishuSource ? "Downloading Feishu source files" : "Parsing source files");
        jobStore.save(running);
        String changedItemsJson = "[]";
        String syncRootUrl = document.originalUrl();
        try {
            if (feishuSource) {
                if (resumeCheckpoint == null) {
                    saveFeishuCheckpoint(document, running, "[]", "[]", 1);
                }
                String boundSubjectId = feishuResourceBindingService == null ? normalizedSubjectId
                        : feishuResourceBindingService.subjectId(normalizedTenantId, document.documentId());
                FeishuCredential userCredential = feishuCredentialService == null ? null
                        : feishuCredentialService.findActive(normalizedTenantId, boundSubjectId);
                boolean usableUserCredential = userCredential != null && !userCredential.expired(Instant.now());
                boolean administratorAppCredentialFallback = "admin".equals(normalizedRole) && !usableUserCredential;
                if (feishuCredentialService != null
                        && !administratorAppCredentialFallback
                        && !usableUserCredential) {
                    if (userCredential != null) feishuCredentialService.markExpired(normalizedTenantId, boundSubjectId);
                    throw new TeacherFeishuDownloadException("Feishu authorization is required", false, null,
                            toDownloadCheckpoint(resumeCheckpoint), new TeacherSourceSyncFailureResponse("AUTH_REQUIRED", false, List.of(), null));
                }
                if (administratorAppCredentialFallback && userCredential != null && feishuCredentialService != null) {
                    // Expired rows must not suppress the tenant-bot fallback for administrator-managed shared roots.
                    feishuCredentialService.markExpired(normalizedTenantId, boundSubjectId);
                }
                // An administrator MCP key may operate the tenant bot against folders explicitly shared with that
                // bot. A missing per-user credential therefore delegates token acquisition to the process downloader;
                // teacher/student identities still require their own OAuth credential above.
                TeacherFeishuDownloadClient.FeishuDownloadResult result = feishuDownloadClient.downloadWithAccessToken(
                        requireText(document.originalUrl(), "Feishu resource originalUrl is required"),
                        syncProperties.feishuStagingRoot(),
                        syncProperties.feishuSmokeMaxFiles(),
                        textOrDefault(document.feishuExportFormat(), "md"),
                        toDownloadCheckpoint(resumeCheckpoint), usableUserCredential ? userCredential.accessToken() : null);
                Path savedSourceRoot = syncProperties.requireStagingPath(result.savedPath());
                changedItemsJson = result.changedItemsJson();
                TeacherSourceSyncCheckpointResponse downloadedCheckpoint = result.checkpoint().hasCursor()
                        ? toStoredCheckpoint(document, running, result.checkpoint(), result.failedItemsJson())
                        : null;
                saveFeishuCheckpoint(
                        document,
                        running,
                        downloadedCheckpoint,
                        mergeDownloadedItemsJson(result),
                        result.failedItemsJson(),
                        2);
                if (manifestStore != null) {
                    manifestStore.recordDiscovery(
                            document.tenantId(), document.originalUrl(), document.ownerSubjectId(), document.documentId(),
                            result.discoveredItemsJson());
                    manifestStore.markDownloaded(
                            document.tenantId(), document.originalUrl(), result.changedItemsJson(), Instant.now());
                }
                TeacherResourceDocumentResponse downloaded = new TeacherResourceDocumentResponse(
                        document.documentId(),
                        document.tenantId(),
                        document.ownerSubjectId(),
                        document.sourceType(),
                        firstNonBlank(result.providerTitle(), document.title()),
                        document.originalUrl(),
                        result.savedPath().toString(),
                        document.permissionScope(),
                        "downloaded",
                        "pending",
                        "pending",
                        "waiting_rebuild",
                        document.feishuExportFormat(),
                        document.previewFiles(),
                        document.parseMode(),
                        firstNonBlank(result.providerRevision(), document.providerRevision()),
                        null,
                        document.sourceIdentity());

                /*
                 * The downloader has already compared Feishu metadata (modified time/revision/name/size) with the
                 * durable local manifest. An empty changed set is a successful no-op: do not retire assets, parse the
                 * whole staging tree, or rebuild Milvus. The provider title/revision is still persisted so the local
                 * record remains an accurate mirror of Feishu metadata.
                 */
                boolean imageBindingBackfillRequired = resourceStore.supportsFileDocuments()
                        && requiresImageBindingBackfill(normalizedTenantId, document.documentId());
                if (result.changedItemsJson().equals("[]")) {
                    /*
                     * A ready legacy ROOT may have no FILE rows yet. Treat that state as a one-time durable migration
                     * trigger: the existing staging snapshot is still authorized by the successful downloader, so it
                     * can be parsed into independent FILE documents without deleting the legacy ROOT blocks.
                     */
                    boolean fileMigrationRequired = resourceStore.supportsFileDocuments()
                            && (resourceStore.listFileDocumentsForIndexing(
                                    normalizedTenantId, document.documentId(), 1, "").isEmpty()
                                || resourceStore.hasArchivedFileDocuments(
                                        normalizedTenantId, document.documentId()));
                    if (!fileMigrationRequired && !imageBindingBackfillRequired) {
                        if (resourceStore.supportsFileDocuments()) {
                            archiveMissingFileDocuments(
                                    normalizedTenantId,
                                    document,
                                    result.discoveredItemsJson());
                        }
                        syncSourceCatalog(document, savedSourceRoot, document.contentChecksum(), true);
                    TeacherResourceDocumentResponse metadataOnly = withSyncFingerprint(
                            new TeacherResourceDocumentResponse(
                                    downloaded.documentId(), downloaded.tenantId(), downloaded.ownerSubjectId(),
                                    downloaded.sourceType(), downloaded.title(), downloaded.originalUrl(),
                                    document.localPath(), downloaded.permissionScope(), downloaded.syncStatus(),
                                    downloaded.parseStatus(), downloaded.embeddingStatus(), downloaded.indexStatus(),
                                    downloaded.feishuExportFormat(), downloaded.previewFiles(), downloaded.parseMode(),
                                    downloaded.providerRevision(), document.contentChecksum(), downloaded.sourceIdentity()),
                            document.contentChecksum());
                    if (hasVerifiedVectorReadiness(document)) {
                        TeacherResourceDocumentResponse unchanged = markUnchangedFeishuResourceSynced(metadataOnly, document);
                        if (manifestStore != null && !result.unchangedItemsJson().equals("[]")) {
                            manifestStore.markIndexed(document.tenantId(), syncRootUrl, result.unchangedItemsJson(), Instant.now());
                        }
                        TeacherResourceAssetService.AssetMigrationSummary assetMigration =
                                assetService.migrateDocumentAssetsToReadableNames(document.tenantId(), document.documentId());
                        TeacherSourceSyncJobResponse completed = updateJob(
                                running, "completed", "skipped_unchanged", result.savedPath().toString(),
                                result.message() + "; metadata unchanged; local files and vector index retained"
                                        + "; readableAssetMigration=" + assetMigration.migratedCount()
                                        + ", legacyHashFilesRemoved=" + assetMigration.removedLegacyFileCount()
                                        + ", assetMigrationElapsedMs=" + assetMigration.elapsedMs());
                        saveFeishuCheckpoint(
                                unchanged, completed,
                                result.checkpoint().hasCursor()
                                        ? toStoredCheckpoint(unchanged, completed, result.checkpoint(), result.failedItemsJson())
                                        : null,
                                mergeDownloadedItemsJson(result), result.failedItemsJson(), 2);
                        return jobStore.save(completed);
                    }
                    /*
                     * A metadata-identical source can still have failed vector status after a previous worker outage.
                     * Reuse the already authorized active blocks so recovery rebuilds Milvus without downloading or
                     * reparsing the source again. The vector service applies the same tenant and subject checks.
                     */
                    TeacherResourceDocumentResponse pending = markLocalResourceSynced(
                            withSyncFingerprint(downloaded, document.contentChecksum()));
                    if (manifestStore != null && !result.unchangedItemsJson().equals("[]")) {
                        manifestStore.markEmbedding(document.tenantId(), syncRootUrl, result.unchangedItemsJson(), Instant.now());
                    }
                    String vectorMessage = autoRebuildVectorIndex(pending, normalizedRole, normalizedSubjectId);
                    if (manifestStore != null && !result.unchangedItemsJson().equals("[]")) {
                        manifestStore.markIndexed(document.tenantId(), syncRootUrl, result.unchangedItemsJson(), Instant.now());
                    }
                    TeacherSourceSyncJobResponse completed = updateJob(
                            running, "completed", "vector_rebuild_completed", result.savedPath().toString(),
                            result.message() + "; metadata unchanged; rebuilt vector index from persisted blocks" + vectorMessage);
                    saveFeishuCheckpoint(
                            pending, completed,
                            result.checkpoint().hasCursor()
                                    ? toStoredCheckpoint(pending, completed, result.checkpoint(), result.failedItemsJson())
                                    : null,
                            mergeDownloadedItemsJson(result), result.failedItemsJson(), 2);
                    return jobStore.save(completed);
                    }
                }
                /*
                 * Retire the prior generation before parsing. Parsing persists and reactivates the exact assets used
                 * by the new blocks; doing this after parsing incorrectly retires those newly written rows and leaves
                 * valid imageRefs pointing at assets that the delivery endpoint refuses to serve.
                 */
                assetService.markDocumentAssetsInactive(document.tenantId(), document.documentId());
                if (manifestStore != null) {
                    manifestStore.markParsing(document.tenantId(), document.originalUrl(), result.changedItemsJson(), Instant.now());
                }
                FileSeparatedParseResult separatedParse = null;
                List<TeacherDocumentBlockResponse> blocks = List.of();
                String contentChecksum;
                if (resourceStore.supportsFileDocuments()) {
                    separatedParse = parseResourceFilesIntoFileDocuments(
                            normalizedTenantId,
                            normalizedRole,
                            normalizedSubjectId,
                            downloaded,
                            true,
                            running,
                            mergeProviderFileItems(result));
                    contentChecksum = separatedParse.rootChecksum();
                } else {
                    blocks = parseResourceFiles(
                            normalizedTenantId,
                            normalizedRole,
                            normalizedSubjectId,
                            downloaded,
                            true,
                            running);
                    contentChecksum = semanticContentChecksum(blocks, downloaded.title());
                }
                downloaded = withSyncFingerprint(downloaded, contentChecksum);
                if (resourceStore.supportsFileDocuments()) {
                    archiveMissingFileDocuments(
                            normalizedTenantId,
                            downloaded,
                            mergeProviderFileItems(result));
                }
                if (!imageBindingBackfillRequired
                        && contentChecksum.equals(document.contentChecksum()) && hasVerifiedVectorReadiness(document)) {
                    /*
                     * Feishu can change title/revision without changing parsed body. Persist those real provider
                     * metadata changes, but retain active block/asset/vector rows: a delete-and-rebuild here would
                     * make title-only renames unnecessarily expensive and briefly degrade retrieval availability.
                     */
                    TeacherResourceDocumentResponse unchanged = markUnchangedFeishuResourceSynced(downloaded, document);
                    TeacherSourceSyncJobResponse completed = updateJob(
                            running,
                            "completed",
                            "skipped_unchanged",
                            result.savedPath().toString(),
                            result.message() + "; body checksum unchanged; vector index retained");
                    TeacherSourceSyncCheckpointResponse successCheckpoint = result.checkpoint().hasCursor()
                            ? toStoredCheckpoint(unchanged, completed, result.checkpoint(), result.failedItemsJson())
                            : null;
                    saveFeishuCheckpoint(
                            unchanged,
                            completed,
                            successCheckpoint,
                            mergeDownloadedItemsJson(result),
                            result.failedItemsJson(),
                            2);
                    return jobStore.save(completed);
                }
                resourceStore.save(downloaded);
                int feishuManifestAssets = ingestFeishuDownloadedAssetManifest(downloaded, result);
                TeacherResourceAssetService.AssetMigrationSummary assetMigration =
                        assetService.migrateDocumentAssetsToReadableNames(downloaded.tenantId(), downloaded.documentId());
                String vectorMessage = "";

                if (manifestStore != null) {
                    manifestStore.markParsed(document.tenantId(), syncRootUrl, changedItemsJson, Instant.now());
                }

                int parsedBlockCount = separatedParse == null ? blocks.size() : separatedParse.blockCount();
                if (parsedBlockCount > 0) {
                    if (separatedParse == null) {
                        blockStore.replaceActiveBlocks(document.tenantId(), document.documentId(), blocks);
                    }
                    syncSourceCatalog(document, savedSourceRoot, contentChecksum, true);
                    TeacherResourceDocumentResponse synced = markLocalResourceSynced(downloaded, contentChecksum);
                    if (manifestStore != null) {
                        manifestStore.markEmbedding(document.tenantId(), syncRootUrl, changedItemsJson, Instant.now());
                    }
                    vectorMessage = autoRebuildVectorIndex(synced, normalizedRole, normalizedSubjectId);
                    if (manifestStore != null) {
                        manifestStore.markIndexed(document.tenantId(), syncRootUrl, changedItemsJson, Instant.now());
                    }
                } else {
                    syncSourceCatalog(document, savedSourceRoot, contentChecksum, false);
                    if (manifestStore != null) {
                        // A changed attachment may be valid without producing searchable text blocks.
                        manifestStore.markIndexed(document.tenantId(), syncRootUrl, changedItemsJson, Instant.now());
                    }
                }
                TeacherSourceSyncJobResponse completed = updateJob(
                        running,
                        "completed",
                        "download_completed",
                        result.savedPath().toString(),
                        parsedBlockCount == 0
                                ? result.message() + "; no supported files parsed"
                                        + assetCountSuffix(feishuManifestAssets)
                                : result.message() + "; Parsed " + parsedBlockCount + " blocks"
                                        + assetCountSuffix(feishuManifestAssets)
                                        + "; readableAssetMigration=" + assetMigration.migratedCount()
                                        + ", legacyHashFilesRemoved=" + assetMigration.removedLegacyFileCount()
                                        + ", assetMigrationElapsedMs=" + assetMigration.elapsedMs()
                                        + aiModeSuffix(document)
                                        + vectorMessage);
                TeacherSourceSyncCheckpointResponse successCheckpoint = result.checkpoint().hasCursor()
                        ? toStoredCheckpoint(document, completed, result.checkpoint(), result.failedItemsJson())
                        : null;
                saveFeishuCheckpoint(
                        document,
                        completed,
                        successCheckpoint,
                        mergeDownloadedItemsJson(result),
                        result.failedItemsJson(),
                        2);
                return jobStore.save(completed);
            }
            assetService.markDocumentAssetsInactive(document.tenantId(), document.documentId());
            List<TeacherDocumentBlockResponse> blocks = parseResourceFiles(
                    normalizedTenantId,
                    normalizedRole,
                    normalizedSubjectId,
                    document,
                    false,
                    running);
            blockStore.replaceActiveBlocks(document.tenantId(), document.documentId(), blocks);
            TeacherResourceDocumentResponse synced = markLocalResourceSynced(document);
            String vectorMessage = autoRebuildVectorIndex(synced, normalizedRole, normalizedSubjectId);
            TeacherSourceSyncJobResponse completed = updateJob(
                    running,
                    "completed",

                    "parse_completed",
                    null,
                    "Parsed " + blocks.size() + " blocks from local source"
                            + aiModeSuffix(document)
                            + vectorMessage);
            return jobStore.save(completed);
        } catch (RuntimeException exception) {
                if (exception instanceof VectorIndexSyncException) {
                markManifestFailure(document, syncRootUrl, changedItemsJson, exception, true);
                LOGGER.warn("teacher_source_sync_failed document={} phase=vector_index_failed type={} message={}",
                        document.documentId(), exception.getClass().getSimpleName(), exception.getMessage());
                TeacherSourceSyncJobResponse failed = updateJob(
                        running,
                        "failed",
                        "vector_index_failed",
                        null,
                        exception.getMessage());
                return jobStore.save(failed);
            }
            if (feishuSource) {
                TeacherFeishuDownloadClient.FeishuDownloadCheckpoint failureCheckpoint =
                        TeacherFeishuDownloadClient.FeishuDownloadCheckpoint.empty();
                boolean retryable = false;
                TeacherSourceSyncFailureResponse failure = TeacherSourceSyncFailureResponse.none();
                if (exception instanceof TeacherFeishuDownloadException feishuException) {
                    retryable = feishuException.retryable();
                    failureCheckpoint = feishuException.checkpoint();
                    failure = feishuException.failure();
                }
                TeacherSourceSyncCheckpointResponse checkpointToSave = failureCheckpoint.hasCursor()
                        ? toStoredCheckpoint(document, running, failureCheckpoint, failedItemsJson(exception, retryable))
                        : resumeCheckpoint;
                boolean downloadFailure = exception instanceof TeacherFeishuDownloadException
                        || "[]".equals(changedItemsJson);
                String failurePhase = downloadFailure
                        ? (retryable ? "download_paused" : "download_failed")
                        : "parse_failed";
                boolean authorizationRequired = failure.authorizationUrl() != null
                        || (failure.requiredScopes() != null && !failure.requiredScopes().isEmpty());
                if (!authorizationRequired) {
                    markManifestFailure(document, syncRootUrl, changedItemsJson, exception, retryable);
                }
                LOGGER.info("teacher_source_sync_failed document={} phase={} type={} message={}",
                        document.documentId(), authorizationRequired ? "authorization_required" : failurePhase,
                        exception.getClass().getSimpleName(), exception.getMessage());
                TeacherSourceSyncJobResponse pausedOrFailed = updateJob(
                        running,
                        authorizationRequired ? "AUTH_REQUIRED" : (retryable ? "paused" : "failed"),
                        authorizationRequired ? "authorization_required" : failurePhase,
                        null,
                        exception.getMessage(),
                        failure);
                saveFeishuCheckpoint(
                        document,
                        pausedOrFailed,
                        checkpointToSave,
                        checkpointToSave == null ? "[]" : checkpointToSave.downloadedItemsJson(),
                        failedItemsJson(exception, retryable),
                        2);
                return jobStore.save(pausedOrFailed);
            }
            markManifestFailure(document, syncRootUrl, changedItemsJson, exception, false);
            LOGGER.info("teacher_source_sync_failed document={} phase=parse_failed type={} message={}",
                    document.documentId(), exception.getClass().getSimpleName(), exception.getMessage());
            TeacherSourceSyncJobResponse failed = updateJob(
                    running,
                    "failed",
                    "parse_failed",
                    null,
                    exception.getMessage());
            return jobStore.save(failed);
        }
    }

    /** Persists file-level failure/retry state without replacing the durable folder-job status. */
    private void markManifestFailure(
            TeacherResourceDocumentResponse document,
            String rootUrl,
            String changedItemsJson,
            RuntimeException exception,
            boolean retryable) {
        if (manifestStore == null) {
            return;
        }
        try {
            Instant now = Instant.now();
            Instant nextRetryAt = retryable ? now.plusSeconds(FILE_RETRY_DELAY_SECONDS) : null;
            if (changedItemsJson == null || changedItemsJson.equals("[]")) {
                manifestStore.markRootFailed(document.tenantId(), rootUrl, exception.getMessage(), nextRetryAt, now);
            } else {
                manifestStore.markFailed(
                        document.tenantId(), rootUrl, changedItemsJson, exception.getMessage(), nextRetryAt, now);
            }
        } catch (RuntimeException manifestException) {
            LOGGER.warn("teacher_source_sync_manifest_failure_recording_failed document={} type={}",
                    document.documentId(), manifestException.getClass().getSimpleName());
        }
    }

    /**
     * Resumes a paused synchronization job after verifying that a durable checkpoint exists.
     *
     * @param tenantId tenant id
     * @param viewerRole backend-resolved viewer role
     * @param viewerSubjectId backend-resolved viewer subject id
     * @param documentId source document id
     * @param jobId sync job id
     * @return resumed job state after execution
     */
    public TeacherSourceSyncJobResponse resume(
            String tenantId,
            String viewerRole,
            String viewerSubjectId,
            String documentId,
            String jobId) {
        String normalizedTenantId = requireText(tenantId, "tenantId is required");
        String normalizedRole = requireText(viewerRole, "viewerRole is required").toLowerCase(Locale.ROOT);
        String normalizedSubjectId = requireText(viewerSubjectId, "viewerSubjectId is required");
        requireTeacherOrAdmin(normalizedRole);
        TeacherResourceDocumentResponse document = requireVisibleDocument(
                normalizedTenantId,
                normalizedRole,
                normalizedSubjectId,
                documentId);
        TeacherSourceSyncJobResponse job = requireJob(document.tenantId(), document.documentId(), jobId);
        if (!"paused".equalsIgnoreCase(textOrDefault(job.status(), ""))
                && !"AUTH_REQUIRED".equalsIgnoreCase(textOrDefault(job.status(), ""))) {
            throw new IllegalArgumentException("Only paused or authorization-required source sync jobs can be resumed");
        }
        TeacherSourceSyncCheckpointResponse checkpoint = checkpointStore.findByJobId(document.tenantId(), job.jobId())
                .orElseThrow(() -> new IllegalArgumentException("Source sync checkpoint not found: " + job.jobId()));
        return executeWithCheckpoint(
                normalizedTenantId,
                normalizedRole,
                normalizedSubjectId,
                document.documentId(),
                job.jobId(),
                checkpoint);
    }

    /**
     * Saves a Feishu checkpoint snapshot for start, success, paused, or failed states.
     */
    private void saveFeishuCheckpoint(
            TeacherResourceDocumentResponse document,
            TeacherSourceSyncJobResponse job,
            String downloadedItemsJson,
            String failedItemsJson,
            int cursorVersion) {
        saveFeishuCheckpoint(document, job, null, downloadedItemsJson, failedItemsJson, cursorVersion);
    }

    /**
     * Saves a Feishu checkpoint while preserving an existing provider cursor during resume attempts.
     */
    private void saveFeishuCheckpoint(
            TeacherResourceDocumentResponse document,
            TeacherSourceSyncJobResponse job,
            TeacherSourceSyncCheckpointResponse previousCheckpoint,
            String downloadedItemsJson,
            String failedItemsJson,
            int cursorVersion) {
        String rootToken = extractFeishuToken(requireText(document.originalUrl(), "Feishu resource originalUrl is required"));
        String currentFolderToken = previousCheckpoint == null
                ? rootToken
                : textOrDefault(previousCheckpoint.currentFolderToken(), rootToken);
        String currentPath = previousCheckpoint == null
                ? textOrDefault(document.title(), "Feishu source")
                : textOrDefault(previousCheckpoint.currentPath(), textOrDefault(document.title(), "Feishu source"));
        String pageToken = previousCheckpoint == null ? null : previousCheckpoint.pageToken();
        String visitedFolderTokensJson = previousCheckpoint == null
                ? (rootToken.isBlank() ? "[]" : "[\"" + escapeJson(rootToken) + "\"]")
                : jsonOrEmptyArray(previousCheckpoint.visitedFolderTokensJson());
        checkpointStore.save(new TeacherSourceSyncCheckpointResponse(
                job.jobId(),
                document.tenantId(),
                document.documentId(),
                rootToken,
                currentFolderToken,
                currentPath,
                pageToken,
                visitedFolderTokensJson,
                jsonOrEmptyArray(downloadedItemsJson),
                jsonOrEmptyArray(failedItemsJson),
                cursorVersion,
                Instant.now().toString()));
    }
    // Delegates deterministic sync/parse policy to TeacherSourceSyncPolicy.
    static TeacherFeishuDownloadClient.FeishuDownloadCheckpoint toDownloadCheckpoint(TeacherSourceSyncCheckpointResponse checkpoint) { return TeacherSourceSyncPolicy.toDownloadCheckpoint(checkpoint); }
    // Delegates deterministic sync/parse policy to TeacherSourceSyncPolicy.
    static TeacherSourceSyncCheckpointResponse toStoredCheckpoint(TeacherResourceDocumentResponse document, TeacherSourceSyncJobResponse job, TeacherFeishuDownloadClient.FeishuDownloadCheckpoint checkpoint, String failedItemsJson) { return TeacherSourceSyncPolicy.toStoredCheckpoint(document, job, checkpoint, failedItemsJson); }
    // Delegates deterministic sync/parse policy to TeacherSourceSyncPolicy.
    static String downloadedItemsJson(TeacherFeishuDownloadClient.FeishuDownloadResult result) { return TeacherSourceSyncPolicy.downloadedItemsJson(result); }
    // Delegates deterministic sync/parse policy to TeacherSourceSyncPolicy.
    static String mergeDownloadedItemsJson(TeacherFeishuDownloadClient.FeishuDownloadResult result) { return TeacherSourceSyncPolicy.mergeDownloadedItemsJson(result); }
    // Delegates deterministic sync/parse policy to TeacherSourceSyncPolicy.
    static String failedItemsJson(RuntimeException exception, boolean retryable) { return TeacherSourceSyncPolicy.failedItemsJson(exception, retryable); }
    // Delegates deterministic sync/parse policy to TeacherSourceSyncPolicy.
    static String extractFeishuToken(String url) { return TeacherSourceSyncPolicy.extractFeishuToken(url); }
    // Delegates deterministic sync/parse policy to TeacherSourceSyncPolicy.
    static String escapeJson(String value) { return TeacherSourceSyncPolicy.escapeJson(value); }
    // Delegates deterministic sync/parse policy to TeacherSourceSyncPolicy.
    static String jsonOrEmptyArray(String value) { return TeacherSourceSyncPolicy.jsonOrEmptyArray(value); }

    /**
     * Makes AI mode observable without claiming that every image is a formula. Native OMML is always extracted locally;
     * AI mode adds an opt-in visual attempt for source-linked images and leaves uncertain ones as original assets.
     */
    private String aiModeSuffix(TeacherResourceDocumentResponse document) {
        if (!"AI".equalsIgnoreCase(textOrDefault(document.parseMode(), "TEXT"))) {
            return "";
        }
        return formulaRecognitionProperties.enabled()
                ? "; AI formula recognition attempted for eligible images; uncertain images retained as original assets"
                : "; AI formula recognition is disabled by deployment configuration; kept TEXT extraction";
    }
    // Delegates deterministic sync/parse policy to TeacherSourceSyncPolicy.
    static String assetCountSuffix(int assetCount) { return TeacherSourceSyncPolicy.assetCountSuffix(assetCount); }

    /**

     * Persists Feishu-native image/file attachments carried by the downloader checkpoint. Exported DOCX/PDF/Markdown
     * rows remain documents and are parsed by parseResourceFiles; only rows marked as image/attachment become assets
     * here so we do not double-count document exports.
     */
    private int ingestFeishuDownloadedAssetManifest(
            TeacherResourceDocumentResponse document,
            TeacherFeishuDownloadClient.FeishuDownloadResult result) {
        JsonNode items;
        try {
            items = OBJECT_MAPPER.readTree(jsonOrEmptyArray(result.downloadedItemsJson()));
        } catch (JsonProcessingException exception) {
            return 0;
        }
        if (!items.isArray()) {
            return 0;
        }
        int count = 0;
        for (JsonNode item : items) {
            String assetKind = textOrDefault(item.path("assetKind").asText(""), "");
            if (!"image".equalsIgnoreCase(assetKind) && !"attachment".equalsIgnoreCase(assetKind)) {
                continue;
            }
            String relativePath = textOrDefault(item.path("relativePath").asText(""), "");
            if (relativePath.isBlank()) {
                continue;
            }
            if ("image".equalsIgnoreCase(assetKind) && isDocumentScopedMarkdownImage(relativePath)) {
                // The Markdown parser owns block binding for document-scoped preview images. The manifest remains
                // download audit data only, preventing the same binary from being persisted once per path and again per block.
                continue;
            }
            Path file = resolveDownloadedItemPath(result.savedPath(), relativePath);
            if (!Files.isRegularFile(file)) {
                continue;
            }
            try {
                /*
                 * Markdown image blocks use the same relative provider id that the worker writes in its manifest.
                 * Keeping this id stable makes parser extraction and manifest ingestion converge on one active asset
                 * instead of leaving block.imageRefs pointing at an inactive duplicate after a resync.
                 */
                String providerAssetId = textOrDefault(
                        item.path("providerAssetId").asText(""),
                        "feishu:" + textOrDefault(item.path("token").asText(""), relativePath));
                Optional<com.doob.mathagent.teacher.vo.TeacherResourceAssetResponse> saved =
                        assetService.saveExtractedAsset(
                                document,
                                relativePath.replace('\\', '/'),
                                null,
                                providerAssetId,
                                Files.readAllBytes(file),
                                textOrDefault(item.path("mimeType").asText(""), "application/octet-stream"),
                                "image".equalsIgnoreCase(assetKind));
                if (saved.isPresent()) {
                    count += 1;
                }
            } catch (IOException exception) {
                throw new IllegalStateException("Failed to ingest Feishu asset manifest file: " + file, exception);
            }
        }
        return count;
    }
    private static boolean isDocumentScopedMarkdownImage(String relativePath) {
        String normalized = textOrDefault(relativePath, "").replace('\\', '/');
        return normalized.startsWith("IMAJES/") || normalized.contains("/IMAJES/");
    }

    // Delegates deterministic sync/parse policy to TeacherSourceSyncPolicy.
    static Path resolveDownloadedItemPath(Path savedPath, String relativePath) { return TeacherSourceSyncPolicy.resolveDownloadedItemPath(savedPath, relativePath); }

    /**
     * Marks a local resource as parsed while keeping embedding/index rebuild pending.
     */
    private TeacherResourceDocumentResponse markLocalResourceSynced(TeacherResourceDocumentResponse document) {
        return markLocalResourceSynced(document, document.contentChecksum());
    }

    private TeacherResourceDocumentResponse markLocalResourceSynced(
            TeacherResourceDocumentResponse document,
            String contentChecksum) {
        TeacherResourceDocumentResponse synced = new TeacherResourceDocumentResponse(
                document.documentId(),
                document.tenantId(),
                document.ownerSubjectId(),
                document.sourceType(),
                document.title(),
                document.originalUrl(),
                document.localPath(),
                document.permissionScope(),
                "synced",
                "parsed",
                "pending",
                "waiting_rebuild",
                document.feishuExportFormat(),
                document.previewFiles(),
                document.parseMode(),
                document.providerRevision(),
                contentChecksum,
                document.sourceIdentity());
        resourceStore.save(synced);
        return synced;
    }

    private void syncSourceCatalog(
            TeacherResourceDocumentResponse document,
            Path savedSourceRoot,
            String checksum,
            boolean requireTextSource) {
        if (sourceFileReader == null) {
            throw new IllegalStateException("Feishu source reader is not configured");
        }
        if (requireTextSource && syncProperties.isValidSourceRoot(savedSourceRoot)) {
            sourceFileReader.register(document.tenantId(), document.documentId(), savedSourceRoot, checksum);
        } else {
            sourceFileReader.unregister(document.tenantId(), document.documentId());
        }
    }

    /**
     * Persists provider metadata from an unchanged Feishu download without invalidating vectors that were already
     * verified for the same parsed body. The checksum comparison above is the proof that retaining this index is safe.
     */
    private TeacherResourceDocumentResponse markUnchangedFeishuResourceSynced(
            TeacherResourceDocumentResponse downloaded,
            TeacherResourceDocumentResponse previouslyIndexed) {
        TeacherResourceDocumentResponse synced = new TeacherResourceDocumentResponse(
                downloaded.documentId(),
                downloaded.tenantId(),
                downloaded.ownerSubjectId(),
                downloaded.sourceType(),
                downloaded.title(),
                downloaded.originalUrl(),
                downloaded.localPath(),
                downloaded.permissionScope(),
                "synced",
                "parsed",
                previouslyIndexed.embeddingStatus(),
                previouslyIndexed.indexStatus(),
                downloaded.feishuExportFormat(),
                downloaded.previewFiles(),
                downloaded.parseMode(),
                downloaded.providerRevision(),
                downloaded.contentChecksum(),
                downloaded.sourceIdentity());
        resourceStore.save(synced);
        return synced;
    }
    // Delegates deterministic sync/parse policy to TeacherSourceSyncPolicy.
    static boolean hasVerifiedVectorReadiness(TeacherResourceDocumentResponse document) { return TeacherSourceSyncPolicy.hasVerifiedVectorReadiness(document); }
    // Delegates deterministic sync/parse policy to TeacherSourceSyncPolicy.
    static TeacherResourceDocumentResponse withSyncFingerprint(TeacherResourceDocumentResponse document, String contentChecksum) { return TeacherSourceSyncPolicy.withSyncFingerprint(document, contentChecksum); }
    // Delegates deterministic sync/parse policy to TeacherSourceSyncPolicy.
    static String semanticContentChecksum(List<TeacherDocumentBlockResponse> blocks, String providerTitle) { return TeacherSourceSyncPolicy.semanticContentChecksum(blocks, providerTitle); }
    // Delegates deterministic sync/parse policy to TeacherSourceSyncPolicy.
    static String removeLeadingProviderTitle(String value, String providerTitle) { return TeacherSourceSyncPolicy.removeLeadingProviderTitle(value, providerTitle); }

    /**

     * Rebuilds vector indexing after parsing. Sync must fail instead of pretending to complete when Milvus or
     * embeddings are unavailable.
     */
    private String autoRebuildVectorIndex(
            TeacherResourceDocumentResponse document,
            String viewerRole,
            String viewerSubjectId) {
        VectorIndexRebuildResponse response;
        try {
            response = vectorIndexService.rebuildTeacherResource(
                    document.tenantId(),
                    viewerRole,
                    viewerSubjectId,
                    document.documentId());
        } catch (RuntimeException exception) {
            throw new VectorIndexSyncException("Vector index rebuild failed: " + exception.getMessage(), exception);
        }
        if ("failed".equalsIgnoreCase(response.status())) {
            throw new VectorIndexSyncException(
                    "Vector index rebuild failed: " + textOrDefault(response.message(), "unknown error"),
                    null);
        }
        return "; Vector index " + response.status() + ": " + textOrDefault(response.message(), "")
                + "; deletedExistingCount=" + response.deletedExistingCount()
                + ", embeddingElapsedMs=" + response.embeddingElapsedMs()
                + ", milvusDeleteElapsedMs=" + response.milvusDeleteElapsedMs()
                + ", milvusUpsertElapsedMs=" + response.milvusUpsertElapsedMs();
    }

    static final class VectorIndexSyncException extends RuntimeException {

        private VectorIndexSyncException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * Detects historical Markdown image rows that were persisted before the original row and logical asset path became
     * mandatory. The next authorized no-op Feishu download reparses only the already staged source; it never guesses
     * an image path from a filename or alters the teacher's original files.
     */
    private boolean requiresImageBindingBackfill(String tenantId, String rootDocumentId) {
        String afterFileDocumentId = "";
        while (true) {
            List<TeacherResourceStore.TeacherFileDocument> files = resourceStore.listFileDocumentsForIndexing(
                    tenantId, rootDocumentId, 128, afterFileDocumentId);
            if (files.isEmpty()) {
                return false;
            }
            for (TeacherResourceStore.TeacherFileDocument file : files) {
                for (TeacherDocumentBlockResponse block : blockStore.listByDocument(tenantId, file.documentId())) {
                    String rawText = textOrDefault(block.rawText(), "").strip();
                    if (MARKDOWN_IMAGE_PATTERN.matcher(rawText).find()
                            && (block.imageRefs() == null || block.imageRefs().isBlank() || block.imageRefs().equals("[]"))) {
                        return true;
                    }
                    if ("[Markdown image block; no extractable text]".equals(rawText)
                            && hasLegacyMarkdownImageReference(block.imageRefs())) {
                        return true;
                    }
                }
            }
            afterFileDocumentId = files.getLast().documentId();
        }
    }

    /** Returns whether an old image payload lacks the exact Markdown row and logical path required for authorization. */
    private static boolean hasLegacyMarkdownImageReference(String imageRefs) {
        if (imageRefs == null || imageRefs.isBlank() || imageRefs.equals("[]")) {
            return false;
        }
        try {
            JsonNode refs = OBJECT_MAPPER.readTree(imageRefs);
            if (refs == null || !refs.isArray()) {
                return false;
            }
            for (JsonNode ref : refs) {
                if (!ref.isObject()) {
                    continue;
                }
                boolean identifiesImage = !ref.path("imageReference").asText("").isBlank()
                        || !ref.path("assetId").asText("").isBlank();
                if (identifiesImage && (ref.path("markdownLine").asText("").isBlank()
                        || ref.path("logicalPath").asText("").isBlank())) {
                    return true;
                }
            }
            return false;
        } catch (JsonProcessingException exception) {
            return false;
        }
    }

    /**
     * Parses and persists one ROOT as independent FILE documents when the backing store supports the durable model.
     * The compatibility return value is used only by legacy stores/tests that do not expose FILE rows.
     */
    private FileSeparatedParseResult parseResourceFilesIntoFileDocuments(
            String tenantId,
            String viewerRole,
            String viewerSubjectId,
            TeacherResourceDocumentResponse document,
            boolean allowNoSupportedFiles,
            TeacherSourceSyncJobResponse running,
            String providerItemsJson) {
        Path root = Path.of(textOrDefault(document.localPath(), ""));
        if (!Files.exists(root)) {
            throw new IllegalArgumentException("Local resource path does not exist: " + root);
        }
        FormulaVisionBudget formulaVisionBudget = FormulaVisionBudget.forDocument(document, formulaRecognitionProperties);
        int totalFiles = 0;
        int[] totalBlocks = {0};
        MessageDigest rootDigest = digest();
        Stream<Path> files = streamSupportedFiles(root, feishuFileExtension(document));
        java.util.Iterator<Path> iterator = files.iterator();
        while (iterator.hasNext()) {
                Path file = iterator.next();
                totalFiles += 1;
                String relativePath = root.equals(file)
                        ? file.getFileName().toString()
                        : root.relativize(file).toString();
                String safeRelativePath = relativePath.replace('\\', '/');
                String providerItemId = providerItemIdForPath(providerItemsJson, safeRelativePath);
                if (providerItemId.isBlank() && manifestStore != null) {
                    // Unchanged files are absent from changedItemsJson. Resolve their stable provider identity from the
                    // persisted manifest before falling back to the ingestion-bound path hash.
                    providerItemId = textOrDefault(
                            manifestStore.providerItemId(tenantId, document.documentId(), safeRelativePath), "");
                }
                String checksum = fileChecksum(file);
                TeacherResourceStore.TeacherFileDocument fileDocument = resourceStore.findOrCreateFileDocument(
                        document, providerItemId, safeRelativePath, checksum, FILE_SPLIT_FINGERPRINT);
                if (fileDocument == null || fileDocument.documentId().isBlank()) {
                    throw new IllegalStateException("FILE document persistence is unavailable for source path: " + safeRelativePath);
                }
                blockStore.beginFileReplacement(tenantId, fileDocument.documentId());
                persistParseProgress(running, "parse_file_started", safeRelativePath, totalBlocks[0], -1);
                final int[] order = {0};
                consumeFileBlocks(
                        tenantId,
                        viewerRole,
                        viewerSubjectId,
                        fileDocument.document(),
                        file,
                        safeRelativePath,
                        formulaVisionBudget,
                        (parsed, formulas) -> {
                            TeacherDocumentBlockResponse block = withDocumentAndOrder(
                                    toBlock(tenantId, viewerRole, viewerSubjectId, fileDocument.document(),
                                            safeRelativePath, parsed, order[0], formulas),
                                    fileDocument.documentId(),
                                    order[0]++);
                            blockStore.replaceActiveBlockBatch(
                                    tenantId, fileDocument.documentId(), List.of(block));
                            totalBlocks[0] += 1;
                        });
                blockStore.completeFileReplacement(tenantId, fileDocument.documentId());
                resourceStore.save(markLocalResourceSynced(fileDocument.document(), checksum));
                rootDigest.update(safeRelativePath.getBytes(StandardCharsets.UTF_8));
                rootDigest.update(checksum.getBytes(StandardCharsets.UTF_8));
                persistParseProgress(running, "parse_file_completed", safeRelativePath, totalBlocks[0], -1);
            }
            files.close();
        if (totalFiles == 0 && !allowNoSupportedFiles) {
            throw new IllegalArgumentException("Feishu staging path contains no files matching export format "
                    + feishuFileExtension(document) + ": " + root);
        }
        return new FileSeparatedParseResult(totalFiles, totalBlocks[0], HexFormat.of().formatHex(rootDigest.digest()));
    }

    /**
     * Removes FILE rows absent from a successful provider discovery without loading their blocks. Cleanup is deliberately
     * ordered vector -> block -> asset -> source row so a failed dependent cleanup leaves the FILE visible for retry.
     */
    private void archiveMissingFileDocuments(
            String tenantId,
            TeacherResourceDocumentResponse rootDocument,
            String discoveredItemsJson) {
        List<String> activeIdentityHashes = new ArrayList<>();
        try {
            JsonNode discovered = OBJECT_MAPPER.readTree(jsonOrEmptyArray(discoveredItemsJson));
            if (!discovered.isArray() || discovered.isEmpty()) {
                // An empty provider manifest is not proof that every persisted file disappeared. A failed,
                // incomplete, or legacy downloader response must never archive the physical document set.
                LOGGER.warn("teacher_source_sync_archive_skipped root={} reason=empty_discovery_manifest",
                        rootDocument.documentId());
                return;
            }
            for (JsonNode item : discovered) {
                String providerItemId = textOrDefault(item.path("token").asText(""), "");
                String sourcePath = textOrDefault(
                        item.path("relativePath").asText(item.path("path").asText("")), "")
                        .replace('\\', '/');
                if (providerItemId.isBlank() && sourcePath.isBlank()) {
                    continue;
                }
                if (!providerItemId.isBlank()) {
                    activeIdentityHashes.add(fileIdentityHash(rootDocument.documentId(), providerItemId, sourcePath));
                }
                if (!sourcePath.isBlank()) {
                    // Keep the path identity as a migration fallback for rows created before provider tokens were
                    // available, and for manifests whose provider path omits the exported filename extension.
                    activeIdentityHashes.add(fileIdentityHash(rootDocument.documentId(), "", sourcePath));
                }
            }
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Feishu discovery manifest is invalid", exception);
        }
        activeIdentityHashes = activeIdentityHashes.stream().filter(value -> value != null && !value.isBlank()).distinct().toList();
        if (activeIdentityHashes.isEmpty()) {
            LOGGER.warn("teacher_source_sync_archive_skipped root={} reason=no_file_identities",
                    rootDocument.documentId());
            return;
        }
        String afterFileDocumentId = "";
        boolean deletedAny = false;
        while (true) {
            List<TeacherResourceStore.TeacherFileDocument> missing = resourceStore.listMissingFileDocuments(
                    tenantId,
                    rootDocument.documentId(),
                    activeIdentityHashes,
                    afterFileDocumentId,
                    1);
            if (missing.isEmpty()) {
                if (deletedAny) {
                    vectorIndexService.flushTeacherResourceVectors();
                }
                return;
            }
            for (TeacherResourceStore.TeacherFileDocument file : missing) {
                vectorIndexService.deleteTeacherResourceVectors(tenantId, file.documentId(), false);
                vectorIndexService.purgeTeacherResourceContent(tenantId, file.documentId());
                assetService.purgeDocumentAssets(tenantId, file.documentId());
                resourceStore.archiveFileDocument(tenantId, file.documentId());
                afterFileDocumentId = file.documentId();
                deletedAny = true;
            }
        }
    }

    private static String mergeProviderFileItems(TeacherFeishuDownloadClient.FeishuDownloadResult result) {
        ArrayNode merged = OBJECT_MAPPER.createArrayNode();
        appendJsonItems(merged, result.changedItemsJson());
        appendJsonItems(merged, result.unchangedItemsJson());
        try {
            return OBJECT_MAPPER.writeValueAsString(merged);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Failed to serialize Feishu provider file manifest", exception);
        }
    }

    private static void appendJsonItems(ArrayNode target, String json) {
        try {
            JsonNode node = OBJECT_MAPPER.readTree(jsonOrEmptyArray(json));
            if (node != null && node.isArray()) {
                node.forEach(target::add);
            }
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Feishu provider file manifest is invalid", exception);
        }
    }

    private static String fileIdentityHash(String rootDocumentId, String providerItemId, String sourcePath) {
        String identity = providerItemId == null || providerItemId.isBlank()
                ? rootDocumentId + "\u001f" + sourcePath
                : providerItemId;
        return TeacherResourceSourceIdentity.hash(identity);
    }

    private void consumeFileBlocks(
            String tenantId,
            String viewerRole,
            String viewerSubjectId,
            TeacherResourceDocumentResponse document,
            Path file,
            String relativePath,
            FormulaVisionBudget formulaVisionBudget,
            BiConsumer<ParsedBlock, List<FormulaReference>> consumer) {
        TeacherSourceSyncParsingPolicy.consumeFileBlocks(
                file,
                formulaVisionBudget,
                (parsed, formulas) -> consumer.accept(parsed, formulas));
    }

    private List<TeacherDocumentBlockResponse> parseSingleFile(
            String tenantId,
            String viewerRole,
            String viewerSubjectId,
            TeacherResourceDocumentResponse document,
            Path file,
            String relativePath,
            FormulaVisionBudget formulaVisionBudget) {
        String lowerName = file.getFileName().toString().toLowerCase(Locale.ROOT);
        if (lowerName.endsWith(".pdf")) {
            return parsePdfBlocksIncrementally(
                    tenantId, viewerRole, viewerSubjectId, document, file, relativePath, 0, formulaVisionBudget);
        }
        List<ParsedBlock> parsedBlocks = new ArrayList<>(parseFileBlocks(file));
        if ("AI".equalsIgnoreCase(textOrDefault(document.parseMode(), "TEXT")) && lowerName.endsWith(".docx")) {
            parsedBlocks.addAll(parseRenderedDocxPages(file));
        }
        boolean hasRenderedDocxPages = lowerName.endsWith(".docx")
                && parsedBlocks.stream().anyMatch(parsed -> parsed.pageNo() != null && !parsed.assets().isEmpty());
        List<List<FormulaReference>> pageFormulas = hasRenderedDocxPages
                ? emptyFormulaBatches(parsedBlocks.size())
                : recognizePdfPageBatches(document, parsedBlocks, formulaVisionBudget);
        List<TeacherDocumentBlockResponse> result = new ArrayList<>(parsedBlocks.size());
        for (int index = 0; index < parsedBlocks.size(); index += 1) {
            result.add(toBlock(
                    tenantId, viewerRole, viewerSubjectId, document, relativePath, parsedBlocks.get(index), index,
                    pageFormulas.get(index)));
        }
        return result;
    }

    private static TeacherDocumentBlockResponse withDocumentAndOrder(
            TeacherDocumentBlockResponse block, String documentId, int order) {
        return new TeacherDocumentBlockResponse(
                block.blockId(), documentId, block.externalBlockId(), block.blockType(), order, block.chapter(),
                block.section(), block.pageNo(), block.printedPageNo(), block.sourcePath(), block.blockRole(),
                block.rawText(), block.normalizedText(), block.imageRefs(), block.formulaRefs(), block.graphNodeIdsJson(),
                block.graphTagNamesJson(), block.checksum(), block.confidence(), block.status());
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static String fileChecksum(Path file) {
        MessageDigest digest = digest();
        try (var input = Files.newInputStream(file)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read > 0) digest.update(buffer, 0, read);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (IOException exception) {
            throw new IllegalArgumentException("Failed to checksum source file: " + file, exception);
        }
    }

    private static String providerItemIdForPath(String itemsJson, String relativePath) {
        if (itemsJson == null || itemsJson.isBlank()) return "";
        try {
            JsonNode items = OBJECT_MAPPER.readTree(itemsJson);
            if (!items.isArray()) return "";
            String normalized = normalizeProviderPath(relativePath);
            String withoutExtension = stripProviderExportExtension(normalized);
            for (JsonNode item : items) {
                String token = textOrDefault(item.path("token").asText(""), "");
                if (token.isBlank()) continue;
                String itemRelativePath = normalizeProviderPath(item.path("relativePath").asText(""));
                String itemPath = normalizeProviderPath(item.path("path").asText(""));
                if (providerPathMatches(normalized, withoutExtension, itemRelativePath)
                        || providerPathMatches(normalized, withoutExtension, itemPath)) {
                    return token;
                }
            }
        } catch (JsonProcessingException ignored) {
            // The provider manifest is advisory; the durable path hash remains the fallback identity.
        }
        return "";
    }

    private static boolean providerPathMatches(String normalized, String withoutExtension, String candidate) {
        if (candidate.isBlank()) return false;
        String candidateWithoutExtension = stripProviderExportExtension(candidate);
        return normalized.equals(candidate)
                || withoutExtension.equals(candidateWithoutExtension)
                || normalized.endsWith("/" + candidate)
                || withoutExtension.endsWith("/" + candidateWithoutExtension)
                || candidate.endsWith("/" + normalized)
                || candidateWithoutExtension.endsWith("/" + withoutExtension);
    }

    private static String normalizeProviderPath(String value) {
        return value == null ? "" : value.replace('\\', '/').strip().replaceAll("^/+|/+$", "");
    }

    private static String stripProviderExportExtension(String path) {
        int slash = path.lastIndexOf('/');
        int dot = path.lastIndexOf('.');
        return dot > slash ? path.substring(0, dot) : path;
    }

    /** Legacy root-level parser retained only for non-FILE test stores and old local callers. */
    private List<TeacherDocumentBlockResponse> parseResourceFiles(
            String tenantId,
            String viewerRole,
            String viewerSubjectId,
            TeacherResourceDocumentResponse document,
            boolean allowNoSupportedFiles,
            TeacherSourceSyncJobResponse runningJob) {
        Path root = Path.of(textOrDefault(document.localPath(), ""));
        if (!Files.exists(root)) throw new IllegalArgumentException("Local resource path does not exist: " + root);
        List<TeacherDocumentBlockResponse> blocks = new ArrayList<>();
        try (Stream<Path> files = streamSupportedFiles(root)) {
            files.forEach(file -> {
                String relativePath = root.equals(file) ? file.getFileName().toString() : root.relativize(file).toString();
                List<TeacherDocumentBlockResponse> parsed = parseSingleFile(
                        tenantId, viewerRole, viewerSubjectId, document, file, relativePath.replace('\\', '/'),
                        FormulaVisionBudget.forDocument(document, formulaRecognitionProperties));
                blocks.addAll(parsed);
            });
        }
        if (blocks.isEmpty() && !allowNoSupportedFiles) {
            throw new IllegalArgumentException("Local resource path contains no supported source files: " + root);
        }
        return blocks;
    }

    private void persistParseProgress(
            TeacherSourceSyncJobResponse runningJob,
            String phase,
            String relativePath,
            int parsedBlocks,
            int totalFiles) {
        if (runningJob == null || jobStore == null) {
            return;
        }
        String message = "Parsing " + relativePath + " (" + parsedBlocks + " blocks, " + totalFiles + " files)";
        jobStore.save(updateJob(runningJob, "running", phase, null, message));
    }

    /** Returns one independent mutable formula list per parsed block when page transcription owns vision work. */
    static List<List<FormulaReference>> emptyFormulaBatches(int blockCount) {
        List<List<FormulaReference>> batches = new ArrayList<>();
        for (int index = 0; index < blockCount; index += 1) {
            batches.add(new ArrayList<>());
        }
        return batches;
    }

    /**
     * Renders and persists PDF pages in the same bounded groups used by page-vision recognition.
     *
     * <p>Keeping only one group in memory is essential for real scanned courseware: each page is still stored as the
     * original-color PNG asset and remains available through the normal permission gate, but a large source cannot
     * exhaust the backend before its first block has been written.</p>
     */
    private List<TeacherDocumentBlockResponse> parsePdfBlocksIncrementally(
            String tenantId,
            String viewerRole,
            String viewerSubjectId,
            TeacherResourceDocumentResponse resourceDocument,
            Path file,
            String relativePath,
            int firstOrder,
            FormulaVisionBudget formulaVisionBudget) {
        List<TeacherDocumentBlockResponse> result = new ArrayList<>();
        String chapter = stripExtension(file.getFileName().toString());
        int batchSize = formulaRecognitionProperties.normalizedPagesPerRequest();
        try (PDDocument pdf = Loader.loadPDF(file.toFile())) {
            PDFTextStripper stripper = new PDFTextStripper();
            PDFRenderer renderer = new PDFRenderer(pdf);
            List<ParsedBlock> batch = new ArrayList<>(batchSize);
            for (int page = 1; page <= pdf.getNumberOfPages(); page += 1) {
                stripper.setStartPage(page);
                stripper.setEndPage(page);
                String text = textOrDefault(stripper.getText(pdf), "");
                byte[] rendered = renderPdfPageAsPng(file, page, renderer);
                TeacherResourceAssetService.ValidatedAsset validated = TeacherResourceAssetService.validateImageBytes(
                        "parser", relativePath, "pdf-page:" + page, rendered, "image/png");
                batch.add(new ParsedBlock(
                        chapter,
                        null,
                        page,
                        text.isBlank() ? "[PDF page image; no extractable text]" : text,
                        List.of(new PendingAsset("pdf-page:" + page, validated.bytes(), validated.mimeType())),
                        List.of()));
                if (batch.size() == batchSize || page == pdf.getNumberOfPages()) {
                    List<List<FormulaReference>> formulas =
                            recognizePdfPageBatches(resourceDocument, batch, formulaVisionBudget);
                    for (int index = 0; index < batch.size(); index += 1) {
                        // `toBlock` first persists the same rendered PNG, then re-opens it through the tenant/role/
                        // owner permission boundary before it can call the visual reader. Keeping that order is what
                        // prevents a temporary local page or a stale pre-sync file from becoming source-of-record text.
                        ParsedBlock pageWithVisibleText = batch.get(index);
                        result.add(toBlock(
                                tenantId,
                                viewerRole,
                                viewerSubjectId,
                                resourceDocument,
                                relativePath,
                                pageWithVisibleText,
                                firstOrder + result.size(),
                                formulas.get(index)));
                    }
                    // `toBlock` has persisted the binary. Clearing the list releases this page group's byte arrays.
                    batch.clear();
                }
            }
        } catch (IOException exception) {
            throw new IllegalArgumentException("Failed to parse PDF resource file: " + file, exception);
        }
        return result;
    }
    // Delegates deterministic file/parse policy to TeacherSourceSyncParsingPolicy.
    static byte[] renderPdfPageAsPng(Path pdf, int pageNo, PDFRenderer fallbackRenderer) throws IOException { return TeacherSourceSyncParsingPolicy.renderPdfPageAsPng(pdf, pageNo, fallbackRenderer); }
    // Delegates deterministic file/parse policy to TeacherSourceSyncParsingPolicy.
    static Optional<byte[]> tryRenderPdfPageWithNativeRenderer(Path pdf, int pageNo) { return TeacherSourceSyncParsingPolicy.tryRenderPdfPageWithNativeRenderer(pdf, pageNo); }
    // Delegates deterministic file/parse policy to TeacherSourceSyncParsingPolicy.
    static int pdfPageRenderDpi() { return TeacherSourceSyncParsingPolicy.pdfPageRenderDpi(); }
    private static String feishuFileExtension(TeacherResourceDocumentResponse document) {
        String format = textOrDefault(document.feishuExportFormat(), "md").toLowerCase(Locale.ROOT);
        return switch (format) {
            case "md" -> ".md";
            case "docx" -> ".docx";
            case "pdf" -> ".pdf";
            default -> throw new IllegalArgumentException("Unsupported Feishu export format: " + format);
        };
    }

    // Delegates deterministic file/parse policy to TeacherSourceSyncParsingPolicy.
    static Stream<Path> streamSupportedFiles(Path root) { return TeacherSourceSyncParsingPolicy.streamSupportedFiles(root); }
    // Delegates deterministic file/parse policy to TeacherSourceSyncParsingPolicy.
    static Stream<Path> streamSupportedFiles(Path root, String requiredExtension) { return TeacherSourceSyncParsingPolicy.streamSupportedFiles(root, requiredExtension); }
    // Delegates deterministic file/parse policy to TeacherSourceSyncParsingPolicy.
    static boolean isSupportedFile(Path file) { return TeacherSourceSyncParsingPolicy.isSupportedFile(file); }
    // Delegates deterministic file/parse policy to TeacherSourceSyncParsingPolicy.
    static String readUtf8(Path file) { return TeacherSourceSyncParsingPolicy.readUtf8(file); }
    // Delegates deterministic file/parse policy to TeacherSourceSyncParsingPolicy.
    static List<ParsedBlock> parseFileBlocks(Path file) { return TeacherSourceSyncParsingPolicy.parseFileBlocks(file); }
    // Delegates deterministic file/parse policy to TeacherSourceSyncParsingPolicy.
    static List<ParsedBlock> parseTextBlocks(String text, Path file) { return TeacherSourceSyncParsingPolicy.parseTextBlocks(text, file); }
    // Delegates deterministic file/parse policy to TeacherSourceSyncParsingPolicy.
    static void flushBlock(List<ParsedBlock> blocks, String chapter, String section, Integer pageNo, StringBuilder current, List<PendingAsset> currentAssets) { TeacherSourceSyncParsingPolicy.flushBlock(blocks, chapter, section, pageNo, current, currentAssets); }
    // Delegates deterministic file/parse policy to TeacherSourceSyncParsingPolicy.
    static Integer pageNumberFromSection(String section) { return TeacherSourceSyncParsingPolicy.pageNumberFromSection(section); }
    // Delegates deterministic file/parse policy to TeacherSourceSyncParsingPolicy.
    static ImageReference markdownImageReference(String line) { return TeacherSourceSyncParsingPolicy.markdownImageReference(line); }
    // Delegates deterministic file/parse policy to TeacherSourceSyncParsingPolicy.
    static String htmlAttributeValue(Pattern attributePattern, String tag) { return TeacherSourceSyncParsingPolicy.htmlAttributeValue(attributePattern, tag); }
    // Delegates deterministic file/parse policy to TeacherSourceSyncParsingPolicy.
    static Optional<PendingAsset> readMarkdownAsset(Path markdownFile, String imagePath) { return TeacherSourceSyncParsingPolicy.readMarkdownAsset(markdownFile, imagePath); }
    static String logicalImagePath(String sourcePath, String imagePath) { return TeacherSourceSyncParsingPolicy.logicalImagePath(sourcePath, imagePath); }
    // Delegates deterministic file/parse policy to TeacherSourceSyncParsingPolicy.
    static String decodeLocalImagePath(String path) { return TeacherSourceSyncParsingPolicy.decodeLocalImagePath(path); }
    // Delegates deterministic file/parse policy to TeacherSourceSyncParsingPolicy.
    static String mimeTypeFromName(String fileName) { return TeacherSourceSyncParsingPolicy.mimeTypeFromName(fileName); }
    // Delegates deterministic file/parse policy to TeacherSourceSyncParsingPolicy.
    static List<ParsedBlock> parseDocxBlocks(Path file) { return TeacherSourceSyncParsingPolicy.parseDocxBlocks(file); }
    // Delegates deterministic file/parse policy to TeacherSourceSyncParsingPolicy.
    static synchronized void configurePoiDocxEntryLimit() { TeacherSourceSyncParsingPolicy.configurePoiDocxEntryLimit(); }
    // Delegates deterministic file/parse policy to TeacherSourceSyncParsingPolicy.
    static byte[] readDocxPackagePart(Path file, String providerId) throws IOException { return TeacherSourceSyncParsingPolicy.readDocxPackagePart(file, providerId); }
    // Delegates deterministic file/parse policy to TeacherSourceSyncParsingPolicy.
    static List<ParsedBlock> parsePdfBlocks(Path file) { return TeacherSourceSyncParsingPolicy.parsePdfBlocks(file); }
    // Delegates deterministic file/parse policy to TeacherSourceSyncParsingPolicy.
    static List<ParsedBlock> parseRenderedDocxPages(Path docx) { return TeacherSourceSyncParsingPolicy.parseRenderedDocxPages(docx); }
    // Delegates deterministic file/parse policy to TeacherSourceSyncParsingPolicy.
    static Path resolveDocxRenderScript() { return TeacherSourceSyncParsingPolicy.resolveDocxRenderScript(); }

    /**
     * Converts a parsed text segment to a document block response.
     */
    private TeacherDocumentBlockResponse toBlock(
            String tenantId,
            String viewerRole,
            String viewerSubjectId,
            TeacherResourceDocumentResponse document,
            String relativePath,
            ParsedBlock parsed,
            int order,
            List<FormulaReference> pageFormulas) {
        String sourcePath = relativePath.replace('\\', '/');
        // Markdown render manifests identify pages in their explicit `Page N` section rather than a PDF parser field.
        // Resolve it again here because this is the single persistence boundary shared by Markdown, DOCX, and PDF.
        Integer resolvedPageNo = parsed.pageNo() == null ? pageNumberFromSection(parsed.section()) : parsed.pageNo();
        List<StoredAssetReference> storedAssets = storeAssets(document, sourcePath, parsed, resolvedPageNo);
        Optional<TeacherPageTranscriptionClient.PageTranscription> pageTranscription = transcribeAuthorizedPage(
                tenantId, viewerRole, viewerSubjectId, document, resolvedPageNo, storedAssets, parsed.text());
        // The model is allowed to replace only a page's visibly corrupted extraction. It never writes an answer or a
        // synthetic exercise: if it is unavailable or below its confidence gate, the original parser text remains.
        String sourceText = pageTranscription.map(TeacherPageTranscriptionClient.PageTranscription::text)
                .orElse(parsed.text());
        List<FormulaReference> formulas = formulaReferences(parsed.formulas());
        formulas.addAll(bindFormulaAssets(pageFormulas, storedAssets));
        String formulaRefs = formulaRefs(formulas);
        String formulaEvidence = formulaEvidence(formulas);
        /*
         * The canonical formula text belongs in normalizedText because this exact value is embedded into Milvus.
         * Keeping rawText unmodified preserves the source extraction for display, while a formula-only paragraph is
         * still indexable and receives the same graph/rerank treatment as ordinary teaching text.
         */

        String normalized = normalizeText(String.join(" ", sourceText, formulaEvidence));
        String externalBlockId = stableExternalBlockId(sourcePath, parsed, order);
        String blockRole = classifyBlockRole(sourcePath, parsed, normalized);
        /*
         * Graph alignment is written at sync time, not lazily at query time only. This keeps document-level coarse
         * recall stable after incremental updates because Milvus/MySQL rows already carry the same normalized concept
         * view used later by query-side rerank.
         */
        TeacherResourceGraphAlignmentService.GraphAlignment graphAlignment = graphAlignmentService.alignBlock(
                tenantId,
                viewerRole,
                viewerSubjectId,
                document,
                sourcePath,
                blockRole,
                parsed.chapter(),
                parsed.section(),
                normalizeText(String.join(" ", sourceText, formulaEvidence)),
                normalized);
        String imageRefs = imageRefs(storedAssets);
        return new TeacherDocumentBlockResponse(
                UUID.randomUUID().toString(),
                document.documentId(),
                externalBlockId,
                relativePath.endsWith(".md") ? "markdown" : relativePath.endsWith(".pdf") ? "pdf_text" : "text",
                order,
                parsed.chapter(),
                parsed.section(),
                resolvedPageNo,
                null,
                sourcePath,

                blockRole,
                sourceText,
                normalized,
                imageRefs,
                formulaRefs,
                jsonArray(graphAlignment.nodeIds()),
                jsonArray(graphAlignment.tagNames()),
                sha256(normalized),
                pageTranscription.map(TeacherPageTranscriptionClient.PageTranscription::confidence).orElse(1.0d),
                "active");
    }
    // Delegates deterministic file/parse policy to TeacherSourceSyncParsingPolicy.
    static String stableExternalBlockId(String sourcePath, ParsedBlock parsed, int order) { return TeacherSourceSyncParsingPolicy.stableExternalBlockId(sourcePath, parsed, order); }
    // Delegates deterministic file/parse policy to TeacherSourceSyncParsingPolicy.
    static String classifyBlockRole(String sourcePath, ParsedBlock parsed, String normalizedText) { return TeacherSourceSyncParsingPolicy.classifyBlockRole(sourcePath, parsed, normalizedText); }
    // Delegates deterministic file/parse policy to TeacherSourceSyncParsingPolicy.
    static String normalizeText(String text) { return TeacherSourceSyncParsingPolicy.normalizeText(text); }
    // Delegates deterministic file/parse policy to TeacherSourceSyncParsingPolicy.
    static String normalizeHeading(String value) { return TeacherSourceSyncParsingPolicy.normalizeHeading(value); }
    // Delegates deterministic file/parse policy to TeacherSourceSyncParsingPolicy.
    static String jsonArray(List<String> values) { return TeacherSourceSyncParsingPolicy.jsonArray(values); }

    /**
     * Persists extracted images and converts them to safe asset references carried by block imageRefs.
     */
    private List<StoredAssetReference> storeAssets(
            TeacherResourceDocumentResponse document,
            String sourcePath,
            ParsedBlock parsed,
            Integer pageNo) {
        if (parsed.assets().isEmpty()) {
            return List.of();
        }
        List<StoredAssetReference> refs = new ArrayList<>();
        for (PendingAsset pending : parsed.assets()) {
            String logicalPath = logicalImagePath(sourcePath, pending.imageReference());
            Optional<com.doob.mathagent.teacher.vo.TeacherResourceAssetResponse> saved =
                    assetService.saveExtractedAsset(
                            document,
                            logicalPath,
                            pageNo,
                            logicalPath,
                            pending.content(),
                            pending.mimeType(),
                            true);
            saved.ifPresent(asset -> refs.add(new StoredAssetReference(
                    asset.assetId(),
                    asset.pageNo(),
                    textOrDefault(asset.sourcePath(), logicalPath),
                    pending.imageReference(),
                    pending.originalFilename(),
                    logicalPath,
                    pending.markdownLine(),
                    logicalPath,
                    textOrDefault(asset.mimeType(), "application/octet-stream"),
                    pending.content())));
        }
        return List.copyOf(refs);
    }

    /**
     * Performs visible-text transcription only after the source page is stored as a teacher asset and re-opened through
     * the same tenant/role/owner check used by normal resource access. The configured page cap is an explicit paid-AI
     * budget; later pages remain synchronized as images and can be resumed in a later authorized sync rather than being
     * silently invented from a broken text layer.
     */
    private Optional<TeacherPageTranscriptionClient.PageTranscription> transcribeAuthorizedPage(
            String tenantId,
            String viewerRole,
            String viewerSubjectId,
            TeacherResourceDocumentResponse document,
            Integer pageNo,
            List<StoredAssetReference> assets,
            String extractedText) {
        boolean aiMode = "AI".equalsIgnoreCase(textOrDefault(document.parseMode(), "TEXT"));
        boolean eligiblePage = pageNo != null && pageNo > 0 && pageNo <= MAX_PAGE_TRANSCRIPTION_PAGES_PER_DOCUMENT;
        boolean hasImage = assets != null && !assets.isEmpty();
        String visibleText = textOrDefault(extractedText, "");
        boolean needsVisualTextRepair = visibleText.isBlank() || UNRESOLVED_MATHEMATICAL_OCR_GLYPH.matcher(visibleText).find();
        LOGGER.info("teacher_page_transcription_gate document={} aiMode={} page={} eligiblePage={} hasImage={} clientEnabled={}",
                document.documentId(), aiMode, pageNo, eligiblePage, hasImage, pageTranscriptionClient.isEnabled());
        if (!aiMode || !eligiblePage || !hasImage || !needsVisualTextRepair) {
            LOGGER.info("teacher_page_transcription_skipped document={} aiMode={} page={} eligiblePage={} hasImage={}",
                    document.documentId(), aiMode, pageNo, eligiblePage, hasImage);
            return Optional.empty();
        }
        for (StoredAssetReference asset : assets) {
            if (!textOrDefault(asset.mimeType(), "").toLowerCase(Locale.ROOT).startsWith("image/")) {
                continue;
            }
            try {
                TeacherResourceAssetService.VisibleAsset visibleAsset = assetService.openVisibleAsset(
                        asset.assetId(),
                        new RequestSubject(tenantId, viewerRole, viewerSubjectId, "teacher-source-sync").normalize());
                Optional<TeacherPageTranscriptionClient.PageTranscription> transcription =
                        pageTranscriptionClient.transcribe(visibleAsset.resource().getFile().toPath(), visibleAsset.mimeType());
                if (transcription.isPresent()) {
                    return transcription;
                }
            } catch (IOException | IllegalArgumentException exception) {
                // Source synchronization remains lossless even when the relay/model is unavailable; the original image
                // and parser text stay persisted and the question importer will refuse unresolved glyphs later.
                // Do not emit page text, asset paths, or provider payloads because these images are teacher-private
                // source material. The exception category is sufficient to establish which permission/materialization
                // boundary rejected the page during a real sync.
                LOGGER.warn("teacher_page_transcription_open_failed document={} page={} reason={}",
                        document.documentId(), pageNo, exception.getClass().getSimpleName());
            }
        }
        return Optional.empty();
    }
    // Delegates deterministic file/parse policy to TeacherSourceSyncParsingPolicy.
    static String imageRefs(List<StoredAssetReference> assets) { return TeacherSourceSyncParsingPolicy.imageRefs(assets); }
    // Delegates deterministic file/parse policy to TeacherSourceSyncParsingPolicy.
    static List<FormulaReference> formulaReferences(List<OmmlFormulaExtractor.ExtractedFormula> formulas) { return TeacherSourceSyncParsingPolicy.formulaReferences(formulas); }


    /**
     * Sends raster assets only when the uploader explicitly selected AI mode and the document-level budget allows it.
     * Unsupported or uncertain images remain safely referenced through imageRefs, but never contribute invented formula
     * text to graph alignment, Milvus vectors, or stage-two reranking.
     */
    private void recognizeFormulaImages(
            TeacherResourceDocumentResponse document,
            List<StoredAssetReference> assets,
            List<FormulaReference> formulas,

            FormulaVisionBudget budget) {
        if (assets == null || assets.isEmpty() || !budget.enabled()) {
            return;
        }
        for (StoredAssetReference asset : assets) {
            if (!budget.tryAcquire()) {
                return;
            }
            TeacherFormulaRecognitionClient.FormulaRecognitionResult recognized =
                    formulaRecognitionClient.recognize(asset.content(), asset.mimeType());
            if (recognized.recognized()) {
                formulas.add(new FormulaReference(
                        "image_vision",
                        "verified_model",
                        recognized.confidence(),
                        null,
                        null,
                        recognized.latex(),
                        recognized.plainText(),
                        asset.assetId(),
                        recognized.model()));
            }
        }
    }

    /**
     * PDF parsing already creates one rendered image per page. Submit those pages in ordered two/four-page batches so
     * the visual model sees page context while formula cost scales with page batches rather than formula count.
     */
    private List<List<FormulaReference>> recognizePdfPageBatches(
            TeacherResourceDocumentResponse document,
            List<ParsedBlock> parsedBlocks,
            FormulaVisionBudget budget) {
        List<List<FormulaReference>> formulasByBlock = new ArrayList<>();
        for (int index = 0; index < parsedBlocks.size(); index += 1) {
            formulasByBlock.add(new ArrayList<>());
        }
        if (!budget.enabled() || parsedBlocks.isEmpty()) {
            return formulasByBlock;
        }
        List<Integer> pageBlockIndexes = new ArrayList<>();
        for (int index = 0; index < parsedBlocks.size(); index += 1) {
            ParsedBlock parsed = parsedBlocks.get(index);
            /*
             * Normal PDF pages already have a usable text layer and a durable PNG asset. Calling the visual model
             * for every page multiplies cost without improving those pages. Restrict recovery to pages whose text
             * layer contains a private-use/replacement glyph, while keeping every page image available for CLIP and
             * manual verification when a caller needs the original visual equation.
             */
            if (parsed.pageNo() != null && !parsed.assets().isEmpty()
                    && UNRESOLVED_PDF_MATH_GLYPH.matcher(textOrDefault(parsed.text(), "")).find()) {
                pageBlockIndexes.add(index);
            }
        }
        int batchSize = formulaRecognitionProperties.normalizedPagesPerRequest();
        for (int offset = 0; offset < pageBlockIndexes.size(); offset += batchSize) {
            int end = Math.min(pageBlockIndexes.size(), offset + batchSize);
            if (!budget.tryAcquirePages(end - offset)) {
                break;
            }
            List<TeacherFormulaRecognitionClient.PageImage> pages = new ArrayList<>();
            for (int cursor = offset; cursor < end; cursor += 1) {
                ParsedBlock parsed = parsedBlocks.get(pageBlockIndexes.get(cursor));
                PendingAsset pageImage = parsed.assets().getFirst();
                pages.add(new TeacherFormulaRecognitionClient.PageImage(parsed.pageNo(), pageImage.content(), pageImage.mimeType()));
            }
            for (TeacherFormulaRecognitionClient.PageFormulaRecognitionResult page : formulaRecognitionClient.recognizePages(pages)) {
                if (page.pageIndex() < 0 || page.pageIndex() >= pages.size()) {
                    continue;
                }
                List<FormulaReference> target = formulasByBlock.get(pageBlockIndexes.get(offset + page.pageIndex()));
                for (TeacherFormulaRecognitionClient.FormulaRecognitionResult formula : page.formulas()) {
                    target.add(new FormulaReference(
                            "page_vision",
                            "verified_model",
                            formula.confidence(),
                            null,
                            null,
                            formula.latex(),
                            formula.plainText(),
                            null,
                            page.model()));
                }
            }
        }
        return formulasByBlock;
    }
    // Delegates deterministic file/parse policy to TeacherSourceSyncParsingPolicy.
    static List<FormulaReference> bindFormulaAssets(List<FormulaReference> formulas, List<StoredAssetReference> assets) { return TeacherSourceSyncParsingPolicy.bindFormulaAssets(formulas, assets); }
    // Delegates deterministic file/parse policy to TeacherSourceSyncParsingPolicy.
    static String formulaRefs(List<FormulaReference> formulas) { return TeacherSourceSyncParsingPolicy.formulaRefs(formulas); }
    // Delegates deterministic file/parse policy to TeacherSourceSyncParsingPolicy.
    static void putIfPresent(Map<String, Object> target, String key, String value) { TeacherSourceSyncParsingPolicy.putIfPresent(target, key, value); }
    // Delegates deterministic file/parse policy to TeacherSourceSyncParsingPolicy.
    static String formulaEvidence(List<FormulaReference> formulas) { return TeacherSourceSyncParsingPolicy.formulaEvidence(formulas); }
    // Delegates deterministic file/parse policy to TeacherSourceSyncParsingPolicy.
    static String sha256(String value) { return TeacherSourceSyncParsingPolicy.sha256(value); }
    // Delegates deterministic file/parse policy to TeacherSourceSyncParsingPolicy.
    static String stripExtension(String fileName) { return TeacherSourceSyncParsingPolicy.stripExtension(fileName); }
    // Delegates deterministic file/parse policy to TeacherSourceSyncParsingPolicy.
    static void requireTeacherOrAdmin(String viewerRole) { TeacherSourceSyncParsingPolicy.requireTeacherOrAdmin(viewerRole); }

    /**
     * Loads a document and enforces teacher ownership unless admin.
     */
    private TeacherResourceDocumentResponse requireVisibleDocument(
            String tenantId,
            String viewerRole,
            String viewerSubjectId,
            String documentId) {
        TeacherResourceDocumentResponse document = resourceStore.find(tenantId, documentId);
        if (document == null) {
            throw new IllegalArgumentException("Teacher resource document not found: " + documentId);
        }
        if (!"admin".equals(viewerRole) && !document.ownerSubjectId().equals(viewerSubjectId)) {
            throw new IllegalArgumentException("Teacher can execute sync only for own resources");
        }
        return document;
    }

    /**
     * Loads a queued sync job for the document.
     */
    private TeacherSourceSyncJobResponse requireJob(String tenantId, String documentId, String jobId) {
        return jobStore.listByDocument(tenantId, documentId).stream()
                .filter(candidate -> candidate.jobId().equals(jobId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Teacher source sync job not found: " + jobId));
    }
    // Delegates deterministic file/parse policy to TeacherSourceSyncParsingPolicy.
    static TeacherSourceSyncJobResponse updateJob(TeacherSourceSyncJobResponse job, String status, String phase, String stagingPath, String message) { return TeacherSourceSyncParsingPolicy.updateJob(job, status, phase, stagingPath, message); }
    // Delegates deterministic file/parse policy to TeacherSourceSyncParsingPolicy.
    static TeacherSourceSyncJobResponse updateJob(TeacherSourceSyncJobResponse job, String status, String phase, String stagingPath, String message, TeacherSourceSyncFailureResponse failure) { return TeacherSourceSyncParsingPolicy.updateJob(job, status, phase, stagingPath, message, failure); }
    // Delegates deterministic file/parse policy to TeacherSourceSyncParsingPolicy.
    static String textOrDefault(String value, String defaultValue) { return TeacherSourceSyncParsingPolicy.textOrDefault(value, defaultValue); }
    // Delegates deterministic file/parse policy to TeacherSourceSyncParsingPolicy.
    static String firstNonBlank(String first, String second) { return TeacherSourceSyncParsingPolicy.firstNonBlank(first, second); }
    // Delegates deterministic file/parse policy to TeacherSourceSyncParsingPolicy.
    static String requireText(String value, String message) { return TeacherSourceSyncParsingPolicy.requireText(value, message); }
    // Delegates deterministic file/parse policy to TeacherSourceSyncParsingPolicy.
    static boolean containsAny(String haystack, String... needles) { return TeacherSourceSyncParsingPolicy.containsAny(haystack, needles); }

    record FileSeparatedParseResult(int fileCount, int blockCount, String rootChecksum) {
    }
    /** Internal parsed block model. */
    record ParsedBlock(
            String chapter,
            String section,
            Integer pageNo,
            String text,
            List<PendingAsset> assets,
            List<OmmlFormulaExtractor.ExtractedFormula> formulas) {
    }

    /** Local Markdown image metadata extracted before the block is persisted. */
    record ImageReference(String altText, String path, String qualifiedReference, String sourcePath, String originalFilename,
                          String markdownLine) {
        ImageReference(String altText, String path, String qualifiedReference, String sourcePath, String originalFilename) {
            this(altText, path, qualifiedReference, sourcePath, originalFilename, "");
        }

        ImageReference withMarkdownLine(String line) {
            return new ImageReference(altText, path, qualifiedReference, sourcePath, originalFilename,
                    line == null ? "" : line);
        }
    }

    record PendingAsset(String providerAssetId, String imageReference, String originalFilename, String qualifiedReference,
                        String markdownLine, byte[] content, String mimeType) {

        PendingAsset(String providerAssetId, String imageReference, String originalFilename, String qualifiedReference,
                     byte[] content, String mimeType) {
            this(providerAssetId, imageReference, originalFilename, qualifiedReference, "", content, mimeType);
        }

        PendingAsset(String providerAssetId, byte[] content, String mimeType) {
            this(providerAssetId, providerAssetId, fileName(providerAssetId), providerAssetId, "", content, mimeType);
        }

        private static String fileName(String value) {
            String normalized = value == null ? "" : value.replace('\\', '/');
            int slash = normalized.lastIndexOf('/');
            return slash < 0 ? normalized : normalized.substring(slash + 1);
        }
    }

    /** Asset bytes are retained only through this sync call; durable references remain opaque asset ids in MySQL. */
    record StoredAssetReference(
            String assetId,
            Integer pageNo,
            String sourcePath,
            String imageReference,
            String originalFilename,
            String qualifiedReference,
            String markdownLine,
            String logicalPath,
            String mimeType,
            byte[] content) {
    }

    /** Internal representation serialized into the existing document_block.formula_refs JSON column. */
    record FormulaReference(
            String source,
            String recognitionStatus,
            double confidence,
            String omml,
            String mathMl,
            String latex,
            String plainText,
            String assetId,
            String model) {
    }

    /**
     * Counts model invocations per source document rather than per worker process so one large PDF cannot unexpectedly
     * consume an unbounded paid-vision budget. The uploader can increase this explicit deployment setting when needed.
     */
    static final class FormulaVisionBudget {

        private final boolean enabled;
        private final int maximum;
        private int consumed;

        private FormulaVisionBudget(boolean enabled, int maximum) {
            this.enabled = enabled;
            this.maximum = maximum;
        }

        static FormulaVisionBudget forDocument(
                TeacherResourceDocumentResponse document,
                TeacherFormulaRecognitionProperties properties) {
            boolean aiMode = "AI".equalsIgnoreCase(textOrDefault(document.parseMode(), "TEXT"));
            return new FormulaVisionBudget(
                    aiMode && properties.enabled(),
                    properties.normalizedMaxImagesPerDocument());
        }

        private boolean enabled() {
            return enabled && maximum > 0;
        }

        private boolean tryAcquire() {
            if (!enabled() || consumed >= maximum) {
                return false;
            }
            consumed += 1;
            return true;
        }

        private boolean tryAcquirePages(int pageCount) {
            if (!enabled() || pageCount <= 0 || consumed + pageCount > maximum) {
                return false;
            }
            consumed += pageCount;
            return true;
        }
    }
}
