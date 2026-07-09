package com.doob.mathagent.teacher.service;

import com.doob.mathagent.infrastructure.security.RequestSubject;
import com.doob.mathagent.resources.ProjectResourceProperties;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

/**
 * Stores teacher/admin uploads under a backend-owned root before the existing local sync pipeline parses them.
 *
 * <p>This service deliberately does not create a parallel ingestion system. Its only job is to take browser-uploaded
 * files, write them into a safe server directory, and hand that directory back to the existing
 * {@link TeacherResourceService} + {@link TeacherSourceSyncExecutionService} chain as a normal {@code local_path}
 * source.</p>
 */
@Service
public class TeacherResourceUploadService {

    private static final long DEFAULT_MAX_TOTAL_BYTES = 256L * 1024L * 1024L;
    private static final int DEFAULT_MAX_STORED_FILES = 512;

    private final ProjectResourceProperties resourceProperties;
    private final Clock clock;
    private final long maxTotalBytes;
    private final int maxStoredFiles;
    private final boolean enabled;

    @Autowired
    public TeacherResourceUploadService(
            ProjectResourceProperties resourceProperties,
            @Value("${math-agent.teacher.upload.max-total-bytes:268435456}") long maxTotalBytes,
            @Value("${math-agent.teacher.upload.max-stored-files:512}") int maxStoredFiles) {
        this(resourceProperties, Clock.systemUTC(), maxTotalBytes, maxStoredFiles, true);
    }

    public TeacherResourceUploadService(
            ProjectResourceProperties resourceProperties,
            Clock clock,
            long maxTotalBytes,
            int maxStoredFiles) {
        this(resourceProperties, clock, maxTotalBytes, maxStoredFiles, true);
    }

    private TeacherResourceUploadService(
            ProjectResourceProperties resourceProperties,
            Clock clock,
            long maxTotalBytes,
            int maxStoredFiles,
            boolean enabled) {
        this.resourceProperties = resourceProperties;
        this.clock = clock == null ? Clock.systemUTC() : clock;
        this.maxTotalBytes = maxTotalBytes <= 0 ? DEFAULT_MAX_TOTAL_BYTES : maxTotalBytes;
        this.maxStoredFiles = maxStoredFiles <= 0 ? DEFAULT_MAX_STORED_FILES : maxStoredFiles;
        this.enabled = enabled;
    }

    /**
     * Returns a disabled upload service for constructor paths that do not need upload support in focused tests.
     */
    public static TeacherResourceUploadService disabled() {
        return new TeacherResourceUploadService(null, Clock.systemUTC(), DEFAULT_MAX_TOTAL_BYTES, DEFAULT_MAX_STORED_FILES, false);
    }

    /**
     * Saves uploaded files into an owner-scoped directory and returns the server-managed local path.
     *
     * <p>ZIP packages are expanded server-side because the sync parser expects a real directory tree, while browser
     * folder uploads can preserve subdirectories through {@code MultipartFile#getOriginalFilename()}.</p>
     */
    public StoredUpload store(List<MultipartFile> files, RequestSubject subject) {
        if (!enabled) {
            throw new IllegalStateException("Teacher resource upload service is disabled in this context");
        }
        RequestSubject owner = normalizeTeacherOrAdmin(subject);
        List<MultipartFile> normalizedFiles = files == null
                ? List.of()
                : files.stream().filter(file -> file != null && !file.isEmpty()).toList();
        if (normalizedFiles.isEmpty()) {
            throw new IllegalArgumentException("At least one upload file is required");
        }
        long declaredBytes = normalizedFiles.stream().mapToLong(MultipartFile::getSize).sum();
        if (declaredBytes > maxTotalBytes) {
            throw new IllegalArgumentException("Teacher resource upload exceeds max size of " + maxTotalBytes + " bytes");
        }
        /*
         * Capture the human-readable source name before files are copied into a UUID staging directory. Once the upload
         * lands on disk, the managed folder path is intentionally opaque and no longer suitable as the display title.
         */
        String suggestedTitle = TeacherResourceTitleResolver.deriveFromUploadPaths(
                normalizedFiles.stream().map(MultipartFile::getOriginalFilename).toList());
        Path root = storageRoot(owner).resolve(UUID.randomUUID().toString()).toAbsolutePath().normalize();
        try {
            Files.createDirectories(root);
            Counter counter = new Counter();
            for (MultipartFile file : normalizedFiles) {
                storeOne(file, root, counter);
            }
            if (counter.storedFiles == 0) {
                throw new IllegalArgumentException("Teacher resource upload produced no usable files");
            }
            return new StoredUpload(root, counter.storedFiles, counter.storedBytes, suggestedTitle);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to store teacher resource upload", exception);
        }
    }

    private void storeOne(MultipartFile file, Path root, Counter counter) throws IOException {
        String originalFileName = sanitizeRelativePath(file.getOriginalFilename());
        if (originalFileName.isBlank()) {
            throw new IllegalArgumentException("Uploaded file name is required");
        }
        if (isZip(originalFileName, file.getContentType())) {
            /*
             * ZIP is expanded before registration so sync jobs keep working with the same recursive local-folder parser
             * path used by existing manually configured local resources.
             */
            unzipInto(file.getInputStream(), zipTargetRoot(root, originalFileName), counter);
            return;
        }
        writeFile(root, originalFileName, file.getInputStream(), counter);
    }

    private void unzipInto(InputStream inputStream, Path zipRoot, Counter counter) throws IOException {
        Files.createDirectories(zipRoot);
        try (ZipInputStream zipInputStream = new ZipInputStream(inputStream)) {
            ZipEntry entry;
            while ((entry = zipInputStream.getNextEntry()) != null) {
                String normalizedEntry = sanitizeRelativePath(entry.getName());
                if (normalizedEntry.isBlank()) {
                    continue;
                }
                Path target = safeResolve(zipRoot, normalizedEntry);
                if (entry.isDirectory()) {
                    Files.createDirectories(target);
                    continue;
                }
                Files.createDirectories(target.getParent());
                counter.beforeWrite();
                try (OutputStream outputStream = Files.newOutputStream(target)) {
                    long written = zipInputStream.transferTo(outputStream);
                    counter.afterWrite(written);
                }
            }
        }
    }

    private void writeFile(Path root, String relativePath, InputStream inputStream, Counter counter) throws IOException {
        Path target = safeResolve(root, relativePath);
        Files.createDirectories(target.getParent());
        counter.beforeWrite();
        try (InputStream in = inputStream; OutputStream outputStream = Files.newOutputStream(target)) {
            long written = in.transferTo(outputStream);
            counter.afterWrite(written);
        }
    }

    private static Path zipTargetRoot(Path root, String originalFileName) {
        String fileName = Path.of(originalFileName).getFileName().toString();
        String stem = fileName.replaceAll("(?i)\\.zip$", "");
        return safeResolve(root, stem.isBlank() ? "archive" : stem);
    }

    private static boolean isZip(String originalFileName, String contentType) {
        String fileName = originalFileName == null ? "" : originalFileName.toLowerCase(Locale.ROOT);
        String mimeType = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT);
        return fileName.endsWith(".zip")
                || "application/zip".equals(mimeType)
                || "application/x-zip-compressed".equals(mimeType);
    }

    private Path storageRoot(RequestSubject owner) {
        return resourceProperties.localFileStorageRoot()
                .resolve("teacher-resource-uploads")
                .resolve(owner.tenantId())
                .resolve(owner.subjectType())
                .resolve(owner.subjectId());
    }

    /**
     * Normalizes browser-provided relative paths while rejecting absolute paths and traversal attempts.
     */
    private static String sanitizeRelativePath(String originalFileName) {
        if (originalFileName == null || originalFileName.isBlank()) {
            return "";
        }
        String normalized = originalFileName.strip()
                .replace('\\', '/')
                .replaceAll("^[A-Za-z]:", "")
                .replaceAll("^/+", "");
        String[] segments = normalized.split("/");
        java.util.ArrayList<String> kept = new java.util.ArrayList<>();
        for (String segment : segments) {
            String value = segment == null ? "" : segment.strip();
            if (value.isBlank() || ".".equals(value)) {
                continue;
            }
            if ("..".equals(value)) {
                throw new IllegalArgumentException("Uploaded file path cannot escape the managed resource root");
            }
            kept.add(value.replace(':', '_'));
        }
        return String.join("/", kept);
    }

    private static Path safeResolve(Path root, String relativePath) {
        Path resolved = root.resolve(relativePath).normalize();
        if (!resolved.startsWith(root)) {
            throw new IllegalArgumentException("Uploaded file path escapes the managed resource root");
        }
        return resolved;
    }

    private RequestSubject normalizeTeacherOrAdmin(RequestSubject subject) {
        if (subject == null) {
            throw new IllegalArgumentException("Authenticated subject is required for teacher resource upload");
        }
        RequestSubject normalized = subject.normalize();
        if (!"teacher".equals(normalized.subjectType()) && !"admin".equals(normalized.subjectType())) {
            throw new IllegalArgumentException("Teacher resource upload requires teacher or admin role");
        }
        return normalized;
    }

    /**
     * Stored upload metadata returned to the controller so registration can reuse the managed local path.
     */
    public record StoredUpload(Path rootPath, int storedFileCount, long storedBytes, String suggestedTitle) {
    }

    private final class Counter {
        private int storedFiles;
        private long storedBytes;

        private void beforeWrite() {
            if (storedFiles >= maxStoredFiles) {
                throw new IllegalArgumentException("Teacher resource upload exceeds max file count of " + maxStoredFiles);
            }
            storedFiles += 1;
        }

        private void afterWrite(long writtenBytes) {
            storedBytes += Math.max(0L, writtenBytes);
            if (storedBytes > maxTotalBytes) {
                throw new IllegalArgumentException("Teacher resource upload exceeds max size of " + maxTotalBytes + " bytes");
            }
        }
    }
}
