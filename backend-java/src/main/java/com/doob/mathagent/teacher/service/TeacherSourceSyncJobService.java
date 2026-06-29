package com.doob.mathagent.teacher.service;

import com.doob.mathagent.teacher.vo.TeacherResourceDocumentResponse;
import com.doob.mathagent.teacher.vo.TeacherSourceSyncJobResponse;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Queues and lists teacher source synchronization jobs.
 */
@Service
public class TeacherSourceSyncJobService {

    private final TeacherResourceStore resourceStore;
    private final TeacherSourceSyncJobStore jobStore;

    /**
     * Creates a source sync job service.
     *
     * @param resourceStore teacher resource store
     * @param jobStore sync job store
     */
    public TeacherSourceSyncJobService(TeacherResourceStore resourceStore, TeacherSourceSyncJobStore jobStore) {
        this.resourceStore = resourceStore;
        this.jobStore = jobStore;
    }

    /**
     * Creates a queued synchronization job for an owned teacher resource.
     *
     * @param tenantId tenant id
     * @param viewerRole backend-resolved viewer role
     * @param viewerSubjectId backend-resolved viewer subject id
     * @param documentId source document id
     * @return queued job
     */
    public TeacherSourceSyncJobResponse createSyncJob(
            String tenantId,
            String viewerRole,
            String viewerSubjectId,
            String documentId) {
        String normalizedTenantId = textOrDefault(tenantId, "default");
        String normalizedRole = textOrDefault(viewerRole, "teacher").toLowerCase();
        String normalizedSubjectId = textOrDefault(viewerSubjectId, "local-teacher-console");
        requireTeacherOrAdmin(normalizedRole);
        TeacherResourceDocumentResponse document = requireVisibleDocument(
                normalizedTenantId,
                normalizedRole,
                normalizedSubjectId,
                documentId);
        String now = Instant.now().toString();
        TeacherSourceSyncJobResponse job = new TeacherSourceSyncJobResponse(
                UUID.randomUUID().toString(),
                document.documentId(),
                document.tenantId(),
                document.sourceType(),
                operationFor(document.sourceType()),
                "queued",
                phaseFor(document.sourceType()),
                0,
                normalizedSubjectId,
                null,
                messageFor(document.sourceType()),
                now,
                now);
        return jobStore.save(job);
    }

    /**
     * Lists sync jobs for a visible teacher resource.
     *
     * @param tenantId tenant id
     * @param viewerRole backend-resolved viewer role
     * @param viewerSubjectId backend-resolved viewer subject id
     * @param documentId source document id
     * @return sync jobs for the resource
     */
    public List<TeacherSourceSyncJobResponse> listSyncJobs(
            String tenantId,
            String viewerRole,
            String viewerSubjectId,
            String documentId) {
        String normalizedTenantId = textOrDefault(tenantId, "default");
        String normalizedRole = textOrDefault(viewerRole, "teacher").toLowerCase();
        String normalizedSubjectId = textOrDefault(viewerSubjectId, "local-teacher-console");
        requireTeacherOrAdmin(normalizedRole);
        TeacherResourceDocumentResponse document = requireVisibleDocument(
                normalizedTenantId,
                normalizedRole,
                normalizedSubjectId,
                documentId);
        return jobStore.listByDocument(document.tenantId(), document.documentId());
    }

    private static void requireTeacherOrAdmin(String viewerRole) {
        if (!"teacher".equals(viewerRole) && !"admin".equals(viewerRole)) {
            throw new IllegalArgumentException("Teacher resource sync requires teacher or admin role");
        }
    }

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
            throw new IllegalArgumentException("Teacher can sync only own resources");
        }
        return document;
    }

    private static String operationFor(String sourceType) {
        return switch (textOrDefault(sourceType, "resource").toLowerCase()) {
            case "feishu" -> "feishu_download";
            case "local_path", "local_docx" -> "local_scan";
            case "textbook_md" -> "textbook_md_import";
            default -> "resource_sync";
        };
    }

    private static String phaseFor(String sourceType) {
        return switch (textOrDefault(sourceType, "resource").toLowerCase()) {
            case "feishu" -> "download_pending";
            case "local_path", "local_docx" -> "scan_pending";
            case "textbook_md" -> "import_pending";
            default -> "sync_pending";
        };
    }

    private static String messageFor(String sourceType) {
        if ("feishu".equalsIgnoreCase(textOrDefault(sourceType, ""))) {
            return "Feishu source sync job queued; downloader worker has not completed yet.";
        }
        return "Source sync job queued; parser and index rebuild are pending.";
    }

    private static String textOrDefault(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value.strip();
    }
}
