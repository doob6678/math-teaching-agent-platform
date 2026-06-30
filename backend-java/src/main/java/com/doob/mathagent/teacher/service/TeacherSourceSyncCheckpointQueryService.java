package com.doob.mathagent.teacher.service;

import com.doob.mathagent.teacher.vo.TeacherResourceDocumentResponse;
import com.doob.mathagent.teacher.vo.TeacherSourceSyncCheckpointResponse;
import java.util.Locale;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * Read-only query service for durable Feishu/source sync checkpoints.
 */
@Service
public class TeacherSourceSyncCheckpointQueryService {

    private final TeacherResourceStore resourceStore;
    private final TeacherSourceSyncJobStore jobStore;
    private final TeacherSourceSyncCheckpointStore checkpointStore;

    /**
     * Creates a checkpoint query service.
     *
     * @param resourceStore teacher resource store used for ownership checks
     * @param jobStore source sync job store used to bind job and document
     * @param checkpointStore durable checkpoint store
     */
    public TeacherSourceSyncCheckpointQueryService(
            TeacherResourceStore resourceStore,
            TeacherSourceSyncJobStore jobStore,
            TeacherSourceSyncCheckpointStore checkpointStore) {
        this.resourceStore = resourceStore;
        this.jobStore = jobStore;
        this.checkpointStore = checkpointStore;
    }

    /**
     * Finds a checkpoint after verifying the backend subject can see the resource and job.
     *
     * @param tenantId backend-resolved tenant id
     * @param viewerRole backend-resolved viewer role
     * @param viewerSubjectId backend-resolved viewer subject id
     * @param documentId source document id
     * @param jobId source sync job id
     * @return checkpoint when present
     */
    public Optional<TeacherSourceSyncCheckpointResponse> findCheckpoint(
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
        boolean jobBelongsToDocument = jobStore.listByDocument(document.tenantId(), document.documentId()).stream()
                .anyMatch(job -> job.jobId().equals(jobId));
        if (!jobBelongsToDocument) {
            throw new IllegalArgumentException("Teacher source sync job not found: " + jobId);
        }
        return checkpointStore.findByJobId(document.tenantId(), jobId);
    }

    /**
     * Verifies teacher/admin role for checkpoint reads.
     */
    private static void requireTeacherOrAdmin(String viewerRole) {
        if (!"teacher".equals(viewerRole) && !"admin".equals(viewerRole)) {
            throw new IllegalArgumentException("Teacher source sync checkpoint requires teacher or admin role");
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
            throw new IllegalArgumentException("Teacher can read checkpoints only for own resources");
        }
        return document;
    }

    /**
     * Returns stripped text or fallback.
     */
    private static String textOrDefault(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value.strip();
    }
}
