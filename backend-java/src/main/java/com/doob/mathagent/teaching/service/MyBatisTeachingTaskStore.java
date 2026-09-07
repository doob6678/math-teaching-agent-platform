package com.doob.mathagent.teaching.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.doob.mathagent.teaching.TeachingEvidence;
import com.doob.mathagent.teaching.entity.TeachingTaskEntity;
import com.doob.mathagent.teaching.mapper.TeachingTaskMapper;
import com.doob.mathagent.teaching.mq.LectureTaskLease;
import com.doob.mathagent.teaching.mq.LectureTaskLeaseStore;
import com.doob.mathagent.teaching.vo.TeachingTaskResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
            TeachingTaskResponse carried = carryDurableImageBindings(existing, task);
            if (carried != task) {
                entity.setResponseJson(writeResponse(carried));
            }
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
        TeachingTaskResponse carried = carryDurableImageBindings(mapper.selectById(runningTask.taskId()), runningTask);
        int updated = mapper.prepareLectureTaskForResume(
                carried.taskId(), ownerKey.strip(), writeResponse(carried), Instant.now());
        if (updated != 1) {
            throw new IllegalStateException("Teaching task could not be prepared for resume");
        }
        return runningTask;
    }

    @Override
    public boolean saveOwnedRunning(LectureTaskLease lease, TeachingTaskResponse task) {
        TeachingTaskResponse carried = carryDurableImageBindings(mapper.selectById(lease.taskId()), task);
        return mapper.saveOwnedRunningLectureTask(
                lease.taskId(), lease.token(), writeResponse(carried), currentStage(carried, null), Instant.now()) == 1;
    }

    @Override
    public boolean ownsLease(LectureTaskLease lease) {
        return mapper.countOwnedRunningLectureTask(lease.taskId(), lease.token()) == 1;
    }

    @Override
    public boolean completeOwned(LectureTaskLease lease, TeachingTaskResponse task) {
        Instant now = Instant.now();
        TeachingTaskResponse carried = carryDurableImageBindings(mapper.selectById(lease.taskId()), task);
        return mapper.completeOwnedLectureTask(
                lease.taskId(), lease.token(), writeResponse(carried), currentStage(carried, null), now) == 1;
    }

    @Override
    public LectureTaskLeaseStore.FailureOutcome failOwned(
            LectureTaskLease lease, TeachingTaskResponse task, String error, int maximumAttempts) {
        boolean retry = lease.retryCount() < maximumAttempts;
        Instant now = Instant.now();
        TeachingTaskResponse carried = carryDurableImageBindings(mapper.selectById(lease.taskId()), task);
        int changed = mapper.failOwnedLectureTask(
                lease.taskId(), lease.token(), writeResponse(carried), retry ? "RETRYING" : "FAILED", safeError(error),
                retry ? null : now, now);
        if (changed != 1) {
            return LectureTaskLeaseStore.FailureOutcome.LEASE_LOST;
        }
        return retry ? LectureTaskLeaseStore.FailureOutcome.RETRYING
                : LectureTaskLeaseStore.FailureOutcome.TERMINAL_FAILURE;
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

    private static String safeError(String message) {
        return message == null || message.isBlank() ? "Lecture task failed"
                : message.substring(0, Math.min(512, message.length()));
    }

    private TeachingTaskResponse readResponse(TeachingTaskEntity entity) {
        try {
            return objectMapper.readValue(entity.getResponseJson(), TeachingTaskResponse.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Teaching task response JSON is corrupted: " + entity.getTaskId(), exception);
        }
    }

    /**
     * Broker canonical 精读会把题图授权绑定（imageRefs）、教师资源搜索会把资产身份（assetIds）写回持久化
     * 账本，而编排器的进度快照由运行开始时的内存 evidence 构建、不含这些后写增量；不结转的话之后的任何一次
     * 保存都会抹掉它们，导出端反查失败即 fail-closed 丢图（2026-09-07 椭圆任务 LaTeX 有图行但 PDF 无图事故）。
     */
    private TeachingTaskResponse carryDurableImageBindings(TeachingTaskEntity existing, TeachingTaskResponse task) {
        if (existing == null || existing.getResponseJson() == null || existing.getResponseJson().isBlank()
                || task.evidence() == null || task.evidence().isEmpty()) {
            return task;
        }
        TeachingTaskResponse persisted;
        try {
            persisted = objectMapper.readValue(existing.getResponseJson(), TeachingTaskResponse.class);
        } catch (JsonProcessingException exception) {
            // 历史快照损坏不能阻塞本次保存；绑定结转尽力而为。
            return task;
        }
        Map<String, TeachingEvidence> durableBindings = new HashMap<>();
        for (TeachingEvidence row : persisted.evidence() == null
                ? List.<TeachingEvidence>of() : persisted.evidence()) {
            // 只有携带后写增量（绑定或资产身份）的行才值得结转；空行不建条目，避免无谓重建。
            if (!row.imageRefs().isEmpty() || !row.assetIds().isEmpty()) {
                durableBindings.putIfAbsent(evidenceIdentityKey(row), row);
            }
        }
        if (durableBindings.isEmpty()) {
            return task;
        }
        List<TeachingEvidence> merged = new ArrayList<>(task.evidence().size());
        boolean changed = false;
        for (TeachingEvidence row : task.evidence()) {
            TeachingEvidence durable = durableBindings.get(evidenceIdentityKey(row));
            if (durable == null
                    || (row.imageRefs().containsAll(durable.imageRefs()) && row.assetIds().containsAll(durable.assetIds()))) {
                merged.add(row);
                continue;
            }
            List<Map<String, String>> combinedRefs = new ArrayList<>(row.imageRefs());
            for (Map<String, String> ref : durable.imageRefs()) {
                if (!combinedRefs.contains(ref)) {
                    combinedRefs.add(ref);
                }
            }
            List<String> combinedAssets = new ArrayList<>(row.assetIds());
            for (String assetId : durable.assetIds()) {
                if (!combinedAssets.contains(assetId)) {
                    combinedAssets.add(assetId);
                }
            }
            merged.add(withCarriedBindings(row, combinedRefs, combinedAssets));
            changed = true;
        }
        return changed ? task.withEvidence(List.copyOf(merged)) : task;
    }

    /** 证据行身份：范围+文档+块+真题题号，与 broker 回写匹配键一致，防止跨文档串绑定。 */
    private static String evidenceIdentityKey(TeachingEvidence row) {
        return row.sourceScope() + '\0' + row.sourceDocumentId() + '\0' + row.chunkId()
                + '\0' + row.canonicalQuestionNumber();
    }

    private static TeachingEvidence withCarriedBindings(
            TeachingEvidence row, List<Map<String, String>> refs, List<String> assetIds) {
        return new TeachingEvidence(row.sourceScope(), row.sourceTitle(), row.chunkId(), row.pageNo(),
                row.snippet(), row.imagePath(), row.imageDescription(), row.sourceDocumentId(), row.sourceType(),
                row.sourceUrl(), row.sourcePath(), List.copyOf(assetIds), row.canonicalQuestionNumber(),
                List.copyOf(refs));
    }
}
