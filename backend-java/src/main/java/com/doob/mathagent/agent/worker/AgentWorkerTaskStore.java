package com.doob.mathagent.agent.worker;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.doob.mathagent.agent.entity.AgentWorkerTaskEntity;
import com.doob.mathagent.agent.mapper.AgentWorkerTaskMapper;
import com.doob.mathagent.agent.service.HandoutRunMetricsStore;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Repository;

/**
 * Persists the distributed Worker task state machine.
 *
 * <p>Every state-changing update includes the current status and lease token in SQL. This compare-and-set discipline
 * makes RabbitMQ redelivery harmless: only the Worker that currently owns the lease can complete, fail, or release
 * a task.</p>
 */
@Repository
public class AgentWorkerTaskStore {

    private static final String QUEUED = "QUEUED";
    private static final String RUNNING = "RUNNING";
    private static final String COMPLETED = "COMPLETED";
    private static final String FAILED = "FAILED";

    private final AgentWorkerTaskMapper mapper;
    private HandoutRunMetricsStore handoutMetricsStore;

    /** Creates the task store backed by the shared control-plane MySQL database. */
    public AgentWorkerTaskStore(AgentWorkerTaskMapper mapper) {
        this.mapper = mapper;
    }

    /** Optional metrics wiring keeps generic Worker tests independent while recording only handout commands. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void configureHandoutMetricsStore(HandoutRunMetricsStore store) {
        this.handoutMetricsStore = store;
    }

    /**
     * Saves a command before it is published so a broker retry can always reload the authoritative payload.
     */
    public AgentWorkerTask create(
            String workflowId, String tenantId, String agentCode, String stageCode, String requestJson) {
        AgentWorkerTaskEntity entity = new AgentWorkerTaskEntity();
        entity.setTaskId(UUID.randomUUID().toString());
        entity.setWorkflowId(workflowId);
        entity.setTenantId(tenantId);
        entity.setAgentCode(agentCode);
        entity.setStageCode(stageCode);
        entity.setStatus(QUEUED);
        entity.setAttempt(0);
        entity.setDispatchVersion(1);
        entity.setRequestJson(requestJson);
        entity.setCreatedAt(Instant.now());
        entity.setUpdatedAt(Instant.now());
        mapper.insert(entity);
        AgentWorkerTask task = toTask(entity);
        if (handoutMetricsStore != null && "python_handout".equals(stageCode)) {
            handoutMetricsStore.recordEnqueued(workflowId, task.taskId(), task.createdAt());
        }
        return task;
    }

    /**
     * Atomically assigns one queued task to a Worker and returns its new opaque lease token.
     *
     * @return claimed task, or {@code null} when another Worker has already won the delivery race
     */
    public AgentWorkerTask claim(String taskId, String workerId, Instant expiresAt) {
        String leaseToken = UUID.randomUUID().toString();
        int updated = mapper.update(null, new LambdaUpdateWrapper<AgentWorkerTaskEntity>()
                .eq(AgentWorkerTaskEntity::getTaskId, taskId)
                .eq(AgentWorkerTaskEntity::getStatus, QUEUED)
                .set(AgentWorkerTaskEntity::getStatus, RUNNING)
                .set(AgentWorkerTaskEntity::getWorkerId, workerId)
                .set(AgentWorkerTaskEntity::getLeaseToken, leaseToken)
                .set(AgentWorkerTaskEntity::getLeaseExpiresAt, expiresAt)
                .setSql("attempt = attempt + 1"));
        return updated == 0 ? null : toTask(mapper.selectById(taskId));
    }

    /** Marks a task successful only when the caller still owns its active lease. */
    public boolean complete(String taskId, String leaseToken) {
        return mapper.update(null, new LambdaUpdateWrapper<AgentWorkerTaskEntity>()
                .eq(AgentWorkerTaskEntity::getTaskId, taskId)
                .eq(AgentWorkerTaskEntity::getLeaseToken, leaseToken)
                .eq(AgentWorkerTaskEntity::getStatus, RUNNING)
                .set(AgentWorkerTaskEntity::getStatus, COMPLETED)
                .set(AgentWorkerTaskEntity::getLeaseExpiresAt, null)) > 0;
    }

    /** Extends a live lease without allowing a reclaimed Worker to renew it. */
    public boolean renew(AgentWorkerTask task, Instant expiresAt) {
        return mapper.update(null, new LambdaUpdateWrapper<AgentWorkerTaskEntity>()
                .eq(AgentWorkerTaskEntity::getTaskId, task.taskId())
                .eq(AgentWorkerTaskEntity::getLeaseToken, task.leaseToken())
                .eq(AgentWorkerTaskEntity::getStatus, RUNNING)
                .set(AgentWorkerTaskEntity::getLeaseExpiresAt, expiresAt)) > 0;
    }

    /**
     * Stores the safe failure summary and either releases the task for another attempt or makes it terminal.
     */
    public AgentWorkerTask failOrRequeue(AgentWorkerTask task, String errorSummary, int maximumAttempts) {
        boolean retry = task.attempt() < maximumAttempts;
        int updated = mapper.update(null, new LambdaUpdateWrapper<AgentWorkerTaskEntity>()
                .eq(AgentWorkerTaskEntity::getTaskId, task.taskId())
                .eq(AgentWorkerTaskEntity::getLeaseToken, task.leaseToken())
                .eq(AgentWorkerTaskEntity::getStatus, RUNNING)
                .set(AgentWorkerTaskEntity::getStatus, retry ? QUEUED : FAILED)
                .set(AgentWorkerTaskEntity::getLeaseToken, null)
                .set(AgentWorkerTaskEntity::getLeaseExpiresAt, null)
                .set(AgentWorkerTaskEntity::getErrorSummary, safeError(errorSummary))
                .setSql(retry ? "dispatch_version = dispatch_version + 1" : "dispatch_version = dispatch_version"));
        return updated == 1 && retry ? toTask(mapper.selectById(task.taskId())) : null;
    }

    /** Returns true only after a lease owner has durably made this task terminal. */
    public boolean isFailed(String taskId) {
        AgentWorkerTaskEntity entity = mapper.selectById(taskId);
        return entity != null && FAILED.equals(entity.getStatus());
    }

    /** Reclaims only abandoned leases; live Workers keep their running task untouched. */
    public List<AgentWorkerTask> reclaimExpired() {
        List<AgentWorkerTaskEntity> expired = mapper.selectList(new LambdaQueryWrapper<AgentWorkerTaskEntity>()
                .eq(AgentWorkerTaskEntity::getStatus, RUNNING)
                .lt(AgentWorkerTaskEntity::getLeaseExpiresAt, Instant.now()));
        return expired.stream().map(this::releaseIfLeaseStillMatches).filter(Objects::nonNull).toList();
    }

    private AgentWorkerTask releaseIfLeaseStillMatches(AgentWorkerTaskEntity entity) {
        int updated = mapper.update(null, new LambdaUpdateWrapper<AgentWorkerTaskEntity>()
                .eq(AgentWorkerTaskEntity::getTaskId, entity.getTaskId())
                .eq(AgentWorkerTaskEntity::getStatus, RUNNING)
                .eq(AgentWorkerTaskEntity::getLeaseToken, entity.getLeaseToken())
                .set(AgentWorkerTaskEntity::getStatus, QUEUED)
                .set(AgentWorkerTaskEntity::getWorkerId, null)
                .set(AgentWorkerTaskEntity::getLeaseToken, null)
                .set(AgentWorkerTaskEntity::getLeaseExpiresAt, null)
                .setSql("dispatch_version = dispatch_version + 1"));
        return updated == 1 ? toTask(mapper.selectById(entity.getTaskId())) : null;
    }

    private static String safeError(String value) {
        if (value == null || value.isBlank()) {
            return "Worker task failed";
        }
        return value.substring(0, Math.min(512, value.length()));
    }

    private static AgentWorkerTask toTask(AgentWorkerTaskEntity entity) {
        return new AgentWorkerTask(
                entity.getTaskId(), entity.getWorkflowId(), entity.getTenantId(), entity.getAgentCode(),
                entity.getStageCode(), entity.getStatus(), entity.getAttempt(), entity.getDispatchVersion(), entity.getLeaseToken(),
                entity.getLeaseExpiresAt(), entity.getWorkerId(), entity.getRequestJson(), entity.getErrorSummary(),
                entity.getCreatedAt(), entity.getUpdatedAt());
    }
}
