package com.doob.mathagent.student.service;

import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

/**
 * Empty snapshot store used by local in-memory development when MySQL is disabled.
 */
@Repository
@ConditionalOnProperty(prefix = "math-agent.database", name = "enabled", havingValue = "false", matchIfMissing = true)
public class EmptyStudentLearningSnapshotStore implements StudentLearningSnapshotStore {

    /**
     * Returns no snapshot so dashboard generation falls back to live baseline aggregation.
     */
    @Override
    public Optional<StudentLearningSnapshotRecord> findLatest(String tenantId, String studentId) {
        return Optional.empty();
    }
}
