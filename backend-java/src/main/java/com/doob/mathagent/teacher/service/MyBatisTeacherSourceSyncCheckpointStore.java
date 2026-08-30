package com.doob.mathagent.teacher.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.doob.mathagent.teacher.entity.TeacherSourceSyncCheckpointEntity;
import com.doob.mathagent.teacher.mapper.TeacherSourceSyncCheckpointMapper;
import com.doob.mathagent.teacher.vo.TeacherSourceSyncCheckpointResponse;
import java.time.LocalDateTime;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

/**
 * MyBatis-backed checkpoint store for resumable source synchronization.
 */
@Repository
@ConditionalOnProperty(prefix = "math-agent.database", name = "enabled", havingValue = "true")
public class MyBatisTeacherSourceSyncCheckpointStore implements TeacherSourceSyncCheckpointStore {

    private final TeacherSourceSyncCheckpointMapper mapper;

    /**
     * Creates a MyBatis checkpoint store.
     *
     * @param mapper checkpoint mapper
     */
    public MyBatisTeacherSourceSyncCheckpointStore(TeacherSourceSyncCheckpointMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * Inserts a checkpoint on first save and updates it on later saves for the same tenant/job.
     */
    @Override
    public TeacherSourceSyncCheckpointResponse save(TeacherSourceSyncCheckpointResponse checkpoint) {
        TeacherSourceSyncCheckpointEntity entity = toEntity(checkpoint);
        TeacherSourceSyncCheckpointEntity existing = mapper.selectOne(
                new LambdaQueryWrapper<TeacherSourceSyncCheckpointEntity>()
                        .eq(TeacherSourceSyncCheckpointEntity::getTenantId, checkpoint.tenantId())
                        .eq(TeacherSourceSyncCheckpointEntity::getJobId, checkpoint.jobId()));
        if (existing == null) {
            mapper.insert(entity);
        } else {
            entity.setId(existing.getId());
            mapper.updateById(entity);
        }
        return toResponse(entity);
    }

    /**
     * Loads a checkpoint by tenant and job id.
     */
    @Override
    public Optional<TeacherSourceSyncCheckpointResponse> findByJobId(String tenantId, String jobId) {
        TeacherSourceSyncCheckpointEntity entity = mapper.selectOne(
                new LambdaQueryWrapper<TeacherSourceSyncCheckpointEntity>()
                        .eq(TeacherSourceSyncCheckpointEntity::getTenantId, tenantId)
                        .eq(TeacherSourceSyncCheckpointEntity::getJobId, jobId));
        return Optional.ofNullable(entity).map(MyBatisTeacherSourceSyncCheckpointStore::toResponse);
    }

    /**
     * Converts an API response record to a MyBatis entity.
     */
    private static TeacherSourceSyncCheckpointEntity toEntity(TeacherSourceSyncCheckpointResponse checkpoint) {
        TeacherSourceSyncCheckpointEntity entity = new TeacherSourceSyncCheckpointEntity();
        entity.setJobId(checkpoint.jobId());
        entity.setTenantId(checkpoint.tenantId());
        entity.setSourceDocumentId(parseId(checkpoint.documentId()));
        entity.setRootToken(checkpoint.rootToken());
        entity.setCurrentFolderToken(checkpoint.currentFolderToken());
        entity.setCurrentPath(checkpoint.currentPath());
        entity.setPageToken(checkpoint.pageToken());
        entity.setVisitedFolderTokensJson(jsonOrEmptyArray(checkpoint.visitedFolderTokensJson()));
        entity.setDownloadedItemsJson(jsonOrEmptyArray(checkpoint.downloadedItemsJson()));
        entity.setFailedItemsJson(jsonOrEmptyArray(checkpoint.failedItemsJson()));
        entity.setCursorVersion(Math.max(1, checkpoint.cursorVersion()));
        entity.setUpdatedAt(parseLocalDateTime(checkpoint.updatedAt()));
        return entity;
    }

    /**
     * Converts a MyBatis entity to an API response record.
     */
    private static TeacherSourceSyncCheckpointResponse toResponse(TeacherSourceSyncCheckpointEntity entity) {
        return new TeacherSourceSyncCheckpointResponse(
                entity.getJobId(),
                entity.getTenantId(),
                entity.getSourceDocumentId() == null ? "" : String.valueOf(entity.getSourceDocumentId()),
                entity.getRootToken(),
                entity.getCurrentFolderToken(),
                entity.getCurrentPath(),
                entity.getPageToken(),
                jsonOrEmptyArray(entity.getVisitedFolderTokensJson()),
                jsonOrEmptyArray(entity.getDownloadedItemsJson()),
                jsonOrEmptyArray(entity.getFailedItemsJson()),
                entity.getCursorVersion() == null ? 1 : entity.getCursorVersion(),
                entity.getUpdatedAt() == null ? "" : entity.getUpdatedAt().toString());
    }

    /**
     * Parses a numeric source document id.
     */
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

    /**
     * Parses a backend timestamp into a database timestamp.
     */
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

    /**
     * Defaults blank JSON array fields to an empty array string.
     */
    private static String jsonOrEmptyArray(String value) {
        return value == null || value.isBlank() ? "[]" : value;
    }
}
