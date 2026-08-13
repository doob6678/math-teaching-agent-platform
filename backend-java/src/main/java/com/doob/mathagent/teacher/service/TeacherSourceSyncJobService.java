package com.doob.mathagent.teacher.service;

import com.doob.mathagent.teacher.document.TeacherResourceStore;
import com.doob.mathagent.teacher.document.TeacherResourceDocumentResponse;
import com.doob.mathagent.teacher.sync.TeacherSourceSyncJobStore;
import com.doob.mathagent.teacher.vo.TeacherSourceSyncJobResponse;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * Queues and lists teacher source synchronization jobs.
 */
@Service
public class TeacherSourceSyncJobService {

    /** First page and smallest valid size keep list cards bounded without hiding the newest durable job. */
    private static final int FIRST_SYNC_JOB_PAGE = 1;
    private static final int MINIMUM_SYNC_JOB_PAGE_SIZE = 1;
    /** Protects the operational endpoint from callers attempting to recreate an unbounded history response. */
    private static final int MAXIMUM_SYNC_JOB_PAGE_SIZE = 25;

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
        String normalizedTenantId = requireText(tenantId, "tenantId is required");
        String normalizedRole = requireText(viewerRole, "viewerRole is required").toLowerCase();
        String normalizedSubjectId = requireText(viewerSubjectId, "viewerSubjectId is required");
        requireTeacherOrAdmin(normalizedRole);
        TeacherResourceDocumentResponse document = requireVisibleDocument(
                normalizedTenantId,
                normalizedRole,
                normalizedSubjectId,
                documentId);
        TeacherSourceSyncJobResponse active = jobStore.findActiveByDocument(document.tenantId(), document.documentId());
        if (active != null) {
            return active;
        }
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
        try {
            return jobStore.save(job);
        } catch (DataIntegrityViolationException exception) {
            /*
             * The MySQL generated-column unique key is the cross-node arbiter. A concurrent click can pass the
             * preceding read, so resolve the winning active job instead of returning a spurious duplicate failure.
             */
            TeacherSourceSyncJobResponse winner = jobStore.findActiveByDocument(document.tenantId(), document.documentId());
            if (winner != null) {
                return winner;
            }
            throw exception;
        }
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
        String normalizedTenantId = requireText(tenantId, "tenantId is required");
        String normalizedRole = requireText(viewerRole, "viewerRole is required").toLowerCase();
        String normalizedSubjectId = requireText(viewerSubjectId, "viewerSubjectId is required");
        requireTeacherOrAdmin(normalizedRole);
        TeacherResourceDocumentResponse document = requireVisibleDocument(
                normalizedTenantId,
                normalizedRole,
                normalizedSubjectId,
                documentId);
        return jobStore.listByDocument(document.tenantId(), document.documentId());
    }

    /**
     * Lists one bounded newest-first page of visible sync jobs.
     *
     * <p>Teacher-resource cards use this method with a one-item page because they render only the latest job. The
     * backend validates the boundary so a client cannot turn the paged endpoint back into an unbounded read.</p>
     *
     * @param tenantId tenant id
     * @param viewerRole backend-resolved viewer role
     * @param viewerSubjectId backend-resolved viewer subject id
     * @param documentId source document id
     * @param pageNumber one-based requested page
     * @param pageSize requested rows per page
     * @return bounded newest-first job page
     */
    public List<TeacherSourceSyncJobResponse> listSyncJobs(
            String tenantId,
            String viewerRole,
            String viewerSubjectId,
            String documentId,
            int pageNumber,
            int pageSize) {
        String normalizedTenantId = requireText(tenantId, "tenantId is required");
        String normalizedRole = requireText(viewerRole, "viewerRole is required").toLowerCase();
        String normalizedSubjectId = requireText(viewerSubjectId, "viewerSubjectId is required");
        requireTeacherOrAdmin(normalizedRole);
        if (pageNumber < FIRST_SYNC_JOB_PAGE) {
            throw new IllegalArgumentException("pageNumber must be at least " + FIRST_SYNC_JOB_PAGE);
        }
        if (pageSize < MINIMUM_SYNC_JOB_PAGE_SIZE || pageSize > MAXIMUM_SYNC_JOB_PAGE_SIZE) {
            throw new IllegalArgumentException("pageSize must be between " + MINIMUM_SYNC_JOB_PAGE_SIZE
                    + " and " + MAXIMUM_SYNC_JOB_PAGE_SIZE);
        }
        TeacherResourceDocumentResponse document = requireVisibleDocument(
                normalizedTenantId, normalizedRole, normalizedSubjectId, documentId);
        return jobStore.listPageByDocument(document.tenantId(), document.documentId(), pageNumber, pageSize);
    }

    /**
     * Returns one job only after applying the same resource visibility rule used by the list endpoint.
     * This lets asynchronous dispatch return the durable queued state without letting a caller probe another tenant.
     */
    public TeacherSourceSyncJobResponse findVisibleJob(
            String tenantId,
            String viewerRole,
            String viewerSubjectId,
            String documentId,
            String jobId) {
        String normalizedTenantId = requireText(tenantId, "tenantId is required");
        String normalizedRole = requireText(viewerRole, "viewerRole is required").toLowerCase();
        String normalizedSubjectId = requireText(viewerSubjectId, "viewerSubjectId is required");
        requireTeacherOrAdmin(normalizedRole);
        TeacherResourceDocumentResponse document = requireVisibleDocument(
                normalizedTenantId, normalizedRole, normalizedSubjectId, documentId);
        String normalizedJobId = requireText(jobId, "jobId is required");
        return jobStore.listByDocument(document.tenantId(), document.documentId()).stream()
                .filter(job -> normalizedJobId.equals(job.jobId()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Teacher source sync job not found: " + normalizedJobId));
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
            case "local_path" -> "local_scan";
            default -> "resource_sync";
        };
    }

    private static String phaseFor(String sourceType) {
        return switch (textOrDefault(sourceType, "resource").toLowerCase()) {
            case "feishu" -> "download_pending";
            case "local_path" -> "scan_pending";
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

    private static String requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value.strip();
    }
}
