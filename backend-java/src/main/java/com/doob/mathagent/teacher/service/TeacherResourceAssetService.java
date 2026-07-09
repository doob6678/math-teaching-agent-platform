package com.doob.mathagent.teacher.service;

import com.doob.mathagent.infrastructure.security.RequestSubject;
import com.doob.mathagent.teacher.vo.TeacherResourceAssetResponse;
import com.doob.mathagent.teacher.vo.TeacherResourceBlockSearchResponse;
import com.doob.mathagent.teacher.vo.TeacherResourceDocumentResponse;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import javax.imageio.ImageIO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Persists extracted images/assets and serves them only through backend permission checks.
 */
@Service
public class TeacherResourceAssetService {

    private static final Logger LOGGER = LoggerFactory.getLogger(TeacherResourceAssetService.class);
    private static final String TEACHER_PRIVATE = "TEACHER_PRIVATE";
    private static final java.util.Set<String> SHARED_SCOPES = java.util.Set.of(
            "MATH_VIP",
            "PUBLIC_TEXTBOOK",
            "CLASS_AUTHORIZED");

    private final TeacherResourceAssetStore assetStore;
    private final TeacherResourceStore resourceStore;
    private final TeacherSourceSyncProperties syncProperties;
    private final boolean enabled;

    @Autowired
    public TeacherResourceAssetService(
            TeacherResourceAssetStore assetStore,
            TeacherResourceStore resourceStore,
            TeacherSourceSyncProperties syncProperties) {
        this.assetStore = Objects.requireNonNull(assetStore, "assetStore");
        this.resourceStore = Objects.requireNonNull(resourceStore, "resourceStore");
        this.syncProperties = Objects.requireNonNull(syncProperties, "syncProperties");
        this.enabled = true;
    }

    private TeacherResourceAssetService() {
        this.assetStore = null;
        this.resourceStore = null;
        this.syncProperties = null;
        this.enabled = false;
    }

    /**
     * Disabled asset service used by focused unit tests that do not start the database-backed asset store.
     */
    public static TeacherResourceAssetService disabled() {
        return new TeacherResourceAssetService();
    }

    public void markDocumentAssetsInactive(String tenantId, String documentId) {
        if (enabled) {
            assetStore.markDocumentAssetsInactive(tenantId, documentId);
        }
    }

    /**
     * Saves one extracted binary asset and returns an opaque asset reference for block imageRefs.
     *
     * The provider id is source-structure based, not a filename classifier. This keeps repeated syncs idempotent while
     * avoiding keyword or test-data coupling.
     */
    public Optional<TeacherResourceAssetResponse> saveExtractedAsset(
            TeacherResourceDocumentResponse document,
            String sourcePath,
            Integer pageNo,
            String providerAssetId,
            byte[] content,
            String mimeType) {
        if (!enabled || document == null || content == null || content.length == 0) {
            /*
             * Do not silently drop assets. If this fires in production, the parser found an image/attachment reference
             * but either Spring wired the disabled test service or the extractor produced an empty byte array.
             */
            LOGGER.warn(
                    "Skip teacher resource asset: enabled={}, documentId={}, sourcePath={}, providerAssetId={}, bytes={}",
                    enabled,
                    document == null ? "" : document.documentId(),
                    sourcePath,
                    providerAssetId,
                    content == null ? null : content.length);
            return Optional.empty();
        }
        String checksum = sha256(content);
        String normalizedProviderId = textOrDefault(providerAssetId, sourcePath + ":" + checksum);
        Optional<TeacherResourceAssetResponse> existing = assetStore.findActiveByProviderChecksum(
                document.tenantId(),
                document.documentId(),
                normalizedProviderId,
                checksum);
        if (existing.isPresent()) {
            return existing;
        }
        String assetId = UUID.randomUUID().toString();
        String extension = extensionForMime(mimeType);
        String storageKey = document.tenantId() + "/" + document.documentId() + "/" + assetId + extension;
        Path target = safeStoragePath(storageKey);
        try {
            Files.createDirectories(target.getParent());
            Files.write(target, content);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to persist teacher resource asset", exception);
        }
        ImageSize imageSize = imageSize(content);
        TeacherResourceAssetResponse asset = new TeacherResourceAssetResponse(
                assetId,
                document.tenantId(),
                document.ownerSubjectId(),
                document.documentId(),
                null,
                document.permissionScope(),
                sourcePath,
                pageNo,
                normalizedProviderId,
                checksum,
                textOrDefault(mimeType, "application/octet-stream"),
                imageSize.width(),
                imageSize.height(),
                storageKey,
                "active");
        return Optional.of(assetStore.save(asset));
    }

    /**
     * Opens an asset after applying the same tenant/owner/scope rules used by teacher resource search.
     */
    public VisibleAsset openVisibleAsset(String assetId, RequestSubject subject) {
        if (!enabled) {
            throw new IllegalArgumentException("Teacher resource asset service is not configured");
        }
        RequestSubject normalized = subject.normalize();
        TeacherResourceAssetResponse asset = assetStore.find(normalized.tenantId(), assetId)
                .filter(candidate -> "active".equalsIgnoreCase(textOrDefault(candidate.status(), "")))
                .orElseThrow(() -> new IllegalArgumentException("Teacher resource asset not found"));
        if (!canRead(asset, normalized)) {
            throw new IllegalArgumentException("Teacher resource asset is not visible to this subject");
        }
        Path path = safeStoragePath(asset.storageKey());
        if (!Files.isRegularFile(path)) {
            throw new IllegalArgumentException("Teacher resource asset file is unavailable");
        }
        return new VisibleAsset(
                asset.assetId(),
                asset.mimeType(),
                safeDownloadName(asset),
                new FileSystemResource(path));
    }

    /**
     * Resolves one visible asset reference without opening the binary stream.
     */
    public Optional<VisibleAssetReference> findVisibleAssetReference(String assetId, RequestSubject subject) {
        if (!enabled || assetId == null || assetId.isBlank()) {
            return Optional.empty();
        }
        RequestSubject normalized = subject.normalize();
        return assetStore.find(normalized.tenantId(), assetId.strip())
                .filter(candidate -> "active".equalsIgnoreCase(textOrDefault(candidate.status(), "")))
                .filter(candidate -> canRead(candidate, normalized))
                .map(asset -> new VisibleAssetReference(
                        asset.assetId(),
                        assetUri(asset.assetId()),
                        asset.mimeType(),
                        safeDownloadName(asset),
                        asset.sourcePath(),
                        asset.pageNo()));
    }

    public String assetUri(String assetId) {
        return "/api/teacher/resources/assets/" + textOrDefault(assetId, "");
    }

    private boolean canRead(TeacherResourceAssetResponse asset, RequestSubject subject) {
        if (!asset.tenantId().equals(subject.tenantId())) {
            return false;
        }
        String role = textOrDefault(subject.subjectType(), "").toLowerCase(Locale.ROOT);
        if ("admin".equals(role)) {
            return true;
        }
        if (!"teacher".equals(role)) {
            return false;
        }
        if (asset.ownerSubjectId().equals(subject.subjectId())) {
            return true;
        }
        String scope = textOrDefault(asset.permissionScope(), TEACHER_PRIVATE).toUpperCase(Locale.ROOT);
        if (!SHARED_SCOPES.contains(scope)) {
            return false;
        }
        TeacherResourceDocumentResponse document = resourceStore.find(asset.tenantId(), asset.documentId());
        return document != null && !"archived".equalsIgnoreCase(textOrDefault(document.syncStatus(), ""));
    }

    /**
     * Resolves a relative storage key under the configured asset root and rejects traversal.
     */
    private Path safeStoragePath(String storageKey) {
        Path root = syncProperties.assetStorageRoot();
        Path resolved = root.resolve(textOrDefault(storageKey, "")).normalize();
        if (!resolved.startsWith(root)) {
            throw new IllegalArgumentException("Teacher resource asset storage key is invalid");
        }
        return resolved;
    }

    private static String safeDownloadName(TeacherResourceAssetResponse asset) {
        String extension = extensionForMime(asset.mimeType());
        String source = textOrDefault(asset.sourcePath(), asset.assetId()).replace('\\', '/');
        int slash = source.lastIndexOf('/');
        String base = slash >= 0 ? source.substring(slash + 1) : source;
        base = base.replaceAll("[^A-Za-z0-9._-]", "_");
        if (base.isBlank()) {
            base = asset.assetId();
        }
        return base.endsWith(extension) ? base : base + extension;
    }

    private static ImageSize imageSize(byte[] content) {
        try {
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(content));
            if (image == null) {
                return new ImageSize(null, null);
            }
            return new ImageSize(image.getWidth(), image.getHeight());
        } catch (IOException exception) {
            return new ImageSize(null, null);
        }
    }

    private static String sha256(byte[] content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(content));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 algorithm is unavailable", exception);
        }
    }

    private static String extensionForMime(String mimeType) {
        String normalized = textOrDefault(mimeType, "").toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "image/jpeg", "image/jpg" -> ".jpg";
            case "image/gif" -> ".gif";
            case "image/webp" -> ".webp";
            case "image/bmp" -> ".bmp";
            case "image/png" -> ".png";
            default -> ".bin";
        };
    }

    private static String textOrDefault(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value.strip();
    }

    public record VisibleAsset(String assetId, String mimeType, String fileName, Resource resource) {
    }

    public record VisibleAssetReference(
            String assetId,
            String assetUri,
            String mimeType,
            String fileName,
            String sourcePath,
            Integer pageNo) {

        public TeacherResourceBlockSearchResponse.AssetRef toSearchAssetRef() {
            return new TeacherResourceBlockSearchResponse.AssetRef(
                    assetId,
                    assetUri,
                    mimeType,
                    fileName,
                    sourcePath,
                    pageNo);
        }
    }

    private record ImageSize(Integer width, Integer height) {
    }
}
