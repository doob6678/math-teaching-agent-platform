package com.doob.mathagent.teacher.service;

import com.doob.mathagent.teacher.vo.TeacherSourceSyncJobResponse;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

/**
 * In-memory source sync job store for local development and tests.
 */
@Repository
@ConditionalOnProperty(prefix = "math-agent.database", name = "enabled", havingValue = "false", matchIfMissing = true)
public class InMemoryTeacherSourceSyncJobStore implements TeacherSourceSyncJobStore {

    private final Map<String, TeacherSourceSyncJobResponse> jobs = new ConcurrentHashMap<>();

    @Override
    public TeacherSourceSyncJobResponse save(TeacherSourceSyncJobResponse job) {
        jobs.put(job.jobId(), job);
        return job;
    }

    @Override
    public List<TeacherSourceSyncJobResponse> listByDocument(String tenantId, String documentId) {
        return jobs.values().stream()
                .filter(job -> job.tenantId().equals(tenantId))
                .filter(job -> job.documentId().equals(documentId))
                .sorted(Comparator.comparing(TeacherSourceSyncJobResponse::createdAt).reversed())
                .toList();
    }
}
