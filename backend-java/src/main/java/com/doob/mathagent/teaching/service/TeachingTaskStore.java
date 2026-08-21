package com.doob.mathagent.teaching.service;

import com.doob.mathagent.teaching.mq.LectureTaskLease;
import com.doob.mathagent.teaching.mq.LectureTaskLeaseStore;
import com.doob.mathagent.teaching.vo.TeachingTaskResponse;
import java.util.List;
import java.util.Optional;

/**
 * 教学任务存储端口；后续可替换为 MySQL/Redis 持久化实现。
 */
public interface TeachingTaskStore {

    /**
     * 按幂等 key 查询已存在任务，避免前端重试造成重复执行。
     */
    Optional<TeachingTaskResponse> findByIdempotencyKey(String idempotencyKey);

    /**
     * 按 taskId 和 ownerKey 查询任务，保证用户和公开/私有资源隔离。
     */
    Optional<TeachingTaskResponse> findByTaskIdAndOwnerKey(String taskId, String ownerKey);

    /** Internal Worker lookup; access control remains at HTTP boundaries, while Workers execute only durable task IDs. */
    default Optional<TeachingTaskResponse> findByTaskId(String taskId) {
        return Optional.empty();
    }

    /**
     * Lists recent teaching tasks owned by the current backend session subject.
     */
    List<TeachingTaskResponse> listRecentByOwnerKey(String ownerKey, int limit);

    /**
     * Lists recent tasks in one tenant for an authenticated administrator. Owner filters must not be applied.
     */
    default List<TeachingTaskResponse> listRecentByTenant(String tenantId, int limit) {
        return List.of();
    }

    /**
     * 保存任务结果及其归属关系。
     */
    TeachingTaskResponse save(String ownerKey, String idempotencyKey, TeachingTaskResponse task);

    /**
     * Persists the visible RUNNING snapshot and makes a terminal worker row eligible for a fresh manual attempt.
     * In-memory stores have no independent lease columns, so their normal save operation is sufficient.
     */
    default TeachingTaskResponse prepareForResume(
            String ownerKey,
            String idempotencyKey,
            TeachingTaskResponse runningTask) {
        return save(ownerKey, idempotencyKey, runningTask);
    }

    default TeachingTaskResponse createIfAbsent(String ownerKey, String idempotencyKey, TeachingTaskResponse task) {
        return save(ownerKey, idempotencyKey, task);
    }

    /**
     * 仅异步 Worker 使用的围栏写入。默认实现保留内存测试与同步流程的既有行为；生产 MySQL 实现必须
     * 以任务、RUNNING 状态和租约令牌为同一条更新条件。
     */
    default boolean saveOwnedRunning(LectureTaskLease lease, TeachingTaskResponse task) {
        return false;
    }

    /** 当前 Worker 在调用外部 Writer 前直接确认其持久化租约仍然有效。 */
    default boolean ownsLease(LectureTaskLease lease) {
        return false;
    }

    /** 以同一围栏 CAS 写入最终响应并完成任务，防止陈旧 Worker 伪造完成快照。 */
    default boolean completeOwned(LectureTaskLease lease, TeachingTaskResponse task) {
        return false;
    }

    /** 以同一围栏 CAS 写入失败/重试快照和执行状态。 */
    default LectureTaskLeaseStore.FailureOutcome failOwned(
            LectureTaskLease lease,
            TeachingTaskResponse task,
            String error,
            int maximumAttempts) {
        return LectureTaskLeaseStore.FailureOutcome.LEASE_LOST;
    }
}
