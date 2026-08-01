package com.doob.mathagent.teacher.sync;

import com.doob.mathagent.teacher.vo.TeacherSourceSyncJobResponse;
import java.util.List;
import java.time.Instant;

/**
 * Store abstraction for durable source synchronization jobs.
 */
public interface TeacherSourceSyncJobStore {

    /**
     * Saves a synchronization job.
     *
     * @param job job response
     * @return saved job
     */
    TeacherSourceSyncJobResponse save(TeacherSourceSyncJobResponse job);

    /**
     * Lists jobs for a source document.
     *
     * @param tenantId tenant id
     * @param documentId source document id
     * @return jobs newest first
     */
    List<TeacherSourceSyncJobResponse> listByDocument(String tenantId, String documentId);

    /** Finds an active queued, running, or paused job for duplicate-click and scheduler idempotency. */
    default TeacherSourceSyncJobResponse findActiveByDocument(String tenantId, String documentId) {
        return null;
    }

    /** Recovers a worker job that stayed running beyond its lease after a process or host crash. */
    default int recoverStaleRunningJobs(Instant now, long staleAfterSeconds) {
        return 0;
    }
}
