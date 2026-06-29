package com.doob.mathagent.teacher.service;

import com.doob.mathagent.teacher.vo.TeacherDocumentBlockResponse;
import com.doob.mathagent.teacher.vo.TeacherResourceDocumentResponse;
import com.doob.mathagent.teacher.vo.TeacherSourceSyncJobResponse;
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
import java.util.UUID;
import java.util.stream.Stream;
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
            TeacherSourceSyncProperties syncProperties) {
        this.resourceStore = resourceStore;
        this.jobStore = jobStore;
        this.blockStore = blockStore;
        this.feishuDownloadClient = feishuDownloadClient;
        this.syncProperties = syncProperties;
    }

    /**
     * Creates a sync execution service for focused unit tests that do not exercise Feishu downloading.
     *
     * @param resourceStore source document store
     * @param jobStore sync job store
     * @param blockStore parsed block store
     */
    public TeacherSourceSyncExecutionService(
            TeacherResourceStore resourceStore,
            TeacherSourceSyncJobStore jobStore,
            TeacherDocumentBlockStore blockStore) {
        this(
                resourceStore,
                jobStore,
                blockStore,
                new UnconfiguredTeacherFeishuDownloadClient(),
                TeacherSourceSyncProperties.defaults());
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
        String normalizedTenantId = textOrDefault(tenantId, "default");
        String normalizedRole = textOrDefault(viewerRole, "teacher").toLowerCase(Locale.ROOT);
        String normalizedSubjectId = textOrDefault(viewerSubjectId, "local-teacher-console");
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
                TeacherFeishuDownloadClient.FeishuDownloadResult result = feishuDownloadClient.download(
                        textOrDefault(document.originalUrl(), syncProperties.feishuDefaultUrl()),
                        syncProperties.feishuStagingRoot(),
                        syncProperties.feishuSmokeMaxFiles());
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
                        document.previewFiles());
                resourceStore.save(downloaded);
                TeacherSourceSyncJobResponse completed = updateJob(
                        running,
                        "completed",
                        "download_completed",
                        result.savedPath().toString(),
                        result.message());
                return jobStore.save(completed);
            }
            List<TeacherDocumentBlockResponse> blocks = parseLocalResource(document);
            blockStore.replaceActiveBlocks(document.tenantId(), document.documentId(), blocks);
            markLocalResourceSynced(document);
            TeacherSourceSyncJobResponse completed = updateJob(
                    running,
                    "completed",
                    "parse_completed",
                    null,
                    "Parsed " + blocks.size() + " blocks from local source");
            return jobStore.save(completed);
        } catch (RuntimeException exception) {
            TeacherSourceSyncJobResponse failed = updateJob(
                    running,
                    "failed",
                    feishuSource ? "download_failed" : "parse_failed",
                    null,
                    exception.getMessage());
            return jobStore.save(failed);
        }
    }

    /**
     * Marks a local resource as parsed while keeping embedding/index rebuild pending.
     */
    private void markLocalResourceSynced(TeacherResourceDocumentResponse document) {
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
                document.previewFiles());
        resourceStore.save(synced);
    }

    /**
     * Parses a local teacher resource into document blocks.
     */
    private static List<TeacherDocumentBlockResponse> parseLocalResource(TeacherResourceDocumentResponse document) {
        if (!"local_path".equalsIgnoreCase(textOrDefault(document.sourceType(), ""))) {
            throw new IllegalArgumentException("Only local_path sync execution is supported in this stage");
        }
        Path root = Path.of(textOrDefault(document.localPath(), ""));
        if (!Files.exists(root)) {
            throw new IllegalArgumentException("Local resource path does not exist: " + root);
        }
        List<Path> files = listSupportedFiles(root);
        if (files.isEmpty()) {
            throw new IllegalArgumentException("Local resource path contains no .md or .txt files: " + root);
        }
        List<TeacherDocumentBlockResponse> blocks = new ArrayList<>();
        int order = 0;
        for (Path file : files) {
            String text = readUtf8(file);
            String relativePath = root.equals(file) ? file.getFileName().toString() : root.relativize(file).toString();
            for (ParsedBlock parsed : parseTextBlocks(text, file)) {
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
     * Checks whether the file can be parsed by the current local sync baseline.
     */
    private static boolean isSupportedFile(Path file) {
        String fileName = file.getFileName().toString().toLowerCase(Locale.ROOT);
        return fileName.endsWith(".md") || fileName.endsWith(".txt");
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
            blocks.add(new ParsedBlock(chapter, section, value));
        }
        current.setLength(0);
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
                null,
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

    /**
     * Internal parsed block model.
     */
    private record ParsedBlock(String chapter, String section, String text) {
    }
}
