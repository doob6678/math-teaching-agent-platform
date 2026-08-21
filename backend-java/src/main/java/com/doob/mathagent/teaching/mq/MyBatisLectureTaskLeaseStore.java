package com.doob.mathagent.teaching.mq;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.doob.mathagent.teaching.entity.TeachingTaskEntity;
import com.doob.mathagent.teaching.mapper.TeachingTaskMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

/** MySQL CAS lease adapter for the top-level lecture workflow. */
@Repository
@ConditionalOnProperty(prefix = "math-agent.database", name = "enabled", havingValue = "true")
public class MyBatisLectureTaskLeaseStore implements LectureTaskLeaseStore {
    private final TeachingTaskMapper mapper;
    public MyBatisLectureTaskLeaseStore(TeachingTaskMapper mapper) { this.mapper = mapper; }
    @Override public LectureTaskLease tryAcquire(String taskId, String workerId, Instant now, Duration duration) {
        String token = UUID.randomUUID().toString(); Instant expiry = now.plus(duration);
        int changed = mapper.tryAcquireLectureTask(taskId, workerId, token, expiry, now);
        if (changed == 0) return null;
        TeachingTaskEntity claimed = mapper.selectById(taskId);
        return new LectureTaskLease(taskId, token, workerId, claimed.getRetryCount(), expiry);
    }
    @Override public boolean complete(LectureTaskLease lease) { return mapper.update(null, owned(lease).set(TeachingTaskEntity::getStatus, "COMPLETED").set(TeachingTaskEntity::getLeaseToken, null).set(TeachingTaskEntity::getLeaseExpireAt, null).set(TeachingTaskEntity::getFinishedAt, Instant.now())) == 1; }
    @Override public java.util.List<String> reclaimExpired(Instant now, int limit) {
        if (limit < 1) return java.util.List.of();
        java.util.List<String> reclaimed = new java.util.ArrayList<>();
        for (TeachingTaskEntity candidate : mapper.findExpiredLectureLeases(now, limit)) {
            if (candidate.getLeaseToken() != null
                    && mapper.reclaimExpiredLectureTask(candidate.getTaskId(), candidate.getLeaseToken(), now) == 1) {
                reclaimed.add(candidate.getTaskId());
            }
        }
        return java.util.List.copyOf(reclaimed);
    }
    @Override public boolean renew(LectureTaskLease lease, Instant expiresAt) { return mapper.update(null, owned(lease).set(TeachingTaskEntity::getLeaseExpireAt, expiresAt)) == 1; }
    @Override public FailureOutcome failOrRetry(LectureTaskLease lease, String error, int maximumAttempts) {
        boolean retry = lease.retryCount() < maximumAttempts;
        int changed = mapper.update(null, owned(lease)
                .set(TeachingTaskEntity::getStatus, retry ? "RETRYING" : "FAILED")
                .set(TeachingTaskEntity::getLastError, safe(error))
                .set(TeachingTaskEntity::getLeaseToken, null)
                .set(TeachingTaskEntity::getLeaseExpireAt, null)
                .set(TeachingTaskEntity::getFinishedAt, retry ? null : Instant.now()));
        if (changed != 1) return FailureOutcome.LEASE_LOST;
        return retry ? FailureOutcome.RETRYING : FailureOutcome.TERMINAL_FAILURE;
    }
    private static LambdaUpdateWrapper<TeachingTaskEntity> owned(LectureTaskLease lease) { return new LambdaUpdateWrapper<TeachingTaskEntity>().eq(TeachingTaskEntity::getTaskId, lease.taskId()).eq(TeachingTaskEntity::getStatus, "RUNNING").eq(TeachingTaskEntity::getLeaseToken, lease.token()); }
    private static String safe(String message) { return message == null || message.isBlank() ? "Lecture task failed" : message.substring(0, Math.min(512, message.length())); }
}
