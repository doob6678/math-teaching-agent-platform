package com.doob.mathagent.teacher.service;

import com.doob.mathagent.teacher.block.TeacherDocumentBlockResponse;
import com.doob.mathagent.teacher.block.TeacherDocumentBlockStore;
import com.doob.mathagent.teacher.document.TeacherResourceDocumentResponse;
import com.doob.mathagent.teacher.document.TeacherResourceStore;
import com.doob.mathagent.teacher.feishu.TeacherFeishuDownloadClient;
import com.doob.mathagent.teacher.feishu.TeacherFeishuDownloadException;
import com.doob.mathagent.teacher.formula.OmmlFormulaExtractor;
import com.doob.mathagent.teacher.formula.TeacherFormulaRecognitionClient;
import com.doob.mathagent.teacher.formula.TeacherFormulaRecognitionProperties;
import com.doob.mathagent.teacher.search.TeacherResourceGraphAlignmentService;
import com.doob.mathagent.teacher.sync.TeacherSourceSyncCheckpointStore;
import com.doob.mathagent.teacher.sync.TeacherSourceSyncJobStore;
import com.doob.mathagent.teacher.sync.TeacherSourceSyncProperties;
import com.doob.mathagent.teacher.vo.TeacherSourceSyncCheckpointResponse;
import com.doob.mathagent.teacher.vo.TeacherSourceSyncJobResponse;
import com.doob.mathagent.vector.service.VectorIndexRebuildResponse;
import com.doob.mathagent.vector.service.VectorIndexService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.UUID;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import javax.imageio.ImageIO;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFPicture;
import org.apache.poi.xwpf.usermodel.XWPFPictureData;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Executes queued teacher source synchronization jobs.
 *
 * Do not put @Service back on this class while the legacy test constructors exist. Spring picked a compatibility
 * constructor in real runs and disabled asset persistence. Production wiring is pinned in
 * TeacherSourceSyncExecutionConfiguration so DOCX/PDF/Feishu assets always use the real asset service.
 */
public class TeacherSourceSyncExecutionService {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final int MAX_SCAN_DEPTH = 8;

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
                new TeacherFormulaRecognitionProperties(false, 0, 2));
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
                new TeacherFormulaRecognitionProperties(false, 0, 2));
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
                new TeacherFormulaRecognitionProperties(false, 0, 2));
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
        try {
            if (feishuSource) {
                if (resumeCheckpoint == null) {
                    saveFeishuCheckpoint(document, running, "[]", "[]", 1);
                }
                TeacherFeishuDownloadClient.FeishuDownloadResult result = feishuDownloadClient.download(
                        requireText(document.originalUrl(), "Feishu resource originalUrl is required"),
                        syncProperties.feishuStagingRoot(),
                        syncProperties.feishuSmokeMaxFiles(),
                        textOrDefault(document.feishuExportFormat(), "md"),
                        toDownloadCheckpoint(resumeCheckpoint));
                TeacherResourceDocumentResponse downloaded = new TeacherResourceDocumentResponse(
                        document.documentId(),
                        document.tenantId(),
                        document.ownerSubjectId(),
                        document.sourceType(),
                        document.title(),
                        document.originalUrl(),
                        result.savedPath().toString(),
                        document.permissionScope(),
                        "downloaded",
                        "pending",
                        "pending",
                        "waiting_rebuild",
                        document.feishuExportFormat(),
                        document.previewFiles(),
                        document.parseMode());
                resourceStore.save(downloaded);
                assetService.markDocumentAssetsInactive(document.tenantId(), document.documentId());
                int feishuManifestAssets = ingestFeishuDownloadedAssetManifest(downloaded, result);
                List<TeacherDocumentBlockResponse> blocks = parseResourceFiles(
                        normalizedTenantId,
                        normalizedRole,
                        normalizedSubjectId,
                        downloaded,
                        true);
                String vectorMessage = "";
                if (!blocks.isEmpty()) {
                    blockStore.replaceActiveBlocks(document.tenantId(), document.documentId(), blocks);
                    TeacherResourceDocumentResponse synced = markLocalResourceSynced(downloaded);
                    vectorMessage = autoRebuildVectorIndex(synced, normalizedRole, normalizedSubjectId);
                }
                TeacherSourceSyncJobResponse completed = updateJob(
                        running,
                        "completed",
                        "download_completed",
                        result.savedPath().toString(),
                        blocks.isEmpty()
                                ? result.message() + "; no supported files parsed"
                                        + assetCountSuffix(feishuManifestAssets)
                                : result.message() + "; Parsed " + blocks.size() + " blocks"
                                        + assetCountSuffix(feishuManifestAssets)
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
                    document);
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
                if (exception instanceof TeacherFeishuDownloadException feishuException) {
                    retryable = feishuException.retryable();
                    failureCheckpoint = feishuException.checkpoint();
                }
                TeacherSourceSyncCheckpointResponse checkpointToSave = failureCheckpoint.hasCursor()
                        ? toStoredCheckpoint(document, running, failureCheckpoint, "[]")
                        : resumeCheckpoint;
                TeacherSourceSyncJobResponse pausedOrFailed = updateJob(
                        running,
                        retryable ? "paused" : "failed",
                        retryable ? "download_paused" : "download_failed",
                        null,
                        exception.getMessage());
                saveFeishuCheckpoint(
                        document,
                        pausedOrFailed,
                        checkpointToSave,
                        checkpointToSave == null ? "[]" : checkpointToSave.downloadedItemsJson(),
                        failedItemsJson(exception, retryable),
                        2);
                return jobStore.save(pausedOrFailed);
            }
            TeacherSourceSyncJobResponse failed = updateJob(
                    running,
                    "failed",
                    "parse_failed",
                    null,
                    exception.getMessage());
            return jobStore.save(failed);
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
        if (!"paused".equalsIgnoreCase(textOrDefault(job.status(), ""))) {
            throw new IllegalArgumentException("Only paused source sync jobs can be resumed");
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

    /**
     * Converts a stored checkpoint to the downloader protocol.
     */
    private static TeacherFeishuDownloadClient.FeishuDownloadCheckpoint toDownloadCheckpoint(
            TeacherSourceSyncCheckpointResponse checkpoint) {
        if (checkpoint == null) {
            return TeacherFeishuDownloadClient.FeishuDownloadCheckpoint.empty();
        }
        return new TeacherFeishuDownloadClient.FeishuDownloadCheckpoint(
                textOrDefault(checkpoint.currentFolderToken(), ""),
                textOrDefault(checkpoint.currentPath(), ""),
                textOrDefault(checkpoint.pageToken(), ""),
                jsonOrEmptyArray(checkpoint.visitedFolderTokensJson()),
                jsonOrEmptyArray(checkpoint.downloadedItemsJson()));
    }

    /**
     * Converts a worker checkpoint back into the persisted checkpoint shape.
     */
    private static TeacherSourceSyncCheckpointResponse toStoredCheckpoint(
            TeacherResourceDocumentResponse document,
            TeacherSourceSyncJobResponse job,
            TeacherFeishuDownloadClient.FeishuDownloadCheckpoint checkpoint,
            String failedItemsJson) {
        String rootToken = extractFeishuToken(textOrDefault(document.originalUrl(), ""));
        return new TeacherSourceSyncCheckpointResponse(
                job.jobId(),
                document.tenantId(),
                document.documentId(),
                rootToken,
                textOrDefault(checkpoint.currentFolderToken(), rootToken),
                textOrDefault(checkpoint.currentPath(), textOrDefault(document.title(), "Feishu source")),
                textOrDefault(checkpoint.pageToken(), null),
                jsonOrEmptyArray(checkpoint.visitedFolderTokensJson()),
                jsonOrEmptyArray(checkpoint.downloadedItemsJson()),
                jsonOrEmptyArray(failedItemsJson),
                2,
                Instant.now().toString());
    }

    /**
     * Builds a compact downloaded-items JSON array from a successful download result.
     */
    private static String downloadedItemsJson(TeacherFeishuDownloadClient.FeishuDownloadResult result) {
        return "[{\"savedPath\":\"" + escapeJson(result.savedPath().toString()) + "\","
                + "\"files\":" + result.files() + ","
                + "\"skipped\":" + result.skipped() + ","
                + "\"failed\":" + result.failed() + "}]";
    }

    /**
     * Prefers provider item-level downloaded records and falls back to a compact aggregate row.
     */
    private static String mergeDownloadedItemsJson(TeacherFeishuDownloadClient.FeishuDownloadResult result) {
        String itemLevel = jsonOrEmptyArray(result.downloadedItemsJson());
        return "[]".equals(itemLevel) ? downloadedItemsJson(result) : itemLevel;
    }

    /**
     * Builds a compact failed-items JSON array from a download failure.
     */
    private static String failedItemsJson(RuntimeException exception, boolean retryable) {
        return "[{\"message\":\"" + escapeJson(textOrDefault(exception.getMessage(), exception.getClass().getSimpleName()))
                + "\",\"retryable\":" + retryable + "}]";
    }

    /**
     * Extracts a Feishu browser URL token without exposing any secret material.
     */
    private static String extractFeishuToken(String url) {
        String normalized = textOrDefault(url, "");
        int slash = normalized.lastIndexOf('/');
        if (slash < 0 || slash == normalized.length() - 1) {
            return normalized;
        }
        String tail = normalized.substring(slash + 1);
        int question = tail.indexOf('?');
        return question >= 0 ? tail.substring(0, question) : tail;
    }

    /**
     * Escapes a string for the small JSON snippets stored in checkpoint rows.
     */
    private static String escapeJson(String value) {
        return textOrDefault(value, "")
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\r", "\\r")
                .replace("\n", "\\n");
    }

    /**
     * Defaults blank JSON array fields to an empty array string.
     */
    private static String jsonOrEmptyArray(String value) {
        return value == null || value.isBlank() ? "[]" : value;
    }

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

    private static String assetCountSuffix(int assetCount) {
        return assetCount > 0 ? "; Feishu manifest assets " + assetCount : "";
    }

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
            Path file = resolveDownloadedItemPath(result.savedPath(), relativePath);
            if (!Files.isRegularFile(file)) {
                continue;
            }
            try {
                String providerAssetId = "feishu:" + textOrDefault(item.path("token").asText(""), relativePath);
                Optional<com.doob.mathagent.teacher.vo.TeacherResourceAssetResponse> saved =
                        assetService.saveExtractedAsset(
                                document,
                                relativePath.replace('\\', '/'),
                                null,
                                providerAssetId,
                                Files.readAllBytes(file),
                                textOrDefault(item.path("mimeType").asText(""), "application/octet-stream"));
                if (saved.isPresent()) {
                    count += 1;
                }
            } catch (IOException exception) {
                throw new IllegalStateException("Failed to ingest Feishu asset manifest file: " + file, exception);
            }
        }
        return count;
    }

    /**
     * Resolves downloader relative paths without letting manifest data escape the saved folder/file root.
     */
    private static Path resolveDownloadedItemPath(Path savedPath, String relativePath) {
        Path base = savedPath.toAbsolutePath().normalize();
        if (Files.isRegularFile(base)) {
            if (base.getFileName().toString().equals(relativePath)) {
                return base;
            }
            Path parent = base.getParent() == null ? base : base.getParent();
            Path resolved = parent.resolve(relativePath).normalize();
            if (!resolved.startsWith(parent)) {
                throw new IllegalArgumentException("Feishu downloaded item path escapes saved file parent");
            }
            return resolved;
        }
        Path resolved = base.resolve(relativePath).normalize();
        if (!resolved.startsWith(base)) {
            throw new IllegalArgumentException("Feishu downloaded item path escapes saved folder");
        }
        return resolved;
    }

    /**
     * Marks a local resource as parsed while keeping embedding/index rebuild pending.
     */
    private TeacherResourceDocumentResponse markLocalResourceSynced(TeacherResourceDocumentResponse document) {
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
                document.parseMode());
        resourceStore.save(synced);
        return synced;
    }

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
        return "; Vector index " + response.status() + ": " + textOrDefault(response.message(), "");
    }

    private static final class VectorIndexSyncException extends RuntimeException {

        private VectorIndexSyncException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * Parses a local teacher resource into document blocks.
     */
    private List<TeacherDocumentBlockResponse> parseResourceFiles(
            String tenantId,
            String viewerRole,
            String viewerSubjectId,
            TeacherResourceDocumentResponse document) {
        return parseResourceFiles(tenantId, viewerRole, viewerSubjectId, document, false);
    }

    private List<TeacherDocumentBlockResponse> parseResourceFiles(
            String tenantId,
            String viewerRole,
            String viewerSubjectId,
            TeacherResourceDocumentResponse document,
            boolean allowNoSupportedFiles) {
        Path root = Path.of(textOrDefault(document.localPath(), ""));
        if (!Files.exists(root)) {
            throw new IllegalArgumentException("Local resource path does not exist: " + root);
        }
        List<Path> files = listSupportedFiles(root);
        if (files.isEmpty()) {
            if (allowNoSupportedFiles) {
                return List.of();
            }
            throw new IllegalArgumentException("Local resource path contains no supported .md, .txt, .docx, or .pdf files: " + root);
        }
        List<TeacherDocumentBlockResponse> blocks = new ArrayList<>();
        FormulaVisionBudget formulaVisionBudget = FormulaVisionBudget.forDocument(document, formulaRecognitionProperties);
        int order = 0;
        for (Path file : files) {
            String relativePath = root.equals(file) ? file.getFileName().toString() : root.relativize(file).toString();
            List<ParsedBlock> parsedBlocks = new ArrayList<>(parseFileBlocks(file));
            if ("AI".equalsIgnoreCase(textOrDefault(document.parseMode(), "TEXT"))
                    && file.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".docx")) {
                /* Render complete Word pages once, then feed them into the same PDF page-batch path. Do not inspect
                 * individual WMF/PNG equation assets: that would multiply visual calls and lose page context. */
                parsedBlocks.addAll(parseRenderedDocxPages(file));
            }
            List<List<FormulaReference>> pageFormulas = recognizePdfPageBatches(document, parsedBlocks, formulaVisionBudget);
            for (int index = 0; index < parsedBlocks.size(); index += 1) {
                ParsedBlock parsed = parsedBlocks.get(index);
                blocks.add(toBlock(
                        tenantId,
                        viewerRole,
                        viewerSubjectId,
                        document,
                        relativePath.replace('\\', '/'),
                        parsed,
                        order++,
                        pageFormulas.get(index)));
            }
        }
        return blocks;
    }

    /**
     * Lists supported local Markdown and plain-text files.
     */
    private static List<Path> listSupportedFiles(Path root) {
        if (Files.isRegularFile(root)) {
            return isSupportedFile(root) ? List.of(root) : List.of();
        }
        try (Stream<Path> stream = Files.walk(root, MAX_SCAN_DEPTH)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(TeacherSourceSyncExecutionService::isSupportedFile)
                    .sorted(Comparator.comparing(Path::toString))
                    .toList();
        } catch (IOException exception) {
            throw new IllegalArgumentException("Failed to scan local resource path: " + root, exception);
        }
    }

    /**
     * Checks whether the file can be parsed by the current local sync parser set.
     */
    private static boolean isSupportedFile(Path file) {
        String fileName = file.getFileName().toString().toLowerCase(Locale.ROOT);
        return fileName.endsWith(".md")
                || fileName.endsWith(".txt")
                || fileName.endsWith(".docx")
                || fileName.endsWith(".pdf");
    }

    /**
     * Reads a UTF-8 source file.
     */
    private static String readUtf8(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalArgumentException("Failed to read local resource file: " + file, exception);
        }
    }

    /**
     * Parses a supported source file into normalized text blocks.
     */
    private static List<ParsedBlock> parseFileBlocks(Path file) {
        String fileName = file.getFileName().toString().toLowerCase(Locale.ROOT);
        if (fileName.endsWith(".md") || fileName.endsWith(".txt")) {
            return parseTextBlocks(readUtf8(file), file);
        }
        if (fileName.endsWith(".docx")) {
            return parseDocxBlocks(file);
        }
        if (fileName.endsWith(".pdf")) {
            return parsePdfBlocks(file);
        }
        return List.of();
    }

    /**
     * Parses Markdown/text into chapter/section-aware text blocks.
     */
    private static List<ParsedBlock> parseTextBlocks(String text, Path file) {
        List<ParsedBlock> blocks = new ArrayList<>();
        String chapter = stripExtension(file.getFileName().toString());
        String section = null;
        StringBuilder current = new StringBuilder();
        for (String line : text.replace("\r\n", "\n").replace('\r', '\n').split("\n")) {
            String stripped = line.strip();
            if (stripped.startsWith("# ")) {
                flushBlock(blocks, chapter, section, current);
                chapter = stripped.substring(2).strip();
                section = null;
                continue;
            }
            if (stripped.startsWith("## ")) {
                flushBlock(blocks, chapter, section, current);
                section = stripped.substring(3).strip();
                continue;
            }
            if (!stripped.isBlank()) {
                current.append(stripped).append('\n');
            }
        }
        flushBlock(blocks, chapter, section, current);
        return blocks;
    }

    /**
     * Appends a non-empty parsed block and clears the buffer.
     */
    private static void flushBlock(List<ParsedBlock> blocks, String chapter, String section, StringBuilder current) {
        String value = current.toString().strip();
        if (!value.isBlank()) {
            blocks.add(new ParsedBlock(chapter, section, null, value, List.of(), List.of()));
        }
        current.setLength(0);
    }

    /**
     * Parses DOCX paragraphs and extracts embedded images without relying on filenames or keywords for classification.
     */
    private static List<ParsedBlock> parseDocxBlocks(Path file) {
        List<ParsedBlock> blocks = new ArrayList<>();
        String chapter = stripExtension(file.getFileName().toString());
        try (XWPFDocument document = new XWPFDocument(Files.newInputStream(file))) {
            for (XWPFParagraph paragraph : document.getParagraphs()) {
                List<PendingAsset> assets = new ArrayList<>();
                StringBuilder text = new StringBuilder();
                for (XWPFRun run : paragraph.getRuns()) {
                    String runText = textOrDefault(run.text(), "");
                    if (!runText.isBlank()) {
                        text.append(runText).append(' ');
                    }
                    for (XWPFPicture picture : run.getEmbeddedPictures()) {
                        XWPFPictureData pictureData = picture.getPictureData();
                        if (pictureData == null) {
                            continue;
                        }
                        String providerId = pictureData.getPackagePart().getPartName().getName();
                        String mimeType = textOrDefault(pictureData.getPackagePart().getContentType(), "application/octet-stream");
                        byte[] data = pictureData.getData();
                        if (data == null || data.length == 0) {
                            data = pictureData.getPackagePart().getInputStream().readAllBytes();
                        }
                        if (data == null || data.length == 0) {
                            /*
                             * Some real DOCX files produced by python-docx expose the drawing relationship through
                             * POI but return an empty PackagePart stream. The binary still exists in word/media/*, so
                             * fall back to the package entry instead of dropping imageRefs and losing the asset.
                             */
                            data = readDocxPackagePart(file, providerId);
                        }
                        assets.add(new PendingAsset(providerId, data, mimeType));
                    }
                }
                String paragraphText = textOrDefault(text.toString(), paragraph.getText());
                /*
                 * XWPFRun.text() intentionally does not flatten Word's m:oMath tree. Extract OMML from the same
                 * paragraph before persistence so equations do not become invisible blank gaps in an otherwise valid
                 * DOCX question. The original XML remains in formula_refs for lossless rendering/reprocessing.
                 */
                List<OmmlFormulaExtractor.ExtractedFormula> formulas =
                        OmmlFormulaExtractor.extractFromParagraphXml(paragraph.getCTP().xmlText());
                if (!paragraphText.isBlank() || !assets.isEmpty() || !formulas.isEmpty()) {
                    blocks.add(new ParsedBlock(
                            chapter,
                            null,
                            null,
                            paragraphText.isBlank() && formulas.isEmpty()
                                    ? "[DOCX image block; no extractable text]"
                                    : paragraphText,
                            List.copyOf(assets),
                            formulas));
                }
            }
        } catch (IOException exception) {
            throw new IllegalArgumentException("Failed to parse DOCX resource file: " + file, exception);
        }
        return blocks;
    }

    private static byte[] readDocxPackagePart(Path file, String providerId) throws IOException {
        String entryName = textOrDefault(providerId, "").replace('\\', '/');
        if (entryName.startsWith("/")) {
            entryName = entryName.substring(1);
        }
        if (entryName.isBlank()) {
            return new byte[0];
        }
        try (ZipFile zipFile = new ZipFile(file.toFile())) {
            ZipEntry entry = zipFile.getEntry(entryName);
            if (entry == null) {
                return new byte[0];
            }
            return zipFile.getInputStream(entry).readAllBytes();
        }
    }

    /**
     * Parses a PDF into one block per page when extractable text exists.
     */
    private static List<ParsedBlock> parsePdfBlocks(Path file) {
        List<ParsedBlock> blocks = new ArrayList<>();
        String chapter = stripExtension(file.getFileName().toString());
        try (PDDocument document = Loader.loadPDF(file.toFile())) {
            PDFTextStripper stripper = new PDFTextStripper();
            PDFRenderer renderer = new PDFRenderer(document);
            for (int page = 1; page <= document.getNumberOfPages(); page += 1) {
                stripper.setStartPage(page);
                stripper.setEndPage(page);
                String text = textOrDefault(stripper.getText(document), "");
                List<PendingAsset> assets = new ArrayList<>();
                ByteArrayOutputStream imageBytes = new ByteArrayOutputStream();
                ImageIO.write(renderer.renderImageWithDPI(page - 1, 144), "png", imageBytes);
                assets.add(new PendingAsset("pdf-page:" + page, imageBytes.toByteArray(), "image/png"));
                if (!text.isBlank() || !assets.isEmpty()) {
                    blocks.add(new ParsedBlock(
                            chapter,
                            null,
                            page,
                            text.isBlank() ? "[PDF page image; no extractable text]" : text,
                            List.copyOf(assets),
                            List.of()));
                }
            }
        } catch (IOException exception) {
            throw new IllegalArgumentException("Failed to parse PDF resource file: " + file, exception);
        }
        return blocks;
    }

    /** Renders an AI-mode DOCX locally through Word, producing page images used by the shared two/four-page pipeline. */
    private static List<ParsedBlock> parseRenderedDocxPages(Path docx) {
        Path renderedPdf = null;
        try {
            renderedPdf = Files.createTempFile("math-agent-docx-pages-", ".pdf");
            Path script = resolveDocxRenderScript();
            Process process = new ProcessBuilder(
                    "powershell.exe", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass",
                    "-File", script.toString(), "-SourcePath", docx.toString(), "-TargetPath", renderedPdf.toString())
                    .redirectErrorStream(true)
                    .start();
            if (!process.waitFor(90, TimeUnit.SECONDS) || process.exitValue() != 0 || !Files.isRegularFile(renderedPdf)) {
                return List.of();
            }
            return parsePdfBlocks(renderedPdf);
        } catch (IOException | InterruptedException exception) {
            if (exception instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return List.of();
        } finally {
            if (renderedPdf != null) {
                try { Files.deleteIfExists(renderedPdf); } catch (IOException ignored) { }
            }
        }
    }

    private static Path resolveDocxRenderScript() {
        Path cwd = Path.of(System.getProperty("user.dir", ".")).toAbsolutePath().normalize();
        for (Path candidate : List.of(
                cwd.resolve("scripts/local/render-docx-to-pdf.ps1"),
                cwd.resolve("../scripts/local/render-docx-to-pdf.ps1"))) {
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("DOCX page renderer script is unavailable");
    }

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
        List<StoredAssetReference> storedAssets = storeAssets(document, sourcePath, parsed);
        List<FormulaReference> formulas = formulaReferences(parsed.formulas());
        formulas.addAll(bindFormulaAssets(pageFormulas, storedAssets));
        String formulaRefs = formulaRefs(formulas);
        String formulaEvidence = formulaEvidence(formulas);
        /*
         * The canonical formula text belongs in normalizedText because this exact value is embedded into Milvus.
         * Keeping rawText unmodified preserves the source extraction for display, while a formula-only paragraph is
         * still indexable and receives the same graph/rerank treatment as ordinary teaching text.
         */
        String normalized = normalizeText(String.join(" ", parsed.text(), formulaEvidence));
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
                normalizeText(String.join(" ", parsed.text(), formulaEvidence)),
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
                parsed.pageNo(),
                null,
                sourcePath,
                blockRole,
                parsed.text(),
                normalized,
                imageRefs,
                formulaRefs,
                jsonArray(graphAlignment.nodeIds()),
                jsonArray(graphAlignment.tagNames()),
                sha256(normalized),
                1.0,
                "active");
    }

    /**
     * Keeps a stable source-side block key for incremental sync. The MySQL auto id may change when rows are inserted,
     * but this external id must stay derivable from the real source structure so updates can reconcile in place.
     */
    private static String stableExternalBlockId(String sourcePath, ParsedBlock parsed, int order) {
        String chapter = normalizeHeading(parsed.chapter());
        String section = normalizeHeading(parsed.section());
        String page = parsed.pageNo() == null ? "0" : String.valueOf(parsed.pageNo());
        return sourcePath + "|" + page + "|" + chapter + "|" + section + "|" + order;
    }

    /**
     * Classifies one parsed block into a small set of generic roles used by stage-two in-document rerank. Keep this
     * broad and source-driven; do not inject benchmark keywords or per-dataset rules here.
     */
    private static String classifyBlockRole(String sourcePath, ParsedBlock parsed, String normalizedText) {
        String haystack = normalizeText(String.join(
                " ",
                textOrDefault(sourcePath, ""),
                textOrDefault(parsed.chapter(), ""),
                textOrDefault(parsed.section(), ""),
                textOrDefault(normalizedText, ""))).toLowerCase(Locale.ROOT);
        if (containsAny(haystack, "答案", "解析", "讲评", "点评", "评注", "解答", "solution", "analysis")) {
            return "analysis";
        }
        if (containsAny(haystack, "方法", "讲法", "思路", "策略", "套路", "model", "method")) {
            return "method";
        }
        if (containsAny(haystack, "板书", "板演", "blackboard")) {
            return "boardwork";
        }
        if (containsAny(haystack, "模板", "讲义模板", "template")) {
            return "template";
        }
        if (containsAny(haystack, "提示", "提醒", "易错", "注意", "tip")) {
            return "tip";
        }
        if (containsAny(haystack, "真题", "模拟", "试题", "题目", "例题", "question", "exam")) {
            return "question";
        }
        if (containsAny(haystack, "专题", "讲义", "课堂", "notes", "lesson")) {
            return "lesson";
        }
        return "reference";
    }

    /**
     * Normalizes text for retrieval and checksum stability.
     */
    private static String normalizeText(String text) {
        return text == null ? "" : text.replaceAll("\\s+", " ").strip();
    }

    private static String normalizeHeading(String value) {
        return normalizeText(textOrDefault(value, ""))
                .replace('|', '/')
                .replace('#', '/');
    }

    private static String jsonArray(List<String> values) {
        try {
            return OBJECT_MAPPER.writeValueAsString(values == null ? List.of() : values);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Failed to serialize graph alignment metadata", exception);
        }
    }

    /**
     * Persists extracted images and converts them to safe asset references carried by block imageRefs.
     */
    private List<StoredAssetReference> storeAssets(
            TeacherResourceDocumentResponse document,
            String sourcePath,
            ParsedBlock parsed) {
        if (parsed.assets().isEmpty()) {
            return List.of();
        }
        List<StoredAssetReference> refs = new ArrayList<>();
        for (PendingAsset pending : parsed.assets()) {
            Optional<com.doob.mathagent.teacher.vo.TeacherResourceAssetResponse> saved =
                    assetService.saveExtractedAsset(
                            document,
                            sourcePath,
                            parsed.pageNo(),
                            pending.providerAssetId(),
                            pending.content(),
                            pending.mimeType());
            saved.ifPresent(asset -> {
                refs.add(new StoredAssetReference(
                        asset.assetId(),
                        asset.pageNo(),
                        textOrDefault(asset.sourcePath(), ""),
                        textOrDefault(asset.mimeType(), "application/octet-stream"),
                        pending.content()));
            });
        }
        return List.copyOf(refs);
    }

    private static String imageRefs(List<StoredAssetReference> assets) {
        if (assets == null || assets.isEmpty()) {
            return "[]";
        }
        List<Map<String, Object>> refs = new ArrayList<>();
        for (StoredAssetReference asset : assets) {
            Map<String, Object> ref = new LinkedHashMap<>();
            ref.put("assetId", asset.assetId());
            ref.put("pageNo", asset.pageNo());
            ref.put("sourcePath", asset.sourcePath());
            ref.put("mimeType", asset.mimeType());
            refs.add(ref);
        }
        try {
            return OBJECT_MAPPER.writeValueAsString(refs);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Failed to serialize teacher resource imageRefs", exception);
        }
    }

    /**
     * Serializes native DOCX equations into the existing JSON column rather than adding a parallel formula table.
     *
     * <p>{@code omml} is lossless source, {@code mathMl} is renderer-friendly structure, and {@code plainText} is
     * the only retrieval evidence. No LaTex is fabricated when a verified converter is unavailable.</p>
     */
    private static List<FormulaReference> formulaReferences(List<OmmlFormulaExtractor.ExtractedFormula> formulas) {
        if (formulas == null || formulas.isEmpty()) {
            return new ArrayList<>();
        }
        List<FormulaReference> refs = new ArrayList<>();
        for (OmmlFormulaExtractor.ExtractedFormula formula : formulas) {
            refs.add(new FormulaReference(
                    "docx_omml",
                    "verified_native",
                    1.0d,
                    formula.omml(),
                    formula.mathMl(),
                    formula.latex(),
                    formula.plainText(),
                    null,
                    null));
        }
        return refs;
    }

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
            if (parsed.pageNo() != null && !parsed.assets().isEmpty()) {
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

    private static List<FormulaReference> bindFormulaAssets(
            List<FormulaReference> formulas,
            List<StoredAssetReference> assets) {
        if (formulas == null || formulas.isEmpty() || assets == null || assets.isEmpty()) {
            return formulas == null ? List.of() : formulas;
        }
        String assetId = assets.getFirst().assetId();
        return formulas.stream().map(formula -> new FormulaReference(
                formula.source(), formula.recognitionStatus(), formula.confidence(), formula.omml(), formula.mathMl(),
                formula.latex(), formula.plainText(), assetId, formula.model())).toList();
    }

    private static String formulaRefs(List<FormulaReference> formulas) {
        if (formulas == null || formulas.isEmpty()) {
            return "[]";
        }
        List<Map<String, Object>> refs = new ArrayList<>();
        for (FormulaReference formula : formulas) {
            Map<String, Object> ref = new LinkedHashMap<>();
            ref.put("source", formula.source());
            ref.put("recognitionStatus", formula.recognitionStatus());
            ref.put("confidence", formula.confidence());
            putIfPresent(ref, "omml", formula.omml());
            putIfPresent(ref, "mathMl", formula.mathMl());
            putIfPresent(ref, "latex", formula.latex());
            putIfPresent(ref, "plainText", formula.plainText());
            putIfPresent(ref, "assetId", formula.assetId());
            putIfPresent(ref, "model", formula.model());
            refs.add(ref);
        }
        try {
            return OBJECT_MAPPER.writeValueAsString(refs);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Failed to serialize DOCX formula references", exception);
        }
    }

    private static void putIfPresent(Map<String, Object> target, String key, String value) {
        if (value != null && !value.isBlank()) {
            target.put(key, value);
        }
    }

    private static String formulaEvidence(List<FormulaReference> formulas) {
        if (formulas == null || formulas.isEmpty()) {
            return "";
        }
        return formulas.stream()
                .map(FormulaReference::plainText)
                .filter(value -> value != null && !value.isBlank())
                .collect(java.util.stream.Collectors.joining(" "));
    }

    /**
     * Computes a SHA-256 checksum for parsed content.
     */
    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 algorithm is unavailable", exception);
        }
    }

    /**
     * Removes the last file extension for fallback chapter names.
     */
    private static String stripExtension(String fileName) {
        int dotIndex = fileName.lastIndexOf('.');
        return dotIndex <= 0 ? fileName : fileName.substring(0, dotIndex);
    }

    /**
     * Verifies teacher/admin role.
     */
    private static void requireTeacherOrAdmin(String viewerRole) {
        if (!"teacher".equals(viewerRole) && !"admin".equals(viewerRole)) {
            throw new IllegalArgumentException("Teacher source sync execution requires teacher or admin role");
        }
    }

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

    /**
     * Creates an updated job response while preserving immutable fields.
     */
    private static TeacherSourceSyncJobResponse updateJob(
            TeacherSourceSyncJobResponse job,
            String status,
            String phase,
            String stagingPath,
            String message) {
        return new TeacherSourceSyncJobResponse(
                job.jobId(),
                job.documentId(),
                job.tenantId(),
                job.sourceType(),
                job.operation(),
                status,
                phase,
                job.attempt(),
                job.createdBy(),
                stagingPath == null ? job.stagingPath() : stagingPath,
                message,
                job.createdAt(),
                Instant.now().toString());
    }

    /**
     * Returns stripped text or fallback.
     */
    private static String textOrDefault(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value.strip();
    }

    private static String requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value.strip();
    }

    private static boolean containsAny(String haystack, String... needles) {
        for (String needle : needles) {
            if (!needle.isBlank() && haystack.contains(needle.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Internal parsed block model.
     */
    private record ParsedBlock(
            String chapter,
            String section,
            Integer pageNo,
            String text,
            List<PendingAsset> assets,
            List<OmmlFormulaExtractor.ExtractedFormula> formulas) {
    }

    private record PendingAsset(String providerAssetId, byte[] content, String mimeType) {
    }

    /** Asset bytes are retained only through this sync call; durable references remain opaque asset ids in MySQL. */
    private record StoredAssetReference(
            String assetId,
            Integer pageNo,
            String sourcePath,
            String mimeType,
            byte[] content) {
    }

    /** Internal representation serialized into the existing document_block.formula_refs JSON column. */
    private record FormulaReference(
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
    private static final class FormulaVisionBudget {

        private final boolean enabled;
        private final int maximum;
        private int consumed;

        private FormulaVisionBudget(boolean enabled, int maximum) {
            this.enabled = enabled;
            this.maximum = maximum;
        }

        private static FormulaVisionBudget forDocument(
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

