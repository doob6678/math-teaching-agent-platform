package com.doob.mathagent.student.service;

import java.util.Optional;

/**
 * Store abstraction for student learning snapshots.
 */
public interface StudentLearningSnapshotStore {

    /**
     * Finds the latest dashboard snapshot for one tenant-scoped student.
     *
     * @param tenantId backend-resolved tenant id
     * @param studentId backend-resolved student id
     * @return latest snapshot when present
     */
    Optional<StudentLearningSnapshotRecord> findLatest(String tenantId, String studentId);
}
