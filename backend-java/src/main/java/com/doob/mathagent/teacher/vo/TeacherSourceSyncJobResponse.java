package com.doob.mathagent.teacher.vo;

/**
 * Durable teacher source synchronization job status.
 *
 * @param jobId stable job id
 * @param documentId source document id
 * @param tenantId tenant id
 * @param sourceType source type, such as feishu or local_path
 * @param operation operation code consumed by sync workers
 * @param status job status, such as queued, running, completed, or failed
 * @param phase detailed pipeline phase
 * @param attempt retry attempt count
 * @param createdBy backend-resolved subject id that queued the job
 * @param stagingPath optional worker staging path
 * @param message human-readable status message
 * @param createdAt creation timestamp
 * @param updatedAt last update timestamp
 */
public record TeacherSourceSyncJobResponse(
        String jobId,
        String documentId,
        String tenantId,
        String sourceType,
        String operation,
        String status,
        String phase,
        int attempt,
        String createdBy,
        String stagingPath,
        String message,
        String createdAt,
        String updatedAt,
        TeacherSourceSyncFailureResponse failure) {

    /** Compatibility constructor for callers that do not have structured provider failure information. */
    public TeacherSourceSyncJobResponse(
            String jobId,
            String documentId,
            String tenantId,
            String sourceType,
            String operation,
            String status,
            String phase,
            int attempt,
            String createdBy,
            String stagingPath,
            String message,
            String createdAt,
            String updatedAt) {
        this(jobId, documentId, tenantId, sourceType, operation, status, phase, attempt, createdBy, stagingPath,
                message, createdAt, updatedAt, TeacherSourceSyncFailureResponse.none());
    }
}
