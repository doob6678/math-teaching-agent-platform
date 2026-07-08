package com.doob.mathagent.teaching.service;

import com.doob.mathagent.teaching.TeachingRequestContext;
import com.doob.mathagent.teaching.dto.TeachingHandoutBatchExportRequest;
import com.doob.mathagent.teaching.vo.TeachingHandoutBatchExportResponse;
import com.doob.mathagent.teaching.vo.TeachingTaskResponse;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Creates and stores short-lived ZIP packages for protected batch handout exports.
 */
@Service
public class TeachingHandoutBatchExportService {

    private static final Duration DEFAULT_TTL = Duration.ofMinutes(30);

    private final TeachingHandoutPdfExportService pdfExportService;
    private final Clock clock;
    private final Duration ttl;
    private final Map<String, TeachingHandoutBatchExportRecord> records = new ConcurrentHashMap<>();

    /**
     * Creates a production batch export service with a 30-minute temporary file TTL.
     *
     * @param pdfExportService PDF renderer reused for each task in the package
     */
    @Autowired
    public TeachingHandoutBatchExportService(
            TeachingHandoutPdfExportService pdfExportService,
            @Value("${math-agent.teaching.handout.batch-zip-ttl-minutes:30}") long ttlMinutes) {
        this(pdfExportService, Clock.systemUTC(), Duration.ofMinutes(Math.max(1, ttlMinutes)));
    }

    /**
     * Creates a testable batch export service with explicit clock and TTL.
     *
     * @param pdfExportService PDF renderer reused for each task in the package
     * @param clock clock used to calculate and verify expiration
     * @param ttl temporary ZIP lifetime
     */
    public TeachingHandoutBatchExportService(
            TeachingHandoutPdfExportService pdfExportService,
            Clock clock,
            Duration ttl) {
        this.pdfExportService = pdfExportService;
        this.clock = clock;
        this.ttl = ttl == null || ttl.isNegative() || ttl.isZero() ? DEFAULT_TTL : ttl;
    }

    /**
     * Creates a temporary ZIP for tasks already loaded through backend ownership checks.
     *
     * @param request normalized export request
     * @param context backend request context used to bind the temporary file owner
     * @param tasks owned teaching tasks to package
     * @return public batch metadata
     */
    public TeachingHandoutBatchExportResponse create(
            TeachingHandoutBatchExportRequest request,
            TeachingRequestContext context,
            List<TeachingTaskResponse> tasks) {
        cleanupExpired();
        TeachingHandoutBatchExportRequest normalized = request.normalize();
        if (tasks == null || tasks.isEmpty()) {
            throw new IllegalArgumentException("No owned teaching tasks selected for batch export");
        }
        String batchId = UUID.randomUUID().toString();
        Instant expiresAt = Instant.now(clock).plus(ttl);
        List<String> taskIds = tasks.stream().map(TeachingTaskResponse::taskId).toList();
        List<String> folderPaths = sanitizeFolderPaths(normalized.folderPaths());
        TeachingRequestContext normalizedContext = context.normalize();
        TeachingHandoutBatchExportResponse response = new TeachingHandoutBatchExportResponse(
                batchId,
                "COMPLETED",
                normalizedContext.subjectType(),
                normalized.taskIds().size(),
                taskIds.size(),
                taskIds,
                normalized.folderIds(),
                folderPaths,
                expiresAt);
        records.put(batchId, new TeachingHandoutBatchExportRecord(
                response,
                normalizedContext.ownerKey(),
                zipBytes(response, tasks)));
        return response;
    }

    /**
     * Finds a non-expired temporary ZIP for the current backend owner.
     *
     * @param batchId temporary package id
     * @param context backend request context
     * @return matching record, empty when missing, expired, or owned by another subject
     */
    public Optional<TeachingHandoutBatchExportRecord> findDownload(String batchId, TeachingRequestContext context) {
        cleanupExpired();
        TeachingHandoutBatchExportRecord record = records.get(batchId);
        if (record == null || isExpired(record)) {
            records.remove(batchId);
            return Optional.empty();
        }
        if (!record.ownerKey().equals(context.normalize().ownerKey())) {
            return Optional.empty();
        }
        return Optional.of(record);
    }

    /**
     * Returns true when a batch id exists but has passed its temporary file expiration.
     */
    public boolean isExpired(String batchId) {
        TeachingHandoutBatchExportRecord record = records.get(batchId);
        return record != null && isExpired(record);
    }

    /**
     * Builds a ZIP containing LaTeX, lightweight PDF previews, and manifest entries.
     */
    private byte[] zipBytes(TeachingHandoutBatchExportResponse response, List<TeachingTaskResponse> tasks) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (ZipOutputStream zip = new ZipOutputStream(bytes, StandardCharsets.UTF_8)) {
                for (int index = 0; index < tasks.size(); index += 1) {
                    TeachingTaskResponse task = tasks.get(index);
                    String folderPrefix = folderPrefix(response.folderPaths(), index, tasks.size());
                    if (canUseTeacherHandout(response.subjectType())) {
                        put(zip, folderPrefix + task.taskId() + ".tex", sanitizedLatex(task, "teacher").getBytes(StandardCharsets.UTF_8));
                        put(zip, folderPrefix + task.taskId() + ".pdf", pdfExportService.render(task, "teacher"));
                        putVersion(zip, folderPrefix, task, "teacher");
                        putVersion(zip, folderPrefix, task, "lecture");
                    }
                    putVersion(zip, folderPrefix, task, "student");
                }
                put(zip, "manifest.txt", manifest(response).getBytes(StandardCharsets.UTF_8));
            }
            return bytes.toByteArray();
        } catch (java.io.IOException exception) {
            throw new IllegalStateException("Failed to create handout batch ZIP", exception);
        }
    }

    /**
     * Writes one ZIP entry with deterministic entry names.
     */
    private static void put(ZipOutputStream zip, String name, byte[] bytes) throws java.io.IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(bytes);
        zip.closeEntry();
    }

    /**
     * Writes one explicit teacher or student handout version into the ZIP package.
     */
    private void putVersion(
            ZipOutputStream zip,
            String folderPrefix,
            TeachingTaskResponse task,
            String version) throws java.io.IOException {
        String versionPrefix = folderPrefix + version + "/" + task.taskId();
        put(zip, versionPrefix + ".tex", sanitizedLatex(task, version).getBytes(StandardCharsets.UTF_8));
        put(zip, versionPrefix + ".pdf", pdfExportService.render(task, version));
    }

    private static String sanitizedLatex(TeachingTaskResponse task, String version) {
        return TeachingHandoutPdfExportService.sanitizeLatexForExport(task.handoutLatexFor(version));
    }

    /**
     * Creates a compact manifest so exported ZIP files remain auditable offline.
     */
    private static String manifest(TeachingHandoutBatchExportResponse response) {
        return """
                batchId=%s
                status=%s
                subjectType=%s
                requestedCount=%d
                exportedCount=%d
                taskIds=%s
                folderIds=%s
                folderPaths=%s
                expiresAt=%s
                """.formatted(
                response.batchId(),
                response.status(),
                response.subjectType(),
                response.requestedCount(),
                response.exportedCount(),
                response.taskIds(),
                response.folderIds(),
                response.folderPaths(),
                response.expiresAt());
    }

    /**
     * Resolves the ZIP directory prefix for the task at the current request order.
     */
    private static String folderPrefix(List<String> folderPaths, int index, int taskCount) {
        if (folderPaths == null || folderPaths.isEmpty()) {
            return "";
        }
        String folderPath = folderPaths.size() == taskCount && index < folderPaths.size()
                ? folderPaths.get(index)
                : folderPaths.getFirst();
        return folderPath == null || folderPath.isBlank() ? "" : folderPath + "/";
    }

    /**
     * Sanitizes client-provided folder labels before they become ZIP entry names.
     */
    private static List<String> sanitizeFolderPaths(List<String> folderPaths) {
        if (folderPaths == null || folderPaths.isEmpty()) {
            return List.of();
        }
        return folderPaths.stream()
                .map(TeachingHandoutBatchExportService::sanitizeFolderPath)
                .filter(path -> !path.isBlank())
                .distinct()
                .toList();
    }

    /**
     * Normalizes one folder path by dropping drive names and resolving traversal segments.
     */
    private static String sanitizeFolderPath(String folderPath) {
        if (folderPath == null || folderPath.isBlank()) {
            return "";
        }
        ArrayDeque<String> segments = new ArrayDeque<>();
        for (String rawSegment : folderPath.replace("\\", "/").split("/+")) {
            String segment = rawSegment.strip();
            if (segment.isBlank() || ".".equals(segment)) {
                continue;
            }
            if ("..".equals(segment)) {
                if (!segments.isEmpty()) {
                    segments.removeLast();
                }
                continue;
            }
            if (segment.matches("(?i)^[a-z]:$")) {
                continue;
            }
            String safeSegment = segment
                    .replaceAll("[\\p{Cntrl}:*?\"<>|]", "_")
                    .replaceAll("^\\.+$", "")
                    .strip();
            if (!safeSegment.isBlank()) {
                segments.addLast(safeSegment);
            }
        }
        return String.join("/", new ArrayList<>(segments));
    }

    /**
     * Removes expired temporary ZIP records from process memory.
     */
    private void cleanupExpired() {
        records.entrySet().removeIf(entry -> isExpired(entry.getValue()));
    }

    /**
     * Checks expiry using the injected clock.
     */
    private boolean isExpired(TeachingHandoutBatchExportRecord record) {
        return !Instant.now(clock).isBefore(record.response().expiresAt());
    }

    /**
     * Teacher-only handout versions include detailed answers and must not be packaged for students.
     */
    private static boolean canUseTeacherHandout(String subjectType) {
        return "teacher".equalsIgnoreCase(subjectType) || "admin".equalsIgnoreCase(subjectType);
    }
}
