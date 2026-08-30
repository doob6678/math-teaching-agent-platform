package com.doob.mathagent.teacher.sync;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.doob.mathagent.teacher.entity.TeacherSourceSyncJobEntity;
import com.doob.mathagent.teacher.mapper.TeacherSourceSyncJobMapper;
import com.doob.mathagent.teacher.vo.TeacherSourceSyncJobResponse;
import com.doob.mathagent.teacher.vo.TeacherSourceSyncFailureResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

/**
 * MyBatis-backed source sync job store.
 */
@Repository
@ConditionalOnProperty(prefix = "math-agent.database", name = "enabled", havingValue = "true")
public class MyBatisTeacherSourceSyncJobStore implements TeacherSourceSyncJobStore {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

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
        TeacherSourceSyncJobEntity existing = mapper.selectOne(
                new LambdaQueryWrapper<TeacherSourceSyncJobEntity>()
                        .eq(TeacherSourceSyncJobEntity::getJobId, job.jobId()));
        if (existing == null) {
            mapper.insert(entity);
        } else {
            entity.setId(existing.getId());
            mapper.updateById(entity);
        }
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

    /**
     * Applies the history page in MySQL so a resource card never transfers every past sync run to the browser.
     */
    @Override
    public List<TeacherSourceSyncJobResponse> listPageByDocument(
            String tenantId, String documentId, int pageNumber, int pageSize) {
        Long sourceDocumentId = parseId(documentId);
        if (sourceDocumentId == null) {
            return List.of();
        }
        LambdaQueryWrapper<TeacherSourceSyncJobEntity> query = new LambdaQueryWrapper<TeacherSourceSyncJobEntity>()
                .eq(TeacherSourceSyncJobEntity::getTenantId, tenantId)
                .eq(TeacherSourceSyncJobEntity::getSourceDocumentId, sourceDocumentId)
                .orderByDesc(TeacherSourceSyncJobEntity::getCreatedAt)
                .orderByDesc(TeacherSourceSyncJobEntity::getId);
        Page<TeacherSourceSyncJobEntity> page = Page.of(pageNumber, pageSize);
        return mapper.selectPage(page, query).getRecords().stream()
                .map(MyBatisTeacherSourceSyncJobStore::toResponse)
                .toList();
    }

    @Override
    public TeacherSourceSyncJobResponse findActiveByDocument(String tenantId, String documentId) {
        Long sourceDocumentId = parseId(documentId);
        if (sourceDocumentId == null) {
            return null;
        }
        List<TeacherSourceSyncJobEntity> entities = mapper.selectPage(Page.of(1, 1), new LambdaQueryWrapper<TeacherSourceSyncJobEntity>()
                .eq(TeacherSourceSyncJobEntity::getTenantId, tenantId)
                .eq(TeacherSourceSyncJobEntity::getSourceDocumentId, sourceDocumentId)
                // Authorization recovery is a durable pause, not a terminal failure.  Treat it as active so a
                // scheduler tick or duplicate browser click cannot create a competing traversal from the root.
                .in(TeacherSourceSyncJobEntity::getStatus, List.of("queued", "running", "paused", "AUTH_REQUIRED"))
                .orderByDesc(TeacherSourceSyncJobEntity::getCreatedAt))
                .getRecords();
        TeacherSourceSyncJobEntity entity = entities.stream().findFirst().orElse(null);
        return entity == null ? null : toResponse(entity);
    }

    @Override
    public int terminateActiveByDocument(String tenantId, String documentId, Instant now) {
        Long sourceDocumentId = parseId(documentId);
        if (sourceDocumentId == null) {
            return 0;
        }
        return mapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<TeacherSourceSyncJobEntity>()
                .eq(TeacherSourceSyncJobEntity::getTenantId, tenantId)
                .eq(TeacherSourceSyncJobEntity::getSourceDocumentId, sourceDocumentId)
                .in(TeacherSourceSyncJobEntity::getStatus, List.of("queued", "running", "paused", "AUTH_REQUIRED"))
                .set(TeacherSourceSyncJobEntity::getStatus, "cancelled")
                .set(TeacherSourceSyncJobEntity::getPhase, "resource_archived")
                .set(TeacherSourceSyncJobEntity::getMessage, "Source document archived; sync job cancelled")
                .set(TeacherSourceSyncJobEntity::getUpdatedAt, LocalDateTime.ofInstant(now, ZoneOffset.UTC)));
    }

    @Override
    public int recoverStaleRunningJobs(Instant now, long staleAfterSeconds) {
        LocalDateTime cutoff = LocalDateTime.ofInstant(now.minusSeconds(Math.max(1L, staleAfterSeconds)), ZoneOffset.UTC);
        return mapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<TeacherSourceSyncJobEntity>()
                .eq(TeacherSourceSyncJobEntity::getStatus, "running")
                .lt(TeacherSourceSyncJobEntity::getUpdatedAt, cutoff)
                .set(TeacherSourceSyncJobEntity::getStatus, "paused")
                .set(TeacherSourceSyncJobEntity::getPhase, "recovery_pending")
                .set(TeacherSourceSyncJobEntity::getMessage,
                        "Worker lease expired; scheduler will resume from the durable Feishu checkpoint.")
                .set(TeacherSourceSyncJobEntity::getUpdatedAt, LocalDateTime.ofInstant(now, ZoneOffset.UTC)));
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
        entity.setMetadataJson(failureJson(job.failure()));
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
                entity.getUpdatedAt() == null ? "" : entity.getUpdatedAt().toString(),
                failureFromJson(entity.getMetadataJson()));
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

    private static String failureJson(TeacherSourceSyncFailureResponse failure) {
        TeacherSourceSyncFailureResponse safe = failure == null ? TeacherSourceSyncFailureResponse.none() : failure;
        try {
            return OBJECT_MAPPER.writeValueAsString(safe);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Failed to persist source sync failure details", exception);
        }
    }

    private static TeacherSourceSyncFailureResponse failureFromJson(String json) {
        if (json == null || json.isBlank()) {
            return TeacherSourceSyncFailureResponse.none();
        }
        try {
            JsonNode node = OBJECT_MAPPER.readTree(json);
            List<String> scopes = new java.util.ArrayList<>();
            node.path("requiredScopes").forEach(value -> {
                if (value.isTextual() && !value.asText().isBlank()) {
                    scopes.add(value.asText());
                }
            });
            return new TeacherSourceSyncFailureResponse(
                    blankToNull(node.path("providerCode").asText("")),
                    node.path("retryable").asBoolean(false),
                    scopes,
                    blankToNull(node.path("authorizationUrl").asText("")));
        } catch (JsonProcessingException exception) {
            return TeacherSourceSyncFailureResponse.none();
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
