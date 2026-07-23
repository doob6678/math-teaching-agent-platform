package com.doob.mathagent.teaching.service;

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
     * 保存任务结果及其归属关系。
     */
    TeachingTaskResponse save(String ownerKey, String idempotencyKey, TeachingTaskResponse task);

    default TeachingTaskResponse createIfAbsent(String ownerKey, String idempotencyKey, TeachingTaskResponse task) {
        return save(ownerKey, idempotencyKey, task);
    }
}
