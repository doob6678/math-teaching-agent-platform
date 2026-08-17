package com.doob.mathagent.teaching.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.doob.mathagent.teaching.entity.TeachingTaskEntity;
import com.doob.mathagent.teaching.mapper.TeachingTaskMapper;
import com.doob.mathagent.teaching.vo.TeachingTaskResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Repository;

/**
 * MySQL-backed teaching task store. This is the production path; tasks must survive process restarts.
 */
@Repository
@ConditionalOnProperty(prefix = "math-agent.database", name = "enabled", havingValue = "true")
public class MyBatisTeachingTaskStore implements TeachingTaskStore {

    private final TeachingTaskMapper mapper;
    private final ObjectMapper objectMapper;

    public MyBatisTeachingTaskStore(TeachingTaskMapper mapper, ObjectMapper objectMapper) {
        this.mapper = mapper;
        this.objectMapper = objectMapper;
    }

    @Override
    public Optional<TeachingTaskResponse> findByIdempotencyKey(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return Optional.empty();
        }
        TeachingTaskEntity entity = mapper.selectOne(new LambdaQueryWrapper<TeachingTaskEntity>()
                .eq(TeachingTaskEntity::getIdempotencyKey, idempotencyKey.strip()));
        return Optional.ofNullable(entity).map(this::readResponse);
    }

    @Override
    public Optional<TeachingTaskResponse> findByTaskIdAndOwnerKey(String taskId, String ownerKey) {
        if (taskId == null || taskId.isBlank() || ownerKey == null || ownerKey.isBlank()) {
            return Optional.empty();
        }
        TeachingTaskEntity entity = mapper.selectById(taskId.strip());
        if (entity == null || !ownerKey.strip().equals(entity.getOwnerKey())) {
            return Optional.empty();
        }
        return Optional.of(readResponse(entity));
    }

    @Override
    public Optional<TeachingTaskResponse> findByTaskId(String taskId) {
        return taskId == null || taskId.isBlank() ? Optional.empty() : Optional.ofNullable(mapper.selectById(taskId.strip())).map(this::readResponse);
    }

    @Override
    public List<TeachingTaskResponse> listRecentByOwnerKey(String ownerKey, int limit) {
        if (ownerKey == null || ownerKey.isBlank()) {
            return List.of();
        }
        int safeLimit = Math.max(1, Math.min(50, limit));
        return mapper.selectPage(Page.of(1, safeLimit), new LambdaQueryWrapper<TeachingTaskEntity>()
                .eq(TeachingTaskEntity::getOwnerKey, ownerKey.strip())
                .orderByDesc(TeachingTaskEntity::getUpdatedAt))
                .getRecords()
                .stream()
                .map(this::readResponse)
                .toList();
    }

    @Override
    public List<TeachingTaskResponse> listRecentByTenant(String tenantId, int limit) {
        if (tenantId == null || tenantId.isBlank()) {
            return List.of();
        }
        int safeLimit = Math.max(1, Math.min(50, limit));
        return mapper.selectPage(Page.of(1, safeLimit), new LambdaQueryWrapper<TeachingTaskEntity>()
                .eq(TeachingTaskEntity::getTenantId, tenantId.strip())
                .orderByDesc(TeachingTaskEntity::getUpdatedAt))
                .getRecords()
                .stream()
                .map(this::readResponse)
                .toList();
    }

    @Override
    public TeachingTaskResponse createIfAbsent(String ownerKey, String idempotencyKey, TeachingTaskResponse task) {
        TeachingTaskEntity entity = toEntity(ownerKey, idempotencyKey, task);
        try {
            mapper.insert(entity);
            return task;
        } catch (DuplicateKeyException exception) {
            return findByIdempotencyKey(idempotencyKey).orElseThrow(() -> exception);
        }
    }

    @Override
    public TeachingTaskResponse save(String ownerKey, String idempotencyKey, TeachingTaskResponse task) {
        TeachingTaskEntity entity = toEntity(ownerKey, idempotencyKey, task);
        TeachingTaskEntity existing = mapper.selectById(task.taskId());
        if (existing == null) {
            mapper.insert(entity);
        } else {
            // Workflow snapshots change visible DAG progress, while the Worker CAS state machine owns lease/retry
            // columns. Preserving those values prevents a progress checkpoint from accidentally stealing a lease.
            entity.setStatus(existing.getStatus());
            entity.setRetryCount(existing.getRetryCount());
            entity.setLeaseOwner(existing.getLeaseOwner());
            entity.setLeaseToken(existing.getLeaseToken());
            entity.setLeaseExpireAt(existing.getLeaseExpireAt());
            entity.setCurrentStage(currentStage(task, existing.getCurrentStage()));
            entity.setLastError(existing.getLastError());
            entity.setStartedAt(existing.getStartedAt());
            entity.setFinishedAt(existing.getFinishedAt());
            entity.setCreatedAt(existing.getCreatedAt());
            mapper.updateById(entity);
        }
        return task;
    }

    /**
     * Resets worker-owned execution columns only for an explicit manual resume. Normal progress saves deliberately
     * preserve these columns so an in-flight checkpoint cannot invalidate its own lease.
     */
    @Override
    public TeachingTaskResponse prepareForResume(
            String ownerKey,
            String idempotencyKey,
            TeachingTaskResponse runningTask) {
        int updated = mapper.prepareLectureTaskForResume(
                runningTask.taskId(), ownerKey.strip(), writeResponse(runningTask), Instant.now());
        if (updated != 1) {
            throw new IllegalStateException("Teaching task could not be prepared for resume");
        }
        return runningTask;
    }

    private TeachingTaskEntity toEntity(String ownerKey, String idempotencyKey, TeachingTaskResponse task) {
        TeachingTaskEntity entity = new TeachingTaskEntity();
        entity.setTaskId(task.taskId());
        entity.setTenantId(task.tenantId());
        entity.setSubjectType(task.subjectType());
        entity.setSubjectId(task.subjectId());
        entity.setOwnerKey(ownerKey);
        entity.setIdempotencyKey(idempotencyKey);
        entity.setClientRequestId(task.clientRequestId());
        entity.setStatus(task.status().name());
        entity.setResponseJson(writeResponse(task));
        Instant now = Instant.now();
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        return entity;
    }

    /** Mirrors the visible DAG checkpoint into a queryable task-table column for stuck-task operations. */
    private static String currentStage(TeachingTaskResponse task, String fallback) {
        return task.nodes().stream()
                .filter(node -> "running".equalsIgnoreCase(node.status()))
                .map(node -> node.code())
                .findFirst()
                .orElse(fallback);
    }

    private String writeResponse(TeachingTaskResponse task) {
        try {
            return objectMapper.writeValueAsString(task);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Teaching task response is not serializable", exception);
        }
    }

    private TeachingTaskResponse readResponse(TeachingTaskEntity entity) {
        try {
            return objectMapper.readValue(entity.getResponseJson(), TeachingTaskResponse.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Teaching task response JSON is corrupted: " + entity.getTaskId(), exception);
        }
    }
}
