package com.doob.mathagent.teacher.service;

import com.doob.mathagent.infrastructure.security.RequestSubject;
import com.doob.mathagent.resources.ProjectResourceProperties;
import com.doob.mathagent.teacher.asset.TeacherResourceAssetStore;
import com.doob.mathagent.teacher.document.TeacherResourceStore;
import com.doob.mathagent.teacher.vo.TeacherResourceAssetResponse;
import com.doob.mathagent.teacher.search.TeacherResourceBlockSearchResponse;
import com.doob.mathagent.teacher.document.TeacherResourceDocumentResponse;
import com.doob.mathagent.teacher.document.TeacherResourceVisibilityPolicy;
import com.doob.mathagent.teacher.sync.TeacherSourceSyncProperties;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Comparator;
import java.util.Iterator;
import java.util.UUID;
import java.util.List;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

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
    private final Path managedUploadRoot;
    private final boolean enabled;

    @Autowired
    public TeacherResourceAssetService(
            TeacherResourceAssetStore assetStore,
            TeacherResourceStore resourceStore,
            TeacherSourceSyncProperties syncProperties,
            ProjectResourceProperties resourceProperties) {
        this.assetStore = Objects.requireNonNull(assetStore, "assetStore");
        this.resourceStore = Objects.requireNonNull(resourceStore, "resourceStore");
        this.syncProperties = Objects.requireNonNull(syncProperties, "syncProperties");
        ProjectResourceProperties configuredResources = Objects.requireNonNull(resourceProperties, "resourceProperties");
        this.managedUploadRoot = configuredResources.teacherResourceUploadRoot();
        this.enabled = true;
    }

    /**
     * Compatibility constructor for focused tests that use an explicit temporary source path. Production wiring uses
     * the four-argument constructor so local asset recovery is restricted to the managed upload volume.
     */
    public TeacherResourceAssetService(
            TeacherResourceAssetStore assetStore,
            TeacherResourceStore resourceStore,
            TeacherSourceSyncProperties syncProperties) {
        this.assetStore = Objects.requireNonNull(assetStore, "assetStore");
        this.resourceStore = Objects.requireNonNull(resourceStore, "resourceStore");
        this.syncProperties = Objects.requireNonNull(syncProperties, "syncProperties");
        this.managedUploadRoot = null;
        this.enabled = true;
    }

    private TeacherResourceAssetService() {
        this.assetStore = null;
        this.resourceStore = null;
        this.syncProperties = null;
        this.managedUploadRoot = null;
        this.enabled = false;
    }

    /**
     * Disabled asset service used by focused unit tests that do not start the database-backed asset store.
     */
    public static TeacherResourceAssetService disabled() {
        return new TeacherResourceAssetService();
    }

    /**
     * Returns active image assets for one document.  CLIP indexing uses this permission-neutral metadata view only
     * after the caller has already checked document ownership; binary reads still go through openVisibleAsset().
     */
    public List<TeacherResourceAssetResponse> listActiveImageAssets(String tenantId, String documentId) {
        if (!enabled) {
            return List.of();
        }
        return assetStore.listByDocument(tenantId, documentId).stream()
                .filter(asset -> "active".equalsIgnoreCase(textOrDefault(asset.status(), "")))
                .filter(asset -> textOrDefault(asset.mimeType(), "").toLowerCase(Locale.ROOT).startsWith("image/"))
                .sorted(Comparator.comparing(asset -> asset.pageNo() == null ? Integer.MAX_VALUE : asset.pageNo()))
                .toList();
    }

    public void markDocumentAssetsInactive(String tenantId, String documentId) {
        if (enabled) {
            assetStore.markDocumentAssetsInactive(tenantId, documentId);
        }
    }

    /** Marks one active asset inactive when a later backend-owned materialization check rejects it. */
    public boolean deactivateAsset(String tenantId, String assetId, String reason) {
        if (!enabled || assetId == null || assetId.isBlank()) {
            return false;
        }
        Optional<TeacherResourceAssetResponse> candidate = assetStore.find(tenantId, assetId);
        if (candidate.isEmpty() || !"active".equalsIgnoreCase(textOrDefault(candidate.get().status(), ""))) {
            return false;
        }
        TeacherResourceAssetResponse asset = candidate.get();
        assetStore.save(new TeacherResourceAssetResponse(
                asset.assetId(), asset.tenantId(), asset.ownerSubjectId(), asset.documentId(), asset.blockId(),
                asset.permissionScope(), asset.sourcePath(), asset.pageNo(), asset.providerAssetId(),
                asset.checksum(), asset.mimeType(), asset.width(), asset.height(), asset.storageKey(), "inactive"));
        LOGGER.warn("teacher_image_asset_deactivated documentId={} assetId={} reason={}",
                asset.documentId(), asset.assetId(), textOrDefault(reason, "unspecified"));
        return true;
    }


    public int deactivateInvalidImageAssets(String tenantId, String documentId) {
        if (!enabled) {
            return 0;
        }
        int invalidated = 0;
        for (TeacherResourceAssetResponse asset : assetStore.listByDocument(tenantId, documentId)) {
            if (!"active".equalsIgnoreCase(textOrDefault(asset.status(), ""))
                    || !textOrDefault(asset.mimeType(), "").toLowerCase(Locale.ROOT).startsWith("image/")) {
                continue;
            }
            try {
                Path path = safeStoragePath(asset.storageKey());
                if (!Files.isRegularFile(path)) {
                    throw new IllegalArgumentException("asset binary is missing");
                }
                validateImageBytes(
                        documentId,
                        asset.sourcePath(),
                        asset.providerAssetId(),
                        Files.readAllBytes(path),
                        asset.mimeType());
            } catch (IOException | RuntimeException exception) {
                LOGGER.warn(
                        "teacher_image_asset_invalidated documentId={} assetId={} reason={}",
                        documentId,
                        asset.assetId(),
                        safeReason(exception));
                TeacherResourceAssetResponse inactive = new TeacherResourceAssetResponse(
                        asset.assetId(), asset.tenantId(), asset.ownerSubjectId(), asset.documentId(), asset.blockId(),
                        asset.permissionScope(), asset.sourcePath(), asset.pageNo(), asset.providerAssetId(),
                        asset.checksum(), asset.mimeType(), asset.width(), asset.height(), asset.storageKey(), "inactive");
                assetStore.save(inactive);
                invalidated++;
            }
        }
        return invalidated;
    }

    /**
     * Removes all binary generations for an archived source and then its asset metadata. Storage keys are resolved
     * through safeStoragePath, so a corrupt database value cannot escape the configured backend-owned asset root.
     */
    public void purgeDocumentAssets(String tenantId, String documentId) {
        if (!enabled) {
            return;
        }
        for (TeacherResourceAssetResponse asset : assetStore.listByDocument(tenantId, documentId)) {
            try {
                Files.deleteIfExists(safeStoragePath(asset.storageKey()));
            } catch (IOException exception) {
                throw new IllegalStateException("Failed to remove archived teacher resource asset", exception);
            }
        }
        assetStore.purgeDocumentAssets(tenantId, documentId);
    }

    /**
     * Re-materializes every persisted asset generation for one Feishu document with readable, deterministic names.
     * The opaque asset id is preserved; only the backend storage key changes after the new file is durable.
     */
    public AssetMigrationSummary migrateDocumentAssetsToReadableNames(String tenantId, String documentId) {
        if (!enabled) {
            return new AssetMigrationSummary(0, 0, 0);
        }
        long started = System.nanoTime();
        List<TeacherResourceAssetResponse> assets = assetStore.listByDocument(tenantId, documentId).stream()
                .sorted(Comparator.comparing(TeacherResourceAssetResponse::sourcePath, Comparator.nullsFirst(String::compareTo))
                        .thenComparing(TeacherResourceAssetResponse::pageNo, Comparator.nullsFirst(Integer::compareTo))
                        .thenComparing(TeacherResourceAssetResponse::assetId, Comparator.nullsFirst(String::compareTo)))
                .toList();
        if (assets.isEmpty()) {
            return new AssetMigrationSummary(0, 0, elapsedMillis(started));
        }
        TeacherResourceDocumentResponse document = resourceStore.find(tenantId, documentId);
        if (document == null) {
            return new AssetMigrationSummary(0, 0, elapsedMillis(started));
        }
        Path documentRoot = documentAssetRoot(document);
        Map<String, Integer> imageOrders = new java.util.LinkedHashMap<>();
        Map<String, Integer> pageOrders = new java.util.LinkedHashMap<>();
        int migrated = 0;
        int removedLegacyFiles = 0;
        for (TeacherResourceAssetResponse asset : assets) {
            Path oldPath = safeStoragePath(asset.storageKey());
            if (!Files.isRegularFile(oldPath)) {
                continue;
            }
            String sourcePath = textOrDefault(asset.sourcePath(), "document.md").replace('\\', '/');
            Path documentDirectory = documentAssetDirectory(documentRoot, sourcePath);
            String extension = extensionForMime(asset.mimeType());
            boolean pageAsset = asset.pageNo() != null && asset.pageNo() > 0
                    && sourcePath.toLowerCase(Locale.ROOT).endsWith(".pdf");
            String orderKey = sourcePath;
            int sequence = pageAsset
                    ? pageOrders.merge(orderKey, 1, Integer::sum)
                    : imageOrders.merge(orderKey, 1, Integer::sum);
            String fileName = String.format(Locale.ROOT, pageAsset ? "page-%03d%s" : "image-%03d%s", sequence, extension);
            Path target = documentDirectory.resolve(fileName).normalize();
            if (!target.startsWith(documentRoot)) {
                throw new IllegalArgumentException("Teacher resource asset target is invalid");
            }
            if (!oldPath.equals(target)) {
                try {
                    Files.createDirectories(target.getParent());
                    Files.copy(oldPath, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    TeacherResourceAssetResponse relocated = new TeacherResourceAssetResponse(
                            asset.assetId(), asset.tenantId(), asset.ownerSubjectId(), asset.documentId(), asset.blockId(),
                            asset.permissionScope(), asset.sourcePath(), asset.pageNo(), asset.providerAssetId(),
                            asset.checksum(), asset.mimeType(), asset.width(), asset.height(),
                            syncProperties.assetStorageRoot().relativize(target).toString().replace('\\', '/'), asset.status());
                    assetStore.save(relocated);
                    Files.deleteIfExists(oldPath);
                    migrated++;
                    if (isLegacyHashFile(oldPath)) {
                        removedLegacyFiles++;
                    }
                } catch (IOException exception) {
                    throw new IllegalStateException("Failed to migrate teacher resource asset " + asset.assetId(), exception);
                }
            }
        }
        return new AssetMigrationSummary(migrated, removedLegacyFiles, elapsedMillis(started));
    }

    private Path documentAssetRoot(TeacherResourceDocumentResponse document) {
        Path root = syncProperties.assetStorageRoot().resolve(safeFileName(document.title(), "document")).normalize();
        if (!root.startsWith(syncProperties.assetStorageRoot())) {
            throw new IllegalArgumentException("Teacher resource asset document root is invalid");
        }
        return root;
    }

    private Path documentAssetDirectory(Path documentRoot, String sourcePath) {
        String normalized = sourcePath.replace('\\', '/');
        Path relative = Path.of(normalized);
        Path parent = relative.getParent();
        Path result = documentRoot;
        if (parent != null) {
            for (Path part : parent) {
                result = result.resolve(safeFileName(part.toString(), "folder"));
            }
        }
        return result.normalize();
    }

    private static long elapsedMillis(long started) {
        return java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
    }

    private static boolean isLegacyHashFile(Path path) {
        if (path.getFileName() == null) {
            return false;
        }
        String name = path.getFileName().toString();
        return name.matches("(?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.[a-z0-9]+")
                || name.matches("(?i)[0-9a-f]{32,}\\.[a-z0-9]+");
    }

    public record AssetMigrationSummary(int migratedCount, int removedLegacyFileCount, long elapsedMs) {
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
        return saveExtractedAsset(document, sourcePath, pageNo, providerAssetId, content, mimeType, true);
    }

    /**
     * Saves one extracted asset. Image callers must pass {@code expectedImage=true}; ordinary Feishu attachments keep
     * their binary bytes and are not forced through an image decoder.
     */
    public Optional<TeacherResourceAssetResponse> saveExtractedAsset(
            TeacherResourceDocumentResponse document,
            String sourcePath,
            Integer pageNo,
            String providerAssetId,
            byte[] content,
            String mimeType,
            boolean expectedImage) {
        if (!enabled || document == null) {
            LOGGER.warn(
                    "Skip teacher resource asset: enabled={}, documentId={}, sourcePath={}, providerAssetId={}",
                    enabled,
                    document == null ? "" : document.documentId(),
                    sourcePath,
                    providerAssetId);
            return Optional.empty();
        }
        if (content == null || content.length == 0) {
            if (expectedImage) {
                throw invalidImage(document.documentId(), sourcePath, providerAssetId, "image payload is empty");
            }
            LOGGER.warn(
                    "Skip empty teacher resource attachment: documentId={}, sourcePath={}, providerAssetId={}",
                    document.documentId(), sourcePath, providerAssetId);
            return Optional.empty();
        }
        ValidatedAsset validated = expectedImage
                ? validateImageContent(document, sourcePath, providerAssetId, content, mimeType)
                : ValidatedAsset.binary(content, normalizedMime(mimeType));
        String checksum = sha256(content);
        String normalizedProviderId = textOrDefault(providerAssetId, sourcePath + ":" + checksum);
        Optional<TeacherResourceAssetResponse> existing = assetStore.findByProviderChecksum(
                document.tenantId(),
                document.documentId(),
                normalizedProviderId,
                checksum);
        if (existing.isPresent()) {
            TeacherResourceAssetResponse previous = existing.get();
            if (!storageFileExists(previous)) {
                persistAssetBytes(previous, validated);
            }
            if (!"active".equalsIgnoreCase(textOrDefault(previous.status(), ""))) {
                previous = new TeacherResourceAssetResponse(
                        previous.assetId(), previous.tenantId(), previous.ownerSubjectId(), previous.documentId(),
                        previous.blockId(), previous.permissionScope(), previous.sourcePath(), previous.pageNo(),
                        previous.providerAssetId(), previous.checksum(), validated.mimeType(), validated.width(),
                        validated.height(), previous.storageKey(), "active");
                return Optional.of(assetStore.save(previous));
            }
            return Optional.of(previous);
        }
        String assetId = UUID.randomUUID().toString();
        String extension = extensionForMime(validated.mimeType());
        String normalizedSourcePath = textOrDefault(sourcePath, "document.md").replace('\\', '/');
        Path documentRoot = documentAssetRoot(document);
        Path documentDirectory = documentAssetDirectory(documentRoot, normalizedSourcePath);
        String storageFileName;
        if (pageNo != null && pageNo > 0 && normalizedSourcePath.toLowerCase(Locale.ROOT).endsWith(".pdf")) {
            storageFileName = String.format(Locale.ROOT, "page-%03d%s", pageNo, extension);
        } else {
            String logicalFileName = Path.of(normalizedSourcePath).getFileName() == null
                    ? "image" + extension
                    : Path.of(normalizedSourcePath).getFileName().toString();
            storageFileName = safeFileName(logicalFileName, "image" + extension);
        }
        Path target = documentDirectory.resolve(storageFileName).normalize();
        String storageKey = syncProperties.assetStorageRoot().relativize(target).toString().replace('\\', '/');
        try {
            Files.createDirectories(target.getParent());
            Files.write(target, content);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to persist teacher resource asset", exception);
        }
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
                validated.mimeType(),
                validated.width(),
                validated.height(),
                storageKey,
                "active");
        return Optional.of(assetStore.save(asset));
    }

    /**
     * Opens one active image bound to the exact authoritative document and Markdown logical path.
     *
     * <p>Source-image aliases are issued from a persisted document block, so a globally repeated relative path is not
     * enough authority to select a binary. This remains a direct, run-authorized asset lookup and does not expose FILE
     * documents through generic resource discovery.</p>
     */
    public Optional<VisibleAsset> openVisibleLogicalAsset(
            String documentId, String logicalPath, RequestSubject subject) {
        if (!enabled || documentId == null || documentId.isBlank()
                || logicalPath == null || logicalPath.isBlank() || subject == null) {
            return Optional.empty();
        }
        String normalizedPath = normalizeLogicalImagePath(logicalPath);
        RequestSubject normalized = subject.normalize();
        List<TeacherResourceAssetResponse> documentAssets = assetStore
                .listByDocument(normalized.tenantId(), documentId.strip());
        List<TeacherResourceAssetResponse> pathMatches = documentAssets.stream()
                .filter(asset -> normalizedPath.equals(normalizePersistedLogicalImagePath(asset.sourcePath())))
                .toList();
        List<TeacherResourceAssetResponse> activeImageMatches = pathMatches.stream()
                .filter(asset -> "active".equalsIgnoreCase(textOrDefault(asset.status(), "")))
                .filter(asset -> textOrDefault(asset.mimeType(), "").toLowerCase(Locale.ROOT).startsWith("image/"))
                .toList();
        List<TeacherResourceAssetResponse> matches = activeImageMatches.stream()
                .filter(asset -> canRead(asset, normalized))
                .toList();
        if (matches.isEmpty()) {
            LOGGER.warn("handout_source_image_authorization_rejected documentId={} pathFingerprint={} documentAssets={} pathMatches={} activeImageMatches={} visibleMatches={}",
                    documentId.strip(), logicalPathFingerprint(normalizedPath), documentAssets.size(), pathMatches.size(),
                    activeImageMatches.size(), matches.size());
        }
        if (matches.size() > 1) {
            throw new IllegalStateException("Teacher resource document image path is ambiguous");
        }
        if (matches.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(openVisibleAsset(matches.get(0).assetId(), normalized));
    }

    private static String normalizeLogicalImagePath(String logicalPath) {
        String normalizedPath = logicalPath.strip().replace('\\', '/');
        if (normalizedPath.startsWith("/") || normalizedPath.contains("..")
                || normalizedPath.contains("http://") || normalizedPath.contains("https://")) {
            throw new IllegalArgumentException("Teacher resource logical image path is invalid");
        }
        return normalizedPath;
    }

    private static String normalizePersistedLogicalImagePath(String logicalPath) {
        return textOrDefault(logicalPath, "").strip().replace('\\', '/');
    }

    private static String logicalPathFingerprint(String logicalPath) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(logicalPath.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 8);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    /**
     * Opens an asset after applying the same tenant/owner/scope rules used by teacher resource search.
     */
    public Optional<VisibleAsset> openVisibleQualifiedAsset(String qualifiedReference, RequestSubject subject) {
        if (!enabled || qualifiedReference == null || qualifiedReference.isBlank()) return Optional.empty();
        RequestSubject normalized = subject.normalize();
        for (TeacherResourceDocumentResponse document : resourceStore.listVisible(
                normalized.tenantId(), normalized.subjectType(), normalized.subjectId())) {
            for (TeacherResourceAssetResponse asset : assetStore.listByDocument(normalized.tenantId(), document.documentId())) {
                if (!"active".equalsIgnoreCase(textOrDefault(asset.status(), ""))
                        || !qualifiedReference.equals(asset.providerAssetId())) continue;
                try {
                    return Optional.of(openVisibleAsset(asset.assetId(), normalized));
                } catch (IllegalArgumentException ignored) {
                    return Optional.empty();
                }
            }
        }
        return Optional.empty();
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
            TeacherResourceDocumentResponse document = resourceStore.find(asset.tenantId(), asset.documentId());
            if (document != null) restoreMissingAsset(asset, document);
            if (!Files.isRegularFile(path)) {
                throw new IllegalArgumentException("Teacher resource asset file is unavailable");
            }
        }
        return new VisibleAsset(asset.assetId(), asset.mimeType(), safeDownloadName(asset), new FileSystemResource(path));
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

    /**
     * Resolves one active image from the exact page that produced an imported atomic question.
     *
     * <p>Question-bank children retain a {@code parentBlockId#qN} identity, while DOCX page assets belong to the
     * parent page. Resolving through document/page keeps that provenance intact and repeats the same owner/scope
     * check as ordinary asset delivery; a question id is never treated as an asset id.</p>
     */
    public Optional<VisibleAssetReference> findVisiblePageImageReference(
            String documentId,
            Integer pageNo,
            RequestSubject subject) {
        if (!enabled || documentId == null || documentId.isBlank() || pageNo == null || pageNo <= 0 || subject == null) {
            return Optional.empty();
        }
        RequestSubject normalized = subject.normalize();
        return assetStore.listByDocument(normalized.tenantId(), documentId.strip()).stream()
                .filter(asset -> "active".equalsIgnoreCase(textOrDefault(asset.status(), "")))
                .filter(asset -> pageNo.equals(asset.pageNo()))
                .filter(asset -> textOrDefault(asset.mimeType(), "").toLowerCase(Locale.ROOT).startsWith("image/"))
                .map(asset -> findVisibleAssetReference(asset.assetId(), normalized))
                .flatMap(Optional::stream)
                .findFirst();
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
        String scope = textOrDefault(asset.permissionScope(), TEACHER_PRIVATE).toUpperCase(Locale.ROOT);
        // A student can open only the same tenant-public image asset that retrieval was allowed to cite.  Applying
        // this policy at binary delivery closes the common bypass where block search filters a private asset but a
        // guessed asset id could still be fetched directly.
        if ("student".equals(role)) {
            return TeacherResourceVisibilityPolicy.STUDENT_SHARED_SCOPES.contains(scope);
        }
        if (!"teacher".equals(role)) {
            return false;
        }
        if (asset.ownerSubjectId().equals(subject.subjectId())) {
            return true;
        }
        if (!TeacherResourceVisibilityPolicy.TEACHER_SHARED_SCOPES.contains(scope)) {
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

    /** Checks the backend-owned binary path without following an untrusted path outside the asset root. */
    private boolean storageFileExists(TeacherResourceAssetResponse asset) {
        return Files.isRegularFile(safeStoragePath(asset.storageKey()));
    }

    /** Writes newly extracted bytes to an existing storage key, preserving the stable asset id and database row. */
    private void persistAssetBytes(TeacherResourceAssetResponse asset, ValidatedAsset content) {
        Path target = safeStoragePath(asset.storageKey());
        try {
            Files.createDirectories(target.getParent());
            Files.write(target, content.bytes());
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to restore teacher resource asset binary", exception);
        }
    }

    /**
     * Rebuilds a missing asset from the registered source document. PDF page assets are rendered at the same fixed DPI
     * used by source synchronization; ordinary image attachments are copied directly from the source package.
     */
    private void restoreMissingAsset(
            TeacherResourceAssetResponse asset,
            TeacherResourceDocumentResponse document) {
        Path source = resolveSourceFile(document, asset.sourcePath());
        if (source == null || !Files.isRegularFile(source)) {
            LOGGER.warn("Cannot restore teacher resource asset {}: source file unavailable", asset.assetId());
            return;
        }
        try {
            byte[] content = sourcePageBytes(source, asset.pageNo(), asset.mimeType());
            if (content.length > 0) {
                ValidatedAsset validated = textOrDefault(asset.mimeType(), "")
                        .toLowerCase(Locale.ROOT)
                        .startsWith("image/")
                                ? validateImageBytes(
                                        asset.documentId(), asset.sourcePath(), asset.providerAssetId(), content, asset.mimeType())
                                : ValidatedAsset.binary(content, normalizedMime(asset.mimeType()));
                persistAssetBytes(asset, validated);
                LOGGER.info("Restored missing teacher resource asset {} from {} page {}",
                        asset.assetId(), source, asset.pageNo());
            }
        } catch (IOException | RuntimeException exception) {
            LOGGER.warn("Failed to restore missing teacher resource asset {} from {}", asset.assetId(), source, exception);
        }
    }

    /** Resolves a source asset only inside the Feishu staging volume or managed local upload volume. */
    private Path resolveSourceFile(
            TeacherResourceDocumentResponse document,
            String sourcePath) {
        String configured = textOrDefault(document.localPath(), "");
        if (configured.isBlank()) {
            return null;
        }
        String relative = textOrDefault(sourcePath, "").replace('\\', '/');
        if (relative.isBlank() || isTextSourcePath(relative)) {
            return null;
        }
        Path root;
        try {
            Path configuredPath = Path.of(configured);
            if ("feishu".equalsIgnoreCase(textOrDefault(document.sourceType(), ""))) {
                root = syncProperties.requireStagingPath(configuredPath);
            } else {
                root = configuredPath.toAbsolutePath().normalize().toRealPath();
                if (managedUploadRoot != null) {
                    Path managedRoot = managedUploadRoot.toRealPath();
                    if (!root.startsWith(managedRoot)) {
                        return null;
                    }
                }
            }
        } catch (IOException | RuntimeException exception) {
            return null;
        }
        try {
            if (Files.isRegularFile(root)) {
                return root.getFileName().toString().equals(relative) ? root : null;
            }
            Path candidate = root.resolve(relative).normalize();
            Path realRoot = root.toRealPath();
            if (!candidate.startsWith(realRoot)) {
                return null;
            }
            Path realCandidate = candidate.toRealPath();
            return realCandidate.startsWith(realRoot) && Files.isRegularFile(realCandidate) ? realCandidate : null;
        } catch (IOException | RuntimeException exception) {
            return null;
        }
    }

    private static boolean isTextSourcePath(String sourcePath) {
        String normalized = sourcePath.toLowerCase(Locale.ROOT);
        return normalized.endsWith(".md") || normalized.endsWith(".markdown") || normalized.endsWith(".txt");
    }

    /** Reads a source image or renders one PDF page to PNG for the missing asset row. */
    private static byte[] sourcePageBytes(Path source, Integer pageNo, String mimeType) throws IOException {
        if (pageNo == null || pageNo <= 0 || !source.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".pdf")) {
            return Files.readAllBytes(source);
        }
        try (PDDocument pdf = Loader.loadPDF(source.toFile())) {
            if (pageNo > pdf.getNumberOfPages()) {
                return new byte[0];
            }
            BufferedImage image = new PDFRenderer(pdf).renderImageWithDPI(pageNo - 1, 144, ImageType.RGB);
            java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
            String format = extensionForMime(mimeType).equals(".jpg") ? "jpg" : "png";
            ImageIO.write(image, format, output);
            return output.toByteArray();
        }
    }

    private String nextSequentialImageName(Path directory, String extension) {
        int index = 1;
        Path candidate;
        do {
            candidate = directory.resolve(String.format(Locale.ROOT, "image-%03d%s", index++, extension));
        } while (Files.exists(candidate));
        return candidate.getFileName().toString();
    }

    private Path nextAvailableStoragePath(Path directory, String fileName) {
        Path candidate = directory.resolve(fileName).normalize();
        if (!candidate.startsWith(syncProperties.assetStorageRoot()) || !Files.exists(candidate)) {
            return candidate;
        }
        String stem = fileName;
        String suffix = "";
        int dot = fileName.lastIndexOf('.');
        if (dot > 0) {
            stem = fileName.substring(0, dot);
            suffix = fileName.substring(dot);
        }
        int index = 2;
        do {
            candidate = directory.resolve(stem + "-" + index++ + suffix).normalize();
        } while (Files.exists(candidate));
        return candidate;
    }

    private static String safeFileName(String value, String fallback) {
        String normalized = textOrDefault(value, fallback).replaceAll("[<>:\"/\\\\|?*]", "_").strip();
        return normalized.isBlank() ? fallback : normalized;
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

    /**
     * Decodes one candidate image before it is permitted to become an active asset. The signature check prevents an
     * HTML/JSON error body renamed to .png from reaching ImageIO or being trusted through a provider MIME header.
     */
    static ValidatedAsset validateImageBytes(
            String documentId,
            String sourcePath,
            String providerAssetId,
            byte[] content,
            String declaredMimeType) {
        if (content == null || content.length == 0) {
            throw invalidImage(documentId, sourcePath, providerAssetId, "image payload is empty");
        }
        String detectedMimeType = imageMimeFromSignature(content);
        if (detectedMimeType.isBlank()) {
            throw invalidImage(documentId, sourcePath, providerAssetId, "image payload has no supported signature");
        }
        String declared = normalizedMime(declaredMimeType);
        if (!declared.isBlank() && !"application/octet-stream".equals(declared)
                && !mimeTypesMatch(declared, detectedMimeType)) {
            throw invalidImage(documentId, sourcePath, providerAssetId,
                    "declared MIME does not match image signature");
        }
        try (ImageInputStream input = ImageIO.createImageInputStream(new ByteArrayInputStream(content))) {
            if (input == null) {
                throw invalidImage(documentId, sourcePath, providerAssetId, "image decoder input is unavailable");
            }
            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) {
                throw invalidImage(documentId, sourcePath, providerAssetId, "no installed decoder accepts the image");
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(input, false, true);
                BufferedImage image = reader.read(0);
                if (image == null || image.getWidth() <= 0 || image.getHeight() <= 0) {
                    throw invalidImage(documentId, sourcePath, providerAssetId, "image decoder returned invalid dimensions");
                }
                return new ValidatedAsset(content, detectedMimeType, image.getWidth(), image.getHeight());
            } finally {
                reader.dispose();
            }
        } catch (IOException exception) {
            throw invalidImage(documentId, sourcePath, providerAssetId, "image decoder rejected the payload");
        }
    }

    private static ValidatedAsset validateImageContent(
            TeacherResourceDocumentResponse document,
            String sourcePath,
            String providerAssetId,
            byte[] content,
            String mimeType) {
        return validateImageBytes(
                document.documentId(), sourcePath, providerAssetId, content, mimeType);
    }

    private static IllegalArgumentException invalidImage(
            String documentId,
            String sourcePath,
            String providerAssetId,
            String reason) {
        return new IllegalArgumentException(
                "Rejected invalid teacher image: documentId=" + textOrDefault(documentId, "unknown")
                        + ", sourcePath=" + textOrDefault(sourcePath, "unknown")
                        + ", providerAssetId=" + textOrDefault(providerAssetId, "unknown")
                        + ", reason=" + reason);
    }

    private static String imageMimeFromSignature(byte[] content) {
        if (startsWith(content, new byte[] {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n'})) {
            return "image/png";
        }
        if (startsWith(content, new byte[] {(byte) 0xff, (byte) 0xd8, (byte) 0xff})) {
            return "image/jpeg";
        }
        if (startsWith(content, "GIF87a".getBytes(java.nio.charset.StandardCharsets.US_ASCII))
                || startsWith(content, "GIF89a".getBytes(java.nio.charset.StandardCharsets.US_ASCII))) {
            return "image/gif";
        }
        if (startsWith(content, new byte[] {'B', 'M'})) {
            return "image/bmp";
        }
        if (startsWith(content, new byte[] {'I', 'I', '*', 0})
                || startsWith(content, new byte[] {'M', 'M', 0, '*'})) {
            return "image/tiff";
        }
        if (content.length >= 12
                && startsWith(content, new byte[] {'R', 'I', 'F', 'F'})
                && content[8] == 'W' && content[9] == 'E' && content[10] == 'B' && content[11] == 'P') {
            return "image/webp";
        }
        return "";
    }

    private static boolean startsWith(byte[] content, byte[] signature) {
        if (content.length < signature.length) {
            return false;
        }
        for (int index = 0; index < signature.length; index += 1) {
            if (content[index] != signature[index]) {
                return false;
            }
        }
        return true;
    }

    private static boolean mimeTypesMatch(String declared, String detected) {
        return declared.equals(detected)
                || ("image/jpg".equals(declared) && "image/jpeg".equals(detected));
    }

    private static String normalizedMime(String value) {
        String normalized = textOrDefault(value, "").toLowerCase(Locale.ROOT);
        int separator = normalized.indexOf(';');
        return separator < 0 ? normalized : normalized.substring(0, separator).strip();
    }

    private static String safeReason(Exception exception) {
        String message = textOrDefault(exception.getMessage(), exception.getClass().getSimpleName());
        return message.length() <= 500 ? message : message.substring(0, 500);
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

    record ValidatedAsset(byte[] bytes, String mimeType, Integer width, Integer height) {
        private static ValidatedAsset binary(byte[] bytes, String mimeType) {
            return new ValidatedAsset(bytes, textOrDefault(mimeType, "application/octet-stream"), null, null);
        }
    }
}
