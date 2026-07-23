package com.doob.mathagent.teaching.service;

import com.doob.mathagent.teaching.vo.TeachingTaskResponse;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 进程内教学任务存储：支撑本地开发阶段的任务恢复；生产阶段应替换为 MySQL 持久化存储。
 */
public class InMemoryTeachingTaskStore implements TeachingTaskStore {

    private final Map<String, TeachingTaskResponse> tasksById = new ConcurrentHashMap<>();
    private final Map<String, String> ownerKeysByTaskId = new ConcurrentHashMap<>();
    private final Map<String, String> taskIdsByIdempotencyKey = new ConcurrentHashMap<>();

    /**
     * 按幂等 key 查找任务。
     */
    @Override
    public Optional<TeachingTaskResponse> findByIdempotencyKey(String idempotencyKey) {
        String taskId = taskIdsByIdempotencyKey.get(idempotencyKey);
        return taskId == null ? Optional.empty() : Optional.ofNullable(tasksById.get(taskId));
    }

    /**
     * 按任务 ID 和归属 key 查找任务，防止其他用户读取私有任务。
     */
    @Override
    public Optional<TeachingTaskResponse> findByTaskIdAndOwnerKey(String taskId, String ownerKey) {
        if (!ownerKey.equals(ownerKeysByTaskId.get(taskId))) {
            return Optional.empty();
        }
        return Optional.ofNullable(tasksById.get(taskId));
    }

    @Override
    public Optional<TeachingTaskResponse> findByTaskId(String taskId) {
        return Optional.ofNullable(tasksById.get(taskId));
    }

    @Override
    public List<TeachingTaskResponse> listRecentByOwnerKey(String ownerKey, int limit) {
        int safeLimit = Math.max(1, Math.min(50, limit));
        return tasksById.values().stream()
                .filter(task -> ownerKey.equals(ownerKeysByTaskId.get(task.taskId())))
                .sorted(Comparator.comparing(TeachingTaskResponse::taskId).reversed())
                .limit(safeLimit)
                .toList();
    }

    /**
     * 保存任务及索引。
     */
    @Override
    public TeachingTaskResponse save(String ownerKey, String idempotencyKey, TeachingTaskResponse task) {
        tasksById.put(task.taskId(), task);
        ownerKeysByTaskId.put(task.taskId(), ownerKey);
        taskIdsByIdempotencyKey.put(idempotencyKey, task.taskId());
        return task;
    }
}
