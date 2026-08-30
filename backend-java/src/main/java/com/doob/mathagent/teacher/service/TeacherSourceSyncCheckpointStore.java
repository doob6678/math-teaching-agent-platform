package com.doob.mathagent.teacher.service;

import com.doob.mathagent.teacher.vo.TeacherSourceSyncCheckpointResponse;
import java.util.Optional;

/**
 * Store abstraction for source synchronization checkpoints used by resumable Feishu traversal.
 */
public interface TeacherSourceSyncCheckpointStore {

    /**
     * Saves or replaces a checkpoint for the same tenant and sync job.
     *
     * @param checkpoint checkpoint snapshot
     * @return persisted checkpoint snapshot
     */
    TeacherSourceSyncCheckpointResponse save(TeacherSourceSyncCheckpointResponse checkpoint);

    /**
     * Finds a checkpoint by tenant and source sync job id.
     *
     * @param tenantId backend-resolved tenant id
     * @param jobId source sync job id
     * @return checkpoint when present
     */
    Optional<TeacherSourceSyncCheckpointResponse> findByJobId(String tenantId, String jobId);
}
