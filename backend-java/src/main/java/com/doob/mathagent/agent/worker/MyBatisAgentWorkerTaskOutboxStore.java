package com.doob.mathagent.agent.worker;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.doob.mathagent.agent.entity.AgentWorkerTaskEntity;
import com.doob.mathagent.agent.entity.AgentWorkerTaskOutboxEventEntity;
import com.doob.mathagent.agent.mapper.AgentWorkerTaskMapper;
import com.doob.mathagent.agent.mapper.AgentWorkerTaskOutboxEventMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Repository;

/** MySQL implementation of the versioned Agent Worker task outbox. */
@Repository
public class MyBatisAgentWorkerTaskOutboxStore implements AgentWorkerTaskOutboxStore {
    private static final String PENDING = "PENDING";
    private static final String PUBLISHING = "PUBLISHING";
    private static final String PUBLISHED = "PUBLISHED";
    private final AgentWorkerTaskOutboxEventMapper outboxMapper;
    private final AgentWorkerTaskMapper taskMapper;

    public MyBatisAgentWorkerTaskOutboxStore(
            AgentWorkerTaskOutboxEventMapper outboxMapper, AgentWorkerTaskMapper taskMapper) {
        this.outboxMapper = outboxMapper;
        this.taskMapper = taskMapper;
    }

    @Override
    public void enqueue(AgentWorkerTask task) {
        AgentWorkerTaskOutboxEventEntity event = new AgentWorkerTaskOutboxEventEntity();
        Instant now = Instant.now();
        event.setEventId(UUID.randomUUID().toString());
        event.setTaskId(task.taskId());
        event.setDispatchVersion(task.dispatchVersion());
        event.setAgentCode(task.agentCode());
        event.setStageCode(task.stageCode());
        event.setStatus(PENDING);
        event.setPublishAttempt(0);
        event.setNextAttemptAt(now);
        event.setCreatedAt(now);
        try {
            outboxMapper.insert(event);
        } catch (DuplicateKeyException ignored) {
            // The unique task/version key makes reconciliation and concurrent recovery idempotent.
        }
    }

    @Override
    public List<AgentWorkerTaskOutboxEvent> claimReady(
            String publisherId, Instant now, Duration leaseDuration, int limit) {
        int safeLimit = Math.max(1, limit);
        List<AgentWorkerTaskOutboxEvent> claimed = new ArrayList<>();
        List<AgentWorkerTaskOutboxEventEntity> candidates = outboxMapper.selectReadyPending(now, safeLimit);
        for (AgentWorkerTaskOutboxEventEntity candidate : candidates) {
            int updated = outboxMapper.update(null, new LambdaUpdateWrapper<AgentWorkerTaskOutboxEventEntity>()
                    .eq(AgentWorkerTaskOutboxEventEntity::getEventId, candidate.getEventId())
                    .eq(AgentWorkerTaskOutboxEventEntity::getStatus, PENDING)
                    .le(AgentWorkerTaskOutboxEventEntity::getNextAttemptAt, now)
                    .set(AgentWorkerTaskOutboxEventEntity::getStatus, PUBLISHING)
                    .set(AgentWorkerTaskOutboxEventEntity::getLockedBy, publisherId)
                    .set(AgentWorkerTaskOutboxEventEntity::getPublishLeaseUntil, now.plus(leaseDuration))
                    .setSql("publish_attempt = publish_attempt + 1"));
            if (updated == 1) {
                AgentWorkerTaskOutboxEventEntity locked = outboxMapper.selectById(candidate.getEventId());
                claimed.add(toEvent(locked));
            }
        }
        return List.copyOf(claimed);
    }

    @Override
    public boolean markPublished(AgentWorkerTaskOutboxEvent event, Instant publishedAt) {
        return outboxMapper.update(null, new LambdaUpdateWrapper<AgentWorkerTaskOutboxEventEntity>()
                .eq(AgentWorkerTaskOutboxEventEntity::getEventId, event.eventId())
                .eq(AgentWorkerTaskOutboxEventEntity::getStatus, PUBLISHING)
                .eq(AgentWorkerTaskOutboxEventEntity::getLockedBy, event.lockedBy())
                .set(AgentWorkerTaskOutboxEventEntity::getStatus, PUBLISHED)
                .set(AgentWorkerTaskOutboxEventEntity::getPublishedAt, publishedAt)
                .set(AgentWorkerTaskOutboxEventEntity::getPublishLeaseUntil, null)
                .set(AgentWorkerTaskOutboxEventEntity::getLockedBy, null)) == 1;
    }

    @Override
    public void releaseForRetry(AgentWorkerTaskOutboxEvent event, Instant nextAttemptAt, String errorSummary) {
        outboxMapper.update(null, new LambdaUpdateWrapper<AgentWorkerTaskOutboxEventEntity>()
                .eq(AgentWorkerTaskOutboxEventEntity::getEventId, event.eventId())
                .eq(AgentWorkerTaskOutboxEventEntity::getStatus, PUBLISHING)
                .eq(AgentWorkerTaskOutboxEventEntity::getLockedBy, event.lockedBy())
                .set(AgentWorkerTaskOutboxEventEntity::getStatus, PENDING)
                .set(AgentWorkerTaskOutboxEventEntity::getNextAttemptAt, nextAttemptAt)
                .set(AgentWorkerTaskOutboxEventEntity::getPublishLeaseUntil, null)
                .set(AgentWorkerTaskOutboxEventEntity::getLockedBy, null)
                .set(AgentWorkerTaskOutboxEventEntity::getLastError, safe(errorSummary)));
    }

    @Override
    public int recoverExpiredPublishing(Instant now) {
        return outboxMapper.update(null, new LambdaUpdateWrapper<AgentWorkerTaskOutboxEventEntity>()
                .eq(AgentWorkerTaskOutboxEventEntity::getStatus, PUBLISHING)
                .lt(AgentWorkerTaskOutboxEventEntity::getPublishLeaseUntil, now)
                .set(AgentWorkerTaskOutboxEventEntity::getStatus, PENDING)
                .set(AgentWorkerTaskOutboxEventEntity::getNextAttemptAt, now)
                .set(AgentWorkerTaskOutboxEventEntity::getPublishLeaseUntil, null)
                .set(AgentWorkerTaskOutboxEventEntity::getLockedBy, null)
                .set(AgentWorkerTaskOutboxEventEntity::getLastError, "publisher lease expired"));
    }

    @Override
    public List<AgentWorkerTask> findOrphanQueued(Instant olderThan, int limit) {
        int safeLimit = Math.max(1, limit);
        List<AgentWorkerTaskEntity> rows = taskMapper.selectQueuedWithoutCurrentOutbox(olderThan, safeLimit);
        return rows.stream().map(MyBatisAgentWorkerTaskOutboxStore::toTask).toList();
    }

    @Override
    public long pendingCount() {
        return outboxMapper.selectCount(new LambdaQueryWrapper<AgentWorkerTaskOutboxEventEntity>()
                .in(AgentWorkerTaskOutboxEventEntity::getStatus, List.of(PENDING, PUBLISHING)));
    }

    @Override
    public Instant oldestPendingCreatedAt() {
        AgentWorkerTaskOutboxEventEntity oldest = outboxMapper.selectOldestUnpublished();
        return oldest == null ? null : oldest.getCreatedAt();
    }

    private static AgentWorkerTaskOutboxEvent toEvent(AgentWorkerTaskOutboxEventEntity entity) {
        return new AgentWorkerTaskOutboxEvent(entity.getEventId(), entity.getTaskId(), entity.getDispatchVersion(),
                entity.getAgentCode(), entity.getStageCode(), entity.getStatus(), entity.getPublishAttempt(),
                entity.getNextAttemptAt(), entity.getPublishLeaseUntil(), entity.getLockedBy(), entity.getLastError(), entity.getCreatedAt());
    }

    private static AgentWorkerTask toTask(AgentWorkerTaskEntity entity) {
        return new AgentWorkerTask(entity.getTaskId(), entity.getWorkflowId(), entity.getTenantId(), entity.getAgentCode(),
                entity.getStageCode(), entity.getStatus(), entity.getAttempt(), entity.getDispatchVersion(), entity.getLeaseToken(),
                entity.getLeaseExpiresAt(), entity.getWorkerId(), entity.getRequestJson(), entity.getErrorSummary(), entity.getCreatedAt(), entity.getUpdatedAt());
    }

    private static String safe(String value) {
        return value == null || value.isBlank() ? "broker publication failed" : value.substring(0, Math.min(512, value.length()));
    }
}
