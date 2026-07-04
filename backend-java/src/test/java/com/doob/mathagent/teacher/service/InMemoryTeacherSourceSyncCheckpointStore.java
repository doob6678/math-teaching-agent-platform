package com.doob.mathagent.teacher.service;

import com.doob.mathagent.teacher.vo.TeacherSourceSyncCheckpointResponse;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory checkpoint store for local development and no-database tests.
 */
public class InMemoryTeacherSourceSyncCheckpointStore implements TeacherSourceSyncCheckpointStore {

    private final Map<String, TeacherSourceSyncCheckpointResponse> checkpoints = new ConcurrentHashMap<>();

    /**
     * Saves a checkpoint snapshot under a tenant/job compound key.
     */
    @Override
    public TeacherSourceSyncCheckpointResponse save(TeacherSourceSyncCheckpointResponse checkpoint) {
        checkpoints.put(key(checkpoint.tenantId(), checkpoint.jobId()), checkpoint);
        return checkpoint;
    }

    /**
     * Finds a checkpoint by tenant and job id without cross-tenant leakage.
     */
    @Override
    public Optional<TeacherSourceSyncCheckpointResponse> findByJobId(String tenantId, String jobId) {
        return Optional.ofNullable(checkpoints.get(key(tenantId, jobId)));
    }

    /**
     * Builds a stable map key from backend-resolved tenant and job id.
     */
    private static String key(String tenantId, String jobId) {
        return tenantId + ":" + jobId;
    }
}
