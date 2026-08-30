package com.doob.mathagent.teacher.service;

import com.doob.mathagent.teacher.sync.TeacherSourceSyncJobStore;
import com.doob.mathagent.teacher.vo.TeacherSourceSyncJobResponse;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory source sync job store for local development and tests.
 */
public class InMemoryTeacherSourceSyncJobStore implements TeacherSourceSyncJobStore {

    private final Map<String, TeacherSourceSyncJobResponse> jobs = new ConcurrentHashMap<>();

    @Override
    public TeacherSourceSyncJobResponse save(TeacherSourceSyncJobResponse job) {
        jobs.put(job.jobId(), job);
        return job;
    }

    @Override
    public int terminateActiveByDocument(String tenantId, String documentId, java.time.Instant now) {
        int[] count = {0};
        jobs.replaceAll((jobId, job) -> {
            if (!job.tenantId().equals(tenantId) || !job.documentId().equals(documentId)
                    || !java.util.Set.of("queued", "running", "paused", "AUTH_REQUIRED").contains(job.status())) {
                return job;
            }
            count[0]++;
            return new TeacherSourceSyncJobResponse(
                    job.jobId(), job.documentId(), job.tenantId(), job.sourceType(), job.operation(),
                    "cancelled", "resource_archived", job.attempt(), job.createdBy(), job.stagingPath(),
                    "Source document archived; sync job cancelled", job.createdAt(), now.toString(), job.failure());
        });
        return count[0];
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
