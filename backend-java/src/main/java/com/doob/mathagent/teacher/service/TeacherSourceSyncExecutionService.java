package com.doob.mathagent.teacher.service;

import com.doob.mathagent.teacher.vo.TeacherDocumentBlockResponse;
import com.doob.mathagent.teacher.vo.TeacherResourceDocumentResponse;
import com.doob.mathagent.teacher.vo.TeacherSourceSyncCheckpointResponse;
import com.doob.mathagent.teacher.vo.TeacherSourceSyncJobResponse;
import com.doob.mathagent.vector.service.VectorIndexRebuildResponse;
import com.doob.mathagent.vector.service.VectorIndexService;
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
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Stream;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Executes queued teacher source synchronization jobs.
 */
@Service
public class TeacherSourceSyncExecutionService {

    private static final int MAX_SCAN_DEPTH = 8;

    private final TeacherResourceStore resourceStore;
    private final TeacherSourceSyncJobStore jobStore;
    private final TeacherDocumentBlockStore blockStore;
    private final TeacherFeishuDownloadClient feishuDownloadClient;
    private final TeacherSourceSyncProperties syncProperties;
    private final TeacherSourceSyncCheckpointStore checkpointStore;
    private final VectorIndexService vectorIndexService;

    /**
     * Creates a sync execution service.
     *
     * @param resourceStore source document store
     * @param jobStore sync job store
     * @param blockStore parsed block store
     */
    @Autowired
    public TeacherSourceSyncExecutionService(
            TeacherResourceStore resourceStore,
            TeacherSourceSyncJobStore jobStore,
            TeacherDocumentBlockStore blockStore,
            TeacherFeishuDownloadClient feishuDownloadClient,
            TeacherSourceSyncProperties syncProperties,
            TeacherSourceSyncCheckpointStore checkpointStore,
            VectorIndexService vectorIndexService) {
        this.resourceStore = resourceStore;
        this.jobStore = jobStore;
        this.blockStore = blockStore;
        this.feishuDownloadClient = feishuDownloadClient;
        this.syncProperties = syncProperties;
        this.checkpointStore = checkpointStore;
        this.vectorIndexService = Objects.requireNonNull(vectorIndexService, "vectorIndexService is required");
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
                        document.previewFiles());
                resourceStore.save(downloaded);
                List<TeacherDocumentBlockResponse> blocks = parseResourceFiles(downloaded);
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
                                : result.message() + "; Parsed " + blocks.size() + " blocks" + vectorMessage);
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
            List<TeacherDocumentBlockResponse> blocks = parseResourceFiles(document);
            blockStore.replaceActiveBlocks(document.tenantId(), document.documentId(), blocks);
            TeacherResourceDocumentResponse synced = markLocalResourceSynced(document);
            String vectorMessage = autoRebuildVectorIndex(synced, normalizedRole, normalizedSubjectId);
            TeacherSourceSyncJobResponse completed = updateJob(
                    running,
                    "completed",
                    "parse_completed",
                    null,
                    "Parsed " + blocks.size() + " blocks from local source" + vectorMessage);
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
                document.previewFiles());
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
    private static List<TeacherDocumentBlockResponse> parseResourceFiles(TeacherResourceDocumentResponse document) {
        Path root = Path.of(textOrDefault(document.localPath(), ""));
        if (!Files.exists(root)) {
            throw new IllegalArgumentException("Local resource path does not exist: " + root);
        }
        List<Path> files = listSupportedFiles(root);
        if (files.isEmpty()) {
            throw new IllegalArgumentException("Local resource path contains no supported .md, .txt, .docx, or .pdf files: " + root);
        }
        List<TeacherDocumentBlockResponse> blocks = new ArrayList<>();
        int order = 0;
        for (Path file : files) {
            String relativePath = root.equals(file) ? file.getFileName().toString() : root.relativize(file).toString();
            for (ParsedBlock parsed : parseFileBlocks(file)) {
                blocks.add(toBlock(document.documentId(), relativePath.replace('\\', '/'), parsed, order++));
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
            blocks.add(new ParsedBlock(chapter, section, null, value));
        }
        current.setLength(0);
    }

    /**
     * Parses DOCX paragraphs into ordered blocks while preserving the source file name as chapter.
     */
    private static List<ParsedBlock> parseDocxBlocks(Path file) {
        List<ParsedBlock> blocks = new ArrayList<>();
        String chapter = stripExtension(file.getFileName().toString());
        try (XWPFDocument document = new XWPFDocument(Files.newInputStream(file))) {
            for (XWPFParagraph paragraph : document.getParagraphs()) {
                String text = textOrDefault(paragraph.getText(), "");
                if (!text.isBlank()) {
                    blocks.add(new ParsedBlock(chapter, null, null, text));
                }
            }
        } catch (IOException exception) {
            throw new IllegalArgumentException("Failed to parse DOCX resource file: " + file, exception);
        }
        return blocks;
    }

    /**
     * Parses a PDF into one block per page when extractable text exists.
     */
    private static List<ParsedBlock> parsePdfBlocks(Path file) {
        List<ParsedBlock> blocks = new ArrayList<>();
        String chapter = stripExtension(file.getFileName().toString());
        try (PDDocument document = Loader.loadPDF(file.toFile())) {
            PDFTextStripper stripper = new PDFTextStripper();
            for (int page = 1; page <= document.getNumberOfPages(); page += 1) {
                stripper.setStartPage(page);
                stripper.setEndPage(page);
                String text = textOrDefault(stripper.getText(document), "");
                if (!text.isBlank()) {
                    blocks.add(new ParsedBlock(chapter, null, page, text));
                }
            }
        } catch (IOException exception) {
            throw new IllegalArgumentException("Failed to parse PDF resource file: " + file, exception);
        }
        return blocks;
    }

    /**
     * Converts a parsed text segment to a document block response.
     */
    private static TeacherDocumentBlockResponse toBlock(
            String documentId,
            String relativePath,
            ParsedBlock parsed,
            int order) {
        String normalized = normalizeText(parsed.text());
        return new TeacherDocumentBlockResponse(
                UUID.randomUUID().toString(),
                documentId,
                relativePath + "#" + order,
                relativePath.endsWith(".md") ? "markdown" : "text",
                order,
                parsed.chapter(),
                parsed.section(),
                parsed.pageNo(),
                null,
                parsed.text(),
                normalized,
                "[]",
                "[]",
                sha256(normalized),
                1.0,
                "active");
    }

    /**
     * Normalizes text for retrieval and checksum stability.
     */
    private static String normalizeText(String text) {
        return text == null ? "" : text.replaceAll("\\s+", " ").strip();
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

    /**
     * Internal parsed block model.
     */
    private record ParsedBlock(String chapter, String section, Integer pageNo, String text) {
    }
}
