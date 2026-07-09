package com.doob.mathagent.teacher.service;

import com.doob.mathagent.teacher.vo.TeacherResourceDocumentResponse;
import com.doob.mathagent.vector.service.VectorIndexService;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.springframework.stereotype.Service;

/**
 * Service for teacher/admin resource registration, preview, and archive operations.
 */
@Service
public class TeacherResourceService {

    private static final int MAX_PREVIEW_FILES = 8;
    private static final int MAX_PREVIEW_DEPTH = 6;

    private final TeacherResourceStore store;
    private final VectorIndexService vectorIndexService;

    /**
     * Creates a teacher resource service.
     *
     * @param store resource store
     */
    public TeacherResourceService(TeacherResourceStore store, VectorIndexService vectorIndexService) {
        this.store = store;
        this.vectorIndexService = vectorIndexService;
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
        TeacherResourceDocumentResponse document = new TeacherResourceDocumentResponse(
                UUID.randomUUID().toString(),
                normalized.tenantId(),
                normalized.viewerSubjectId(),
                normalized.sourceType(),
                normalized.title(),
                normalized.originalUrl(),
                normalized.localPath(),
                normalizePermissionScope(normalized.permissionScope(), normalized.viewerRole()),
                "registered",
                "pending",
                "pending",
                "waiting_rebuild",
                normalized.feishuExportFormat(),
                previewFiles(normalized.localPath()),
                normalized.parseMode());
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
        return store.listVisible(normalizedTenantId, normalizedRole, normalizedSubjectId);
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
                document.parseMode());
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
     * Prevents non-admin teachers from self-assigning shared or public RAG permission scopes.
     *
     * @param permissionScope requested permission scope
     * @param viewerRole backend resolved viewer role
     * @return safe permission scope
     */
    private static String normalizePermissionScope(String permissionScope, String viewerRole) {
        if (!"admin".equals(viewerRole)) {
            return "TEACHER_PRIVATE";
        }
        String normalizedScope = textOrDefault(permissionScope, "TEACHER_PRIVATE").toUpperCase();
        if ("MATH_VIP".equals(normalizedScope) || "PUBLIC_TEXTBOOK".equals(normalizedScope)) {
            return normalizedScope;
        }
        return "TEACHER_PRIVATE";
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
            throw new IllegalArgumentException("Local resource path is invalid: " + exception.getInput(), exception);
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
                    .limit(MAX_PREVIEW_FILES)
                    .map(path -> previewFile(root, path))
                    .toList();
        } catch (IOException exception) {
            return List.of();
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
