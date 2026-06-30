package com.doob.mathagent.teacher.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.doob.mathagent.teacher.entity.TeacherSourceDocumentEntity;
import com.doob.mathagent.teacher.mapper.TeacherSourceDocumentMapper;
import com.doob.mathagent.teacher.vo.TeacherResourceDocumentResponse;
import java.util.Collection;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

/**
 * MyBatis-Plus store for teacher-managed source documents.
 */
@Repository
@ConditionalOnProperty(prefix = "math-agent.database", name = "enabled", havingValue = "true")
public class MyBatisTeacherResourceStore implements TeacherResourceStore {

    private static final Collection<String> SHARED_SEARCH_SCOPES = List.of(
            "MATH_VIP",
            "PUBLIC_TEXTBOOK",
            "CLASS_AUTHORIZED");

    private final TeacherSourceDocumentMapper mapper;

    /**
     * Creates a MyBatis-backed teacher resource store.
     *
     * @param mapper source document mapper
     */
    public MyBatisTeacherResourceStore(TeacherSourceDocumentMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * Saves a resource document to source_document. Numeric ids update existing rows; non-numeric ids insert rows.
     *
     * @param document resource document
     * @return saved resource document
     */
    @Override
    public TeacherResourceDocumentResponse save(TeacherResourceDocumentResponse document) {
        TeacherSourceDocumentEntity entity = toEntity(document);
        if (entity.getId() == null) {
            mapper.insert(entity);
            if (entity.getId() == null) {
                return document;
            }
            return toResponse(entity);
        }
        mapper.updateById(entity);
        return toResponse(entity);
    }

    /**
     * Lists active resource documents visible to the viewer.
     *
     * @param tenantId tenant id
     * @param viewerRole current viewer role
     * @param viewerSubjectId current viewer subject id
     * @return visible active documents
     */
    @Override
    public List<TeacherResourceDocumentResponse> listVisible(String tenantId, String viewerRole, String viewerSubjectId) {
        LambdaQueryWrapper<TeacherSourceDocumentEntity> query = new LambdaQueryWrapper<TeacherSourceDocumentEntity>()
                .eq(TeacherSourceDocumentEntity::getTenantId, tenantId)
                .ne(TeacherSourceDocumentEntity::getSyncStatus, "archived")
                .orderByAsc(TeacherSourceDocumentEntity::getId);
        if (!"admin".equals(viewerRole)) {
            query.eq(TeacherSourceDocumentEntity::getCreatedBy, viewerSubjectId);
        }
        return mapper.selectList(query).stream()
                .map(MyBatisTeacherResourceStore::toResponse)
                .toList();
    }

    /**
     * Lists active documents whose parsed blocks can be searched by this viewer.
     *
     * @param tenantId tenant id
     * @param viewerRole current viewer role
     * @param viewerSubjectId current viewer subject id
     * @return searchable active documents
     */
    @Override
    public List<TeacherResourceDocumentResponse> listSearchable(
            String tenantId,
            String viewerRole,
            String viewerSubjectId) {
        if (!"teacher".equals(viewerRole) && !"admin".equals(viewerRole)) {
            return List.of();
        }
        LambdaQueryWrapper<TeacherSourceDocumentEntity> query = new LambdaQueryWrapper<TeacherSourceDocumentEntity>()
                .eq(TeacherSourceDocumentEntity::getTenantId, tenantId)
                .ne(TeacherSourceDocumentEntity::getSyncStatus, "archived")
                .orderByAsc(TeacherSourceDocumentEntity::getTitle)
                .orderByAsc(TeacherSourceDocumentEntity::getId);
        if ("teacher".equals(viewerRole)) {
            query.and(wrapper -> wrapper
                    .eq(TeacherSourceDocumentEntity::getCreatedBy, viewerSubjectId)
                    .or()
                    .in(TeacherSourceDocumentEntity::getPermissionScope, SHARED_SEARCH_SCOPES));
        }
        return mapper.selectList(query).stream()
                .map(MyBatisTeacherResourceStore::toResponse)
                .toList();
    }

    /**
     * Finds a source document by tenant and id.
     *
     * @param tenantId tenant id
     * @param documentId document id
     * @return resource response or null
     */
    @Override
    public TeacherResourceDocumentResponse find(String tenantId, String documentId) {
        Long id = parseId(documentId);
        if (id == null) {
            return null;
        }
        TeacherSourceDocumentEntity entity = mapper.selectById(id);
        if (entity == null || !tenantId.equals(entity.getTenantId())) {
            return null;
        }
        return toResponse(entity);
    }

    /**
     * Converts response data to a source_document entity.
     *
     * @param document resource response
     * @return source document entity
     */
    private static TeacherSourceDocumentEntity toEntity(TeacherResourceDocumentResponse document) {
        TeacherSourceDocumentEntity entity = new TeacherSourceDocumentEntity();
        entity.setId(parseId(document.documentId()));
        entity.setTenantId(document.tenantId());
        entity.setSourceType(document.sourceType());
        entity.setTitle(document.title());
        entity.setOriginalUrl(document.originalUrl());
        entity.setLocalPath(document.localPath());
        entity.setPermissionScope(document.permissionScope());
        entity.setCreatedBy(document.ownerSubjectId());
        entity.setSyncStatus(document.syncStatus());
        entity.setParseStatus(document.parseStatus());
        entity.setEmbeddingStatus(document.embeddingStatus());
        entity.setMetadataJson(indexMetadata(document.indexStatus()));
        return entity;
    }

    /**
     * Converts a source_document entity to a teacher resource response.
     *
     * @param entity source document entity
     * @return resource response
     */
    private static TeacherResourceDocumentResponse toResponse(TeacherSourceDocumentEntity entity) {
        return new TeacherResourceDocumentResponse(
                entity.getId() == null ? "" : String.valueOf(entity.getId()),
                entity.getTenantId(),
                entity.getCreatedBy(),
                entity.getSourceType(),
                entity.getTitle(),
                entity.getOriginalUrl(),
                entity.getLocalPath(),
                entity.getPermissionScope(),
                entity.getSyncStatus(),
                entity.getParseStatus(),
                entity.getEmbeddingStatus(),
                indexStatus(entity.getMetadataJson()),
                List.of());
    }

    /**
     * Builds compact metadata JSON containing index status.
     *
     * @param indexStatus index status
     * @return metadata JSON
     */
    private static String indexMetadata(String indexStatus) {
        String value = indexStatus == null || indexStatus.isBlank() ? "waiting_rebuild" : indexStatus.strip();
        return "{\"indexStatus\":\"" + escapeJson(value) + "\"}";
    }

    /**
     * Extracts index status from compact metadata JSON.
     *
     * @param metadataJson metadata JSON
     * @return index status
     */
    private static String indexStatus(String metadataJson) {
        if (metadataJson == null || metadataJson.isBlank()) {
            return "waiting_rebuild";
        }
        int keyIndex = metadataJson.indexOf("\"indexStatus\"");
        if (keyIndex < 0) {
            return "waiting_rebuild";
        }
        int colonIndex = metadataJson.indexOf(':', keyIndex);
        int firstQuote = metadataJson.indexOf('"', colonIndex + 1);
        int secondQuote = metadataJson.indexOf('"', firstQuote + 1);
        if (colonIndex < 0 || firstQuote < 0 || secondQuote < 0) {
            return "waiting_rebuild";
        }
        return metadataJson.substring(firstQuote + 1, secondQuote);
    }

    /**
     * Parses a numeric source document id.
     *
     * @param documentId document id
     * @return numeric id or null
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
     * Escapes a JSON string value.
     *
     * @param value raw value
     * @return escaped value
     */
    private static String escapeJson(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
