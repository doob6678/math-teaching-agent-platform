package com.doob.mathagent.teacher.service;

import com.doob.mathagent.teacher.vo.TeacherSourceSyncJobResponse;
import java.util.List;

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
}
