package com.doob.mathagent.teacher.service;

import com.doob.mathagent.teacher.document.TeacherResourceDocumentResponse;
import com.doob.mathagent.teacher.document.TeacherResourceStore;
import com.doob.mathagent.teacher.document.TeacherFileDocumentResponse;
import com.doob.mathagent.teacher.support.TeacherResourceRegistrationCommand;
import com.doob.mathagent.teacher.support.TeacherResourceSourceIdentity;
import com.doob.mathagent.teacher.sync.TeacherSourceSyncJobStore;
import com.doob.mathagent.teacher.sync.TeacherSourceSyncProperties;
import com.doob.mathagent.resources.ProjectResourceProperties;
import com.doob.mathagent.vector.service.VectorIndexService;
import java.time.Instant;
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
    private final TeacherSourceSyncJobStore syncJobStore;
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
            ProjectResourceProperties resourceProperties,
            TeacherSourceSyncJobStore syncJobStore) {
        this.store = store;
        this.vectorIndexService = vectorIndexService;
        this.assetService = assetService;
        this.syncProperties = syncProperties;
        this.resourceProperties = resourceProperties;
        this.syncJobStore = syncJobStore;
    }

    /** Compatibility constructor for narrow parser tests that intentionally do not provision asset storage. */
    public TeacherResourceService(TeacherResourceStore store, VectorIndexService vectorIndexService) {
        this.store = store;
        this.vectorIndexService = vectorIndexService;
        this.assetService = TeacherResourceAssetService.disabled();
        this.syncProperties = null;
        this.resourceProperties = null;
        this.syncJobStore = null;
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
                    previewFiles(normalized.sourceType(), normalized.localPath(), normalized.feishuExportFormat()), normalized.parseMode(), null, null, sourceIdentity));
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
                previewFiles(normalized.sourceType(), normalized.localPath(), normalized.feishuExportFormat()),
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
                previewFiles(document.sourceType(), document.localPath(), document.feishuExportFormat()),
                document.parseMode(),
                document.providerRevision(),
                document.contentChecksum(),
                document.sourceIdentity());
    }

    /**
     * Lists the bounded, searchable physical FILE documents below one visible ROOT resource.
     *
     * <p>The ROOT is checked through the same tenant/role/owner policy as management operations. The store then applies
     * the FILE, parse, embedding, visibility, and SQL limit predicates without loading any blocks.</p>
     */
    public List<TeacherFileDocumentResponse> listPhysicalFiles(
            String tenantId,
            String viewerRole,
            String viewerSubjectId,
            String rootDocumentId,
            int limit) {
        String normalizedTenantId = requireText(tenantId, "tenantId is required");
        String normalizedRole = requireText(viewerRole, "viewerRole is required").toLowerCase();
        String normalizedSubjectId = requireText(viewerSubjectId, "viewerSubjectId is required");
        requireTeacherOrAdmin(normalizedRole);
        if (limit < 1 || limit > 512) {
            throw new IllegalArgumentException("limit must be between 1 and 512");
        }
        TeacherResourceDocumentResponse root = store.find(normalizedTenantId, requireText(rootDocumentId, "rootDocumentId is required"));
        if (root == null || !"feishu".equalsIgnoreCase(textOrDefault(root.sourceType(), ""))) {
            throw new IllegalArgumentException("Teacher resource ROOT not found: " + rootDocumentId);
        }
        boolean visibleRoot = store.listVisible(normalizedTenantId, normalizedRole, normalizedSubjectId).stream()
                .anyMatch(candidate -> root.documentId().equals(candidate.documentId()));
        if (!visibleRoot) {
            throw new IllegalArgumentException("Teacher resource ROOT not visible: " + rootDocumentId);
        }
        return store.listSearchableFileDocuments(
                        normalizedTenantId,
                        normalizedRole,
                        normalizedSubjectId,
                        List.of(root.documentId()),
                        limit)
                .stream()
                .map(TeacherFileDocumentResponse::from)
                .toList();
    }

    /** Returns whether one persisted FILE document is visible and searchable for this viewer. */
    public boolean isVisiblePhysicalFile(
            String tenantId,
            String viewerRole,
            String viewerSubjectId,
            String fileDocumentId) {
        String normalizedTenantId = requireText(tenantId, "tenantId is required");
        String normalizedRole = requireText(viewerRole, "viewerRole is required").toLowerCase();
        String normalizedSubjectId = requireText(viewerSubjectId, "viewerSubjectId is required");
        requireTeacherOrAdmin(normalizedRole);
        String normalizedFileId = requireText(fileDocumentId, "fileDocumentId is required");
        return store.listSearchableFileDocumentsByIds(
                        normalizedTenantId, normalizedRole, normalizedSubjectId, List.of(normalizedFileId), 1)
                .stream()
                .anyMatch(file -> normalizedFileId.equals(file.documentId()));
    }

    /**
     * Archives a resource document. Admin can archive any tenant resource; teachers can archive their own resources.
     *
     * @param tenantId tenant id
     * @param viewerRole backend-resolved viewer role
     * @param viewerSubjectId backend-resolved viewer subject id
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
        if (syncJobStore != null) {
            syncJobStore.terminateActiveByDocument(normalizedTenantId, documentId, Instant.now());
        }
        if (store.supportsFileDocuments()) {
            purgeFileDocuments(
                    normalizedTenantId,
                    document.documentId());
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

    /** Cleans each persisted FILE under a ROOT without loading blocks or the full child set. */
    private void purgeFileDocuments(String tenantId, String rootDocumentId) {
        String afterFileDocumentId = "";
        while (true) {
            List<TeacherResourceStore.TeacherFileDocument> files = store.listFileDocumentsForIndexing(
                    tenantId,
                    rootDocumentId,
                    64,
                    afterFileDocumentId);
            if (files.isEmpty()) {
                return;
            }
            for (TeacherResourceStore.TeacherFileDocument file : files) {
                vectorIndexService.deleteTeacherResourceVectors(tenantId, file.documentId());
                vectorIndexService.purgeTeacherResourceContent(tenantId, file.documentId());
                assetService.purgeDocumentAssets(tenantId, file.documentId());
                store.archiveFileDocument(tenantId, file.documentId());
                afterFileDocumentId = file.documentId();
            }
            if (files.size() < 64) {
                return;
            }
        }
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
                || "TEACHER_SHARED".equals(normalizedScope)
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
        return "TEACHER_SHARED".equalsIgnoreCase(textOrDefault(permissionScope, ""))
                || "TENANT_PUBLIC".equalsIgnoreCase(textOrDefault(permissionScope, ""))
                || "CLASS_AUTHORIZED".equalsIgnoreCase(textOrDefault(permissionScope, ""))
                || "MATH_VIP".equalsIgnoreCase(textOrDefault(permissionScope, ""));
    }

    /**
     * Builds a small local file preview for a registered path.
     *
     * @param localPath local file or folder path
     * @return preview file list
     */
    private List<TeacherResourceDocumentResponse.PreviewFile> previewFiles(
            String sourceType, String localPath, String feishuExportFormat) {
        if (localPath == null || localPath.isBlank()) {
            return List.of();
        }
        Path root;
        try {
            root = Path.of(localPath).toAbsolutePath().normalize();
            if ("feishu".equalsIgnoreCase(textOrDefault(sourceType, "")) && syncProperties != null) {
                root = syncProperties.requireStagingPath(root);
            } else if ("feishu".equalsIgnoreCase(textOrDefault(sourceType, ""))) {
                return List.of();
            }
            root = root.toRealPath();
        } catch (IOException | IllegalArgumentException exception) {
            return List.of();
        }
        if (!Files.exists(root)) {
            return List.of();
        }
        String requiredExtension = "feishu".equalsIgnoreCase(textOrDefault(sourceType, ""))
                ? "." + textOrDefault(feishuExportFormat, "md").toLowerCase()
                : "";
        if (Files.isRegularFile(root)) {
            return matchesPreviewExtension(root, requiredExtension)
                    ? List.of(previewFile(root.getParent(), root))
                    : List.of();
        }
        Path realRoot = root;
        try (Stream<Path> stream = Files.walk(root, MAX_PREVIEW_DEPTH)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(path -> matchesPreviewExtension(path, requiredExtension))
                    .filter(path -> {
                        try {
                            return path.toRealPath().startsWith(realRoot);
                        } catch (IOException exception) {
                            return false;
                        }
                    })
                    .map(path -> previewFile(realRoot, path))
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
        if (!Files.exists(candidate)) {
            return;
        }
        try {
            candidate = syncProperties.requireStagingPath(candidate);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Archived Feishu staging path is outside the configured staging root", exception);
        }
        if (!candidate.startsWith(root) || candidate.equals(root)) {
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
        Path root = resourceProperties.teacherResourceUploadRoot();
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

    private static boolean matchesPreviewExtension(Path file, String requiredExtension) {
        if (requiredExtension == null || requiredExtension.isBlank()) {
            return true;
        }
        return file.getFileName().toString().toLowerCase().endsWith(requiredExtension);
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
