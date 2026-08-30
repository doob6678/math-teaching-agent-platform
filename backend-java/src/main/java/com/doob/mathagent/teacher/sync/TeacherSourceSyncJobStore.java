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

    /**
     * Reads one newest-first page of jobs for a source document.
     *
     * <p>The default keeps non-database implementations compatible. The production store overrides it so the
     * database, rather than the browser or service layer, applies the page boundary.</p>
     *
     * @param tenantId tenant id
     * @param documentId source document id
     * @param pageNumber one-based requested page number
     * @param pageSize maximum jobs in the requested page
     * @return requested newest-first page
     */
    default List<TeacherSourceSyncJobResponse> listPageByDocument(
            String tenantId, String documentId, int pageNumber, int pageSize) {
        List<TeacherSourceSyncJobResponse> jobs = listByDocument(tenantId, documentId);
        int startIndex = Math.multiplyExact(pageNumber - 1, pageSize);
        if (startIndex >= jobs.size()) {
            return List.of();
        }
        return jobs.subList(startIndex, Math.min(startIndex + pageSize, jobs.size()));
    }

    /** Finds an active queued, running, or paused job for duplicate-click and scheduler idempotency. */
    default TeacherSourceSyncJobResponse findActiveByDocument(String tenantId, String documentId) {
        return null;
    }

    /** Terminates non-terminal jobs when their source document is archived. */
    default int terminateActiveByDocument(String tenantId, String documentId, Instant now) {
        return 0;
    }

    /** Recovers a worker job that stayed running beyond its lease after a process or host crash. */
    default int recoverStaleRunningJobs(Instant now, long staleAfterSeconds) {
        return 0;
    }
}
