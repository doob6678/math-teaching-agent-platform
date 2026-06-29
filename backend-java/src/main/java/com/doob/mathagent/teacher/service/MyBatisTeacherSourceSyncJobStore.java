package com.doob.mathagent.teacher.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.doob.mathagent.teacher.entity.TeacherSourceSyncJobEntity;
import com.doob.mathagent.teacher.mapper.TeacherSourceSyncJobMapper;
import com.doob.mathagent.teacher.vo.TeacherSourceSyncJobResponse;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

/**
 * MyBatis-backed source sync job store.
 */
@Repository
@ConditionalOnProperty(prefix = "math-agent.database", name = "enabled", havingValue = "true")
public class MyBatisTeacherSourceSyncJobStore implements TeacherSourceSyncJobStore {

    private final TeacherSourceSyncJobMapper mapper;

    /**
     * Creates a MyBatis source sync job store.
     *
     * @param mapper sync job mapper
     */
    public MyBatisTeacherSourceSyncJobStore(TeacherSourceSyncJobMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public TeacherSourceSyncJobResponse save(TeacherSourceSyncJobResponse job) {
        TeacherSourceSyncJobEntity entity = toEntity(job);
        mapper.insert(entity);
        return toResponse(entity);
    }

    @Override
    public List<TeacherSourceSyncJobResponse> listByDocument(String tenantId, String documentId) {
        Long sourceDocumentId = parseId(documentId);
        if (sourceDocumentId == null) {
            return List.of();
        }
        LambdaQueryWrapper<TeacherSourceSyncJobEntity> query = new LambdaQueryWrapper<TeacherSourceSyncJobEntity>()
                .eq(TeacherSourceSyncJobEntity::getTenantId, tenantId)
                .eq(TeacherSourceSyncJobEntity::getSourceDocumentId, sourceDocumentId)
                .orderByDesc(TeacherSourceSyncJobEntity::getCreatedAt)
                .orderByDesc(TeacherSourceSyncJobEntity::getId);
        return mapper.selectList(query).stream()
                .map(MyBatisTeacherSourceSyncJobStore::toResponse)
                .toList();
    }

    private static TeacherSourceSyncJobEntity toEntity(TeacherSourceSyncJobResponse job) {
        TeacherSourceSyncJobEntity entity = new TeacherSourceSyncJobEntity();
        entity.setJobId(job.jobId());
        entity.setSourceDocumentId(parseId(job.documentId()));
        entity.setTenantId(job.tenantId());
        entity.setSourceType(job.sourceType());
        entity.setOperation(job.operation());
        entity.setStatus(job.status());
        entity.setPhase(job.phase());
        entity.setAttempt(job.attempt());
        entity.setCreatedBy(job.createdBy());
        entity.setStagingPath(job.stagingPath());
        entity.setMessage(job.message());
        entity.setMetadataJson("{}");
        entity.setCreatedAt(parseLocalDateTime(job.createdAt()));
        entity.setUpdatedAt(parseLocalDateTime(job.updatedAt()));
        return entity;
    }

    private static TeacherSourceSyncJobResponse toResponse(TeacherSourceSyncJobEntity entity) {
        return new TeacherSourceSyncJobResponse(
                entity.getJobId(),
                entity.getSourceDocumentId() == null ? "" : String.valueOf(entity.getSourceDocumentId()),
                entity.getTenantId(),
                entity.getSourceType(),
                entity.getOperation(),
                entity.getStatus(),
                entity.getPhase(),
                entity.getAttempt() == null ? 0 : entity.getAttempt(),
                entity.getCreatedBy(),
                entity.getStagingPath(),
                entity.getMessage(),
                entity.getCreatedAt() == null ? "" : entity.getCreatedAt().toString(),
                entity.getUpdatedAt() == null ? "" : entity.getUpdatedAt().toString());
    }

    private static Long parseId(String documentId) {
        if (documentId == null || documentId.isBlank()) {
            return null;
        }
        try {
            return Long.valueOf(documentId);
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private static LocalDateTime parseLocalDateTime(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.strip();
        if (normalized.endsWith("Z")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        int dotIndex = normalized.indexOf('.');
        if (dotIndex >= 0) {
            normalized = normalized.substring(0, Math.min(dotIndex + 7, normalized.length()));
        }
        return LocalDateTime.parse(normalized);
    }
}
