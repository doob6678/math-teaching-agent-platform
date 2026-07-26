package com.doob.mathagent.student.service;

import com.doob.mathagent.infrastructure.security.RequestSubject;
import com.doob.mathagent.resources.ProjectResourceProperties;
import com.doob.mathagent.student.vo.StudentExplanationImageUploadResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

/**
 * Stores temporary student explanation images on local disk with owner binding and expiration.
 */
@Service
public class StudentExplanationImageStoreService {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final Duration DEFAULT_TTL = Duration.ofMinutes(30);
    private static final long DEFAULT_MAX_BYTES = 8L * 1024L * 1024L;
    /** Upload only stores the original image; the explanation model receives it directly when the user submits. */
    private static final String IMAGE_STATUS = "image_uploaded_for_direct_context";

    private final ProjectResourceProperties resourceProperties;
    private final Clock clock;
    private final Duration ttl;
    private final long maxBytes;
    private final Map<String, StudentExplanationImageRecord> records = new ConcurrentHashMap<>();

    /**
     * Creates the production image store.
     *
     * @param resourceProperties configured local storage paths
     * @param ttlMinutes temporary file lifetime in minutes
     * @param maxBytes maximum accepted image size
     */
    @Autowired
    public StudentExplanationImageStoreService(
            ProjectResourceProperties resourceProperties,
            @Value("${math-agent.student.explanation.image-ttl-minutes:30}") long ttlMinutes,
            @Value("${math-agent.student.explanation.max-image-bytes:8388608}") long maxBytes) {
        this(resourceProperties, Clock.systemUTC(), Duration.ofMinutes(Math.max(1, ttlMinutes)), maxBytes);
    }

    /**
     * Creates a testable image store.
     *
     * @param resourceProperties configured local storage paths
     * @param clock clock used for expiration
     * @param ttl temporary file lifetime
     * @param maxBytes maximum accepted image size
     */
    public StudentExplanationImageStoreService(
            ProjectResourceProperties resourceProperties,
            Clock clock,
            Duration ttl,
            long maxBytes) {
        this.resourceProperties = resourceProperties;
        this.clock = clock == null ? Clock.systemUTC() : clock;
        this.ttl = ttl == null || ttl.isZero() || ttl.isNegative() ? DEFAULT_TTL : ttl;
        this.maxBytes = maxBytes <= 0 ? DEFAULT_MAX_BYTES : maxBytes;
    }

    /**
     * Stores one image file and returns a temporary upload id.
     *
     * @param file multipart image file
     * @param subject backend-resolved owner
     * @return upload metadata
     */
    public StudentExplanationImageUploadResponse save(MultipartFile file, RequestSubject subject) {
        cleanupExpired();
        RequestSubject owner = normalizeSubject(subject);
        validateFile(file);
        String uploadId = UUID.randomUUID().toString();
        String originalFileName = sanitizeFileName(file.getOriginalFilename());
        String contentType = normalizeContentType(file.getContentType());
        Instant createdAt = Instant.now(clock);
        Instant expiresAt = createdAt.plus(ttl);
        Path target = storageDirectory(owner).resolve(uploadId + extension(originalFileName, contentType));
        try {
            Files.createDirectories(target.getParent());
            file.transferTo(target);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to store student explanation image", e);
        }
        StudentExplanationImageRecord record = new StudentExplanationImageRecord(
                uploadId,
                owner.tenantId(),
                owner.subjectType(),
                owner.subjectId(),
                originalFileName,
                contentType,
                file.getSize(),
                target.toAbsolutePath().normalize(),
                createdAt,
                expiresAt);
        records.put(uploadId, record);
        writeMetadata(record);
        return response(record);
    }

    /**
     * Finds a non-expired image owned by the backend subject.
     *
     * @param uploadId temporary upload id
     * @param subject backend-resolved viewer
     * @return visible image record
     */
    public Optional<StudentExplanationImageRecord> findUsable(String uploadId, RequestSubject subject) {
        cleanupExpired();
        if (uploadId == null || uploadId.isBlank()) {
            return Optional.empty();
        }
        RequestSubject viewer = normalizeSubject(subject);
        String normalizedUploadId = uploadId.strip();
        StudentExplanationImageRecord record = records.get(normalizedUploadId);
        if (record == null) {
            record = loadMetadata(viewer, normalizedUploadId).orElse(null);
            if (record != null) {
                records.put(normalizedUploadId, record);
            }
        }
        if (record == null || expired(record)) {
            if (record != null) {
                deleteQuietly(record.localPath());
                deleteQuietly(metadataPath(record));
                records.remove(normalizedUploadId);
            }
            return Optional.empty();
        }
        if (!record.tenantId().equals(viewer.tenantId())
                || !record.subjectType().equals(viewer.subjectType())
                || !record.subjectId().equals(viewer.subjectId())) {
            throw new IllegalArgumentException("Image upload is not owned by the current subject");
        }
        return Optional.of(record);
    }

    /**
     * Removes expired metadata and best-effort deletes expired files.
     *
     * @return number of expired records removed
     */
    public int cleanupExpired() {
        int[] removed = {0};
        records.entrySet().removeIf(entry -> {
            StudentExplanationImageRecord record = entry.getValue();
            if (!expired(record)) {
                return false;
            }
            removed[0] += 1;
            deleteQuietly(record.localPath());
            deleteQuietly(metadataPath(record));
            return true;
        });
        removed[0] += cleanupExpiredMetadataFiles();
        return removed[0];
    }

    /**
     * Returns whether a record is already expired.
     */
    private boolean expired(StudentExplanationImageRecord record) {
        return !Instant.now(clock).isBefore(record.expiresAt());
    }

    /**
     * Persists upload metadata so non-expired temporary uploads survive a backend restart.
     */
    private void writeMetadata(StudentExplanationImageRecord record) {
        try {
            Files.createDirectories(metadataPath(record).getParent());
            OBJECT_MAPPER.writeValue(metadataPath(record).toFile(), ImageMetadata.from(record));
        } catch (IOException e) {
            deleteQuietly(record.localPath());
            throw new IllegalStateException("Failed to persist student explanation image metadata", e);
        }
    }

    /**
     * Loads owner-scoped metadata for an upload id.
     */
    private Optional<StudentExplanationImageRecord> loadMetadata(RequestSubject owner, String uploadId) {
        Path path = storageDirectory(owner).resolve(uploadId + ".json");
        if (!Files.isRegularFile(path)) {
            return Optional.empty();
        }
        try {
            ImageMetadata metadata = OBJECT_MAPPER.readValue(path.toFile(), ImageMetadata.class);
            StudentExplanationImageRecord record = metadata.toRecord();
            if (!record.uploadId().equals(uploadId)
                    || !record.tenantId().equals(owner.tenantId())
                    || !record.subjectType().equals(owner.subjectType())
                    || !record.subjectId().equals(owner.subjectId())) {
                throw new IllegalArgumentException("Image upload metadata is not owned by the current subject");
            }
            if (!Files.isRegularFile(record.localPath())) {
                deleteQuietly(path);
                return Optional.empty();
            }
            return Optional.of(record);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read student explanation image metadata", e);
        }
    }

    /**
     * Removes expired sidecar metadata left from previous backend processes.
     */
    private int cleanupExpiredMetadataFiles() {
        Path root = resourceProperties.localFileStorageRoot().resolve("student-explanation-images");
        if (!Files.isDirectory(root)) {
            return 0;
        }
        int[] removed = {0};
        try (Stream<Path> stream = Files.walk(root, 6)) {
            stream
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".json"))
                    .forEach(path -> {
                        try {
                            ImageMetadata metadata = OBJECT_MAPPER.readValue(path.toFile(), ImageMetadata.class);
                            StudentExplanationImageRecord record = metadata.toRecord();
                            if (expired(record)) {
                                deleteQuietly(record.localPath());
                                deleteQuietly(path);
                                records.remove(record.uploadId());
                                removed[0] += 1;
                            }
                        } catch (IOException e) {
                            deleteQuietly(path);
                            removed[0] += 1;
                        }
                    });
        } catch (IOException e) {
            throw new IllegalStateException("Failed to cleanup student explanation image metadata", e);
        }
        return removed[0];
    }

    /**
     * Returns the owner-scoped sidecar metadata path.
     */
    private Path metadataPath(StudentExplanationImageRecord record) {
        return storageDirectory(new RequestSubject(
                record.tenantId(),
                record.subjectType(),
                record.subjectId(),
                null)).resolve(record.uploadId() + ".json");
    }

    /**
     * Converts a record to frontend upload metadata.
     */
    private static StudentExplanationImageUploadResponse response(StudentExplanationImageRecord record) {
        return new StudentExplanationImageUploadResponse(
                record.uploadId(),
                record.originalFileName(),
                record.contentType(),
                record.sizeBytes(),
                record.expiresAt(),
                IMAGE_STATUS);
    }

    /**
     * Validates multipart file size and image MIME type.
     */
    private void validateFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("Image file is required");
        }
        if (file.getSize() > maxBytes) {
            throw new IllegalArgumentException("Image file exceeds max size");
        }
        String contentType = normalizeContentType(file.getContentType());
        if (!contentType.startsWith("image/")) {
            throw new IllegalArgumentException("Only image uploads are supported");
        }
    }

    /**
     * Builds the owner-scoped storage directory under the configured local file root.
     */
    private Path storageDirectory(RequestSubject subject) {
        return resourceProperties.localFileStorageRoot()
                .resolve("student-explanation-images")
                .resolve(safePathSegment(subject.tenantId()))
                .resolve(safePathSegment(subject.subjectType()))
                .resolve(safePathSegment(subject.subjectId()))
                .toAbsolutePath()
                .normalize();
    }

    /**
     * Returns a normalized backend subject that can own uploads.
     */
    private static RequestSubject normalizeSubject(RequestSubject subject) {
        RequestSubject normalized = requireSubject(subject).normalize();
        if (normalized.subjectId() == null || normalized.subjectId().isBlank()) {
            throw new IllegalArgumentException("Authenticated subject is required for image upload");
        }
        return normalized;
    }

    /**
     * Requires backend-resolved identity; callers that need a test subject must pass it explicitly.
     */
    private static RequestSubject requireSubject(RequestSubject subject) {
        if (subject == null) {
            throw new IllegalArgumentException("Request subject is required");
        }
        return subject;
    }

    /**
     * Normalizes a MIME type.
     */
    private static String normalizeContentType(String contentType) {
        return contentType == null || contentType.isBlank()
                ? "application/octet-stream"
                : contentType.strip().toLowerCase(Locale.ROOT);
    }

    /**
     * Sanitizes browser-provided file names and removes any path fragments.
     */
    private static String sanitizeFileName(String originalFileName) {
        String value = originalFileName == null || originalFileName.isBlank()
                ? "upload-image"
                : originalFileName.replace('\\', '/');
        int slash = value.lastIndexOf('/');
        String fileName = slash >= 0 ? value.substring(slash + 1) : value;
        return fileName.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    /**
     * Selects a safe extension from file name or MIME type.
     */
    private static String extension(String fileName, String contentType) {
        int dot = fileName.lastIndexOf('.');
        if (dot >= 0 && dot < fileName.length() - 1) {
            String ext = fileName.substring(dot).toLowerCase(Locale.ROOT);
            if (ext.matches("\\.[a-z0-9]{1,8}")) {
                return ext;
            }
        }
        return switch (contentType) {
            case "image/jpeg" -> ".jpg";
            case "image/png" -> ".png";
            case "image/webp" -> ".webp";
            case "image/gif" -> ".gif";
            default -> ".img";
        };
    }

    /**
     * Converts text to a safe single path segment.
     */
    private static String safePathSegment(String value) {
        return (value == null || value.isBlank() ? "unknown" : value.strip())
                .replaceAll("[^A-Za-z0-9._-]", "_");
    }

    /**
     * Best-effort delete that must not break request handling.
     */
    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // Cleanup is opportunistic; the TTL metadata still prevents reuse.
        }
    }

    /**
     * Disk-serialized upload metadata.
     */
    private record ImageMetadata(
            String uploadId,
            String tenantId,
            String subjectType,
            String subjectId,
            String originalFileName,
            String contentType,
            long sizeBytes,
            String localPath,
            String createdAt,
            String expiresAt) {

        private static ImageMetadata from(StudentExplanationImageRecord record) {
            return new ImageMetadata(
                    record.uploadId(),
                    record.tenantId(),
                    record.subjectType(),
                    record.subjectId(),
                    record.originalFileName(),
                    record.contentType(),
                    record.sizeBytes(),
                    record.localPath().toString(),
                    record.createdAt().toString(),
                    record.expiresAt().toString());
        }

        private StudentExplanationImageRecord toRecord() {
            return new StudentExplanationImageRecord(
                    uploadId,
                    tenantId,
                    subjectType,
                    subjectId,
                    originalFileName,
                    contentType,
                    sizeBytes,
                    Path.of(localPath).toAbsolutePath().normalize(),
                    Instant.parse(createdAt),
                    Instant.parse(expiresAt));
        }
    }
}
