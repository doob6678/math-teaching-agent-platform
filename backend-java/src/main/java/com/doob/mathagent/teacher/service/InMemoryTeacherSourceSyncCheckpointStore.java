package com.doob.mathagent.teacher.service;

import com.doob.mathagent.teacher.vo.TeacherSourceSyncCheckpointResponse;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

/**
 * In-memory checkpoint store for local development and no-database tests.
 */
@Repository
@ConditionalOnProperty(prefix = "math-agent.database", name = "enabled", havingValue = "false", matchIfMissing = true)
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
