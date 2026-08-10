package com.doob.mathagent.teacher.service;

import com.doob.mathagent.teacher.document.TeacherResourceDocumentResponse;
import com.doob.mathagent.teacher.document.TeacherResourceStore;
import com.doob.mathagent.teacher.support.TeacherResourceRegistrationCommand;
import com.doob.mathagent.teacher.support.TeacherResourceSourceIdentity;
import com.doob.mathagent.teacher.sync.TeacherSourceSyncProperties;
import com.doob.mathagent.resources.ProjectResourceProperties;
import com.doob.mathagent.vector.service.VectorIndexService;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import java.util.Comparator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Service for teacher/admin resource registration, preview, and archive operations.
 */
@Service
public class TeacherResourceService {

    private static final int MAX_PREVIEW_DEPTH = 6;

    private final TeacherResourceStore store;
    private final VectorIndexService vectorIndexService;
    private final TeacherResourceAssetService assetService;
    private final TeacherSourceSyncProperties syncProperties;
    private final ProjectResourceProperties resourceProperties;

    /**
     * Creates a teacher resource service.
     *
     * @param store resource store
     */
    @Autowired
    public TeacherResourceService(
            TeacherResourceStore store,
            VectorIndexService vectorIndexService,
            TeacherResourceAssetService assetService,
            TeacherSourceSyncProperties syncProperties,
            ProjectResourceProperties resourceProperties) {
        this.store = store;
        this.vectorIndexService = vectorIndexService;
        this.assetService = assetService;
        this.syncProperties = syncProperties;
        this.resourceProperties = resourceProperties;
    }

    /** Compatibility constructor for narrow parser tests that intentionally do not provision asset storage. */
    public TeacherResourceService(TeacherResourceStore store, VectorIndexService vectorIndexService) {
        this.store = store;
        this.vectorIndexService = vectorIndexService;
        this.assetService = TeacherResourceAssetService.disabled();
        this.syncProperties = null;
        this.resourceProperties = null;
    }

    /**
     * Registers a teacher-managed resource source and marks it waiting for parse/index rebuild.
     *
     * @param request registration request
     * @return registered resource document
     */
    public TeacherResourceDocumentResponse register(TeacherResourceRegistrationCommand request) {
        TeacherResourceRegistrationCommand normalized = request.normalize();
        requireTeacherOrAdmin(normalized.viewerRole());
        requireSourceLocation(normalized);
        String sourceIdentity = TeacherResourceSourceIdentity.resolve(
                normalized.sourceType(), normalized.originalUrl(), normalized.localPath());
        String permissionScope = normalizePermissionScope(normalized.permissionScope(), normalized.viewerRole());
        TeacherResourceDocumentResponse existing = store.findBySourceIdentity(
                normalized.tenantId(), normalized.viewerSubjectId(), normalized.sourceType(), sourceIdentity,
                normalized.feishuExportFormat());
        if (existing == null && "feishu".equals(normalized.sourceType()) && isSharedScope(permissionScope)) {
            /*
             * Shared Feishu roots are tenant resources, not owner-private uploads.  A scheduler identity and an
             * admin click can therefore legitimately use different created_by values; consult the already visible
             * shared set so the same provider token cannot create a second production corpus.
             */
            existing = store.listVisible(normalized.tenantId(), normalized.viewerRole(), normalized.viewerSubjectId()).stream()
                    .filter(candidate -> "feishu".equals(candidate.sourceType()))
                    .filter(candidate -> sourceIdentity.equals(candidate.sourceIdentity()))
                    .filter(candidate -> isSharedScope(candidate.permissionScope()))
                    .findFirst()
                    .orElse(null);
        }
        if (existing != null) {
            if (!"archived".equalsIgnoreCase(existing.syncStatus())) {
                // Re-registration is deliberately a read operation: no duplicate source rows and no surprise resync.
                return existing;
            }
            /*
             * Archive deletes content but keeps source audit identity. Re-registering that same real source revives
             * the audit row in a clean pending state so a deliberate new sync can rebuild it without a second row.
             */
            return store.save(new TeacherResourceDocumentResponse(
                    existing.documentId(), existing.tenantId(), existing.ownerSubjectId(), existing.sourceType(),
                    normalized.title(), normalized.originalUrl(), normalized.localPath(),
                    permissionScope,
                    "registered", "pending", "pending", "waiting_rebuild", normalized.feishuExportFormat(),
                    previewFiles(normalized.localPath()), normalized.parseMode(), null, null, sourceIdentity));
        }
        TeacherResourceDocumentResponse document = new TeacherResourceDocumentResponse(
                UUID.randomUUID().toString(),
                normalized.tenantId(),
                normalized.viewerSubjectId(),
                normalized.sourceType(),
                normalized.title(),
                normalized.originalUrl(),
                normalized.localPath(),
                permissionScope,
                "registered",
                "pending",
                "pending",
                "waiting_rebuild",
                normalized.feishuExportFormat(),
                previewFiles(normalized.localPath()),
                normalized.parseMode(),
                null,
                null,
                sourceIdentity);
        return store.save(document);
    }

    /**
     * Lists active resources visible to the current teacher or admin.
     *
     * @param tenantId tenant id
     * @param viewerRole current viewer role
     * @param viewerSubjectId current viewer subject id
     * @return visible resources
     */
    public List<TeacherResourceDocumentResponse> list(String tenantId, String viewerRole, String viewerSubjectId) {
        String normalizedTenantId = requireText(tenantId, "tenantId is required");
        String normalizedRole = requireText(viewerRole, "viewerRole is required").toLowerCase();
        String normalizedSubjectId = requireText(viewerSubjectId, "viewerSubjectId is required");
        requireTeacherOrAdmin(normalizedRole);
        /*
         * Preview entries are intentionally derived from the current local source on read. The document table stores
         * the authoritative path and lifecycle state, while this keeps folder names and PDF filenames current without
         * duplicating a large file manifest in MySQL.
         */
        return store.listVisible(normalizedTenantId, normalizedRole, normalizedSubjectId).stream()
                .map(this::withLocalPreviewFiles)
                .toList();
    }

    /** Rehydrates the small UI preview while preserving every source filename exactly as found on disk. */
    private TeacherResourceDocumentResponse withLocalPreviewFiles(TeacherResourceDocumentResponse document) {
        if (document == null || document.localPath() == null || document.localPath().isBlank()) {
            return document;
        }
        return new TeacherResourceDocumentResponse(
                document.documentId(),
                document.tenantId(),
                document.ownerSubjectId(),
                document.sourceType(),
                document.title(),
                document.originalUrl(),
                document.localPath(),
                document.permissionScope(),
                document.syncStatus(),
                document.parseStatus(),
                document.embeddingStatus(),
                document.indexStatus(),
                document.feishuExportFormat(),
                previewFiles(document.localPath()),
                document.parseMode(),
                document.providerRevision(),
                document.contentChecksum(),
                document.sourceIdentity());
    }

    /**
     * Archives a resource document. Admin can archive any tenant resource; teachers can archive their own resources.
     *
     * @param tenantId tenant id
     * @param viewerRole current viewer role
     * @param viewerSubjectId current viewer subject id
     * @param documentId document id
     * @return archived resource document
     */
    public TeacherResourceDocumentResponse archive(
            String tenantId,
            String viewerRole,
            String viewerSubjectId,
            String documentId) {
        String normalizedTenantId = requireText(tenantId, "tenantId is required");
        String normalizedRole = requireText(viewerRole, "viewerRole is required").toLowerCase();
        String normalizedSubjectId = requireText(viewerSubjectId, "viewerSubjectId is required");
        requireTeacherOrAdmin(normalizedRole);
        TeacherResourceDocumentResponse document = store.find(normalizedTenantId, documentId);
        if (document == null) {
            throw new IllegalArgumentException("Teacher resource document not found: " + documentId);
        }
        if (!"admin".equals(normalizedRole) && !document.ownerSubjectId().equals(normalizedSubjectId)) {
            throw new IllegalArgumentException("Teacher can archive only own resources");
        }
        vectorIndexService.deleteTeacherResourceVectors(normalizedTenantId, documentId);
        // Archive keeps source identity for audit, but its parsed body must not remain in the local corpus.
        vectorIndexService.purgeTeacherResourceContent(normalizedTenantId, documentId);
        assetService.purgeDocumentAssets(normalizedTenantId, documentId);
        purgeFeishuStagingContent(document);
        purgeManagedUploadContent(document);
        TeacherResourceDocumentResponse archived = new TeacherResourceDocumentResponse(
                document.documentId(),
                document.tenantId(),
                document.ownerSubjectId(),
                document.sourceType(),
                document.title(),
                document.originalUrl(),
                document.localPath(),
                document.permissionScope(),
                "archived",
                document.parseStatus(),
                document.embeddingStatus(),
                "archived",
                document.feishuExportFormat(),
                document.previewFiles(),
                document.parseMode(),
                document.providerRevision(),
                document.contentChecksum(),
                document.sourceIdentity());
        return store.save(archived);
    }

    /**
     * Validates that a viewer can manage teacher resources.
     *
     * @param viewerRole current viewer role
     */
    private static void requireTeacherOrAdmin(String viewerRole) {
        if (!"teacher".equals(viewerRole) && !"admin".equals(viewerRole)) {
            throw new IllegalArgumentException("Teacher resource management requires teacher or admin role");
        }
    }

    /**
     * Validates that at least one source location was supplied.
     *
     * @param request normalized registration request
     */
    private static void requireSourceLocation(TeacherResourceRegistrationCommand request) {
        if (!request.hasLocalPath() && !request.hasOriginalUrl()) {
            throw new IllegalArgumentException("Teacher resource requires localPath or originalUrl");
        }
    }

    /**
     * Normalizes publication choices for new resources.
     *
     * <p>The permission field remains editable for both teachers and administrators. A blank value defaults to
     * tenant-shared visibility, so a teacher-uploaded resource is immediately readable by students; choosing
     * {@code TEACHER_PRIVATE} keeps it owner-only.</p>
     *
     * @param permissionScope requested permission scope
     * @param viewerRole backend resolved viewer role
     * @return safe permission scope
     */
    private static String normalizePermissionScope(String permissionScope, String viewerRole) {
        String normalizedScope = textOrDefault(permissionScope, "TENANT_PUBLIC").toUpperCase();
        if ("TEACHER_PRIVATE".equals(normalizedScope)
                || "CLASS_AUTHORIZED".equals(normalizedScope)
                || "TENANT_PUBLIC".equals(normalizedScope)
                || "PUBLIC_TEXTBOOK".equals(normalizedScope)
                || "MATH_VIP".equals(normalizedScope)) {
            return normalizedScope;
        }
        return "TENANT_PUBLIC";
    }

    /** Shared scopes may safely reuse one Feishu source row across registration identities. */
    private static boolean isSharedScope(String permissionScope) {
        return "TENANT_PUBLIC".equalsIgnoreCase(textOrDefault(permissionScope, ""))
                || "CLASS_AUTHORIZED".equalsIgnoreCase(textOrDefault(permissionScope, ""))
                || "MATH_VIP".equalsIgnoreCase(textOrDefault(permissionScope, ""));
    }

    /**
     * Builds a small local file preview for a registered path.
     *
     * @param localPath local file or folder path
     * @return preview file list
     */
    private static List<TeacherResourceDocumentResponse.PreviewFile> previewFiles(String localPath) {
        if (localPath == null || localPath.isBlank()) {
            return List.of();
        }
        Path root;
        try {
            root = Path.of(localPath);
        } catch (InvalidPathException exception) {
            return List.of();
        }
        if (!Files.exists(root)) {
            return List.of();
        }
        if (Files.isRegularFile(root)) {
            return List.of(previewFile(root.getParent(), root));
        }
        try (Stream<Path> stream = Files.walk(root, MAX_PREVIEW_DEPTH)) {
            return stream
                    .filter(Files::isRegularFile)
                    .map(path -> previewFile(root, path))
                    .sorted(Comparator.comparing(TeacherResourceDocumentResponse.PreviewFile::relativePath))
                    .toList();
        } catch (IOException exception) {
            return List.of();
        }
    }

    /**
     * Deletes only a document's Feishu downloader output. `localPath` is retained in the archived source row for
     * auditability, while its content is removed. Non-Feishu local paths are never touched by this archive action.
     */
    private void purgeFeishuStagingContent(TeacherResourceDocumentResponse document) {
        if (syncProperties == null || !"feishu".equalsIgnoreCase(document.sourceType())
                || document.localPath() == null || document.localPath().isBlank()) {
            return;
        }
        Path root = syncProperties.feishuStagingRoot().toAbsolutePath().normalize();
        Path candidate;
        try {
            candidate = Path.of(document.localPath()).toAbsolutePath().normalize();
        } catch (InvalidPathException exception) {
            throw new IllegalArgumentException("Archived Feishu staging path is invalid", exception);
        }
        if (!candidate.startsWith(root) || candidate.equals(root) || candidate.equals(syncProperties.assetStorageRoot())) {
            throw new IllegalArgumentException("Archived Feishu staging path is outside the configured staging root");
        }
        try {
            if (Files.isDirectory(candidate)) {
                try (Stream<Path> paths = Files.walk(candidate)) {
                    paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (IOException exception) {
                            throw new IllegalStateException("Failed to remove archived Feishu staging content", exception);
                        }
                    });
                }
            } else {
                Files.deleteIfExists(candidate);
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to scan archived Feishu staging content", exception);
        }
    }

    /**
     * Removes only files created by the multipart upload service. Arbitrary developer-supplied localPath values remain
     * untouched by design, while managed upload staging cannot grow forever after a resource is archived.
     */
    private void purgeManagedUploadContent(TeacherResourceDocumentResponse document) {
        if (resourceProperties == null || document.localPath() == null || document.localPath().isBlank()) {
            return;
        }
        Path root = resourceProperties.localFileStorageRoot()
                .resolve("teacher-resource-uploads").toAbsolutePath().normalize();
        Path candidate;
        try {
            candidate = Path.of(document.localPath()).toAbsolutePath().normalize();
        } catch (InvalidPathException exception) {
            throw new IllegalArgumentException("Managed upload path is invalid", exception);
        }
        if (!candidate.startsWith(root) || candidate.equals(root)) {
            return;
        }
        deleteTree(candidate, "managed teacher upload");
    }

    /** Deletes one backend-owned tree and preserves the original archive failure when cleanup cannot complete. */
    private static void deleteTree(Path candidate, String description) {
        try {
            if (Files.isDirectory(candidate)) {
                try (Stream<Path> paths = Files.walk(candidate)) {
                    paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (IOException exception) {
                            throw new IllegalStateException("Failed to remove " + description, exception);
                        }
                    });
                }
            } else {
                Files.deleteIfExists(candidate);
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to remove " + description, exception);
        }
    }

    /**
     * Converts a file path to a preview entry.
     *
     * @param root registered root path
     * @param file file path
     * @return preview entry
     */
    private static TeacherResourceDocumentResponse.PreviewFile previewFile(Path root, Path file) {
        long size;
        try {
            size = Files.size(file);
        } catch (IOException exception) {
            size = 0L;
        }
        Path safeRoot = root == null ? file.getParent() : root;
        String relativePath = safeRoot == null ? file.toString() : safeRoot.relativize(file).toString();
        return new TeacherResourceDocumentResponse.PreviewFile(
                file.getFileName().toString(),
                relativePath.replace('\\', '/'),
                size);
    }

    /**
     * Returns stripped text or a fallback when blank.
     *
     * @param value input value
     * @param defaultValue fallback value
     * @return normalized text
     */
    private static String textOrDefault(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value.strip();
    }

    /**
     * Returns stripped text or fails when a backend-owned identity field is missing.
     *
     * @param value input value
     * @param message exception message
     * @return stripped text
     */
    private static String requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value.strip();
    }
}
