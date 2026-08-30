package com.doob.mathagent.teacher.document;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.doob.mathagent.teacher.document.entity.TeacherSourceDocumentEntity;
import com.doob.mathagent.teacher.document.mapper.TeacherSourceDocumentMapper;
import com.doob.mathagent.teacher.support.TeacherResourceSourceIdentity;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.ArrayList;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * MyBatis-Plus store for teacher-managed source documents.
 */
@Repository
@ConditionalOnProperty(prefix = "math-agent.database", name = "enabled", havingValue = "true")
public class MyBatisTeacherResourceStore implements TeacherResourceStore {

    private static final Logger LOGGER = LoggerFactory.getLogger(MyBatisTeacherResourceStore.class);
    private static final ObjectMapper METADATA_MAPPER = new ObjectMapper();

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
        if ("teacher".equals(viewerRole)) {
            query.and(wrapper -> wrapper.eq(TeacherSourceDocumentEntity::getCreatedBy, viewerSubjectId)
                    .or().in(TeacherSourceDocumentEntity::getPermissionScope,
                            TeacherResourceVisibilityPolicy.TEACHER_SHARED_SCOPES));
        } else if ("student".equals(viewerRole)) {
            query.in(TeacherSourceDocumentEntity::getPermissionScope,
                    TeacherResourceVisibilityPolicy.STUDENT_SHARED_SCOPES);
        } else if (!"admin".equals(viewerRole)) {
            return List.of();
        }
        return mapper.selectList(query).stream()
                .filter(entity -> !"FILE".equalsIgnoreCase(parseMetadata(entity.getMetadataJson()).getOrDefault("documentKind", "")))
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
        if (!"admin".equals(viewerRole) && !"teacher".equals(viewerRole) && !"student".equals(viewerRole)) {
            return List.of();
        }
        return mapper.selectSearchableRootDocuments(tenantId, viewerRole, viewerSubjectId).stream()
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

    @Override
    public TeacherResourceDocumentResponse findBySourceIdentity(
            String tenantId, String ownerSubjectId, String sourceType, String sourceIdentity, String feishuExportFormat) {
        List<TeacherSourceDocumentEntity> entities = mapper.selectPage(Page.of(1, 1), new LambdaQueryWrapper<TeacherSourceDocumentEntity>()
                .eq(TeacherSourceDocumentEntity::getTenantId, tenantId)
                .eq(TeacherSourceDocumentEntity::getCreatedBy, ownerSubjectId)
                .eq(TeacherSourceDocumentEntity::getSourceType, sourceType)
                .eq(TeacherSourceDocumentEntity::getSourceIdentityHash, TeacherResourceSourceIdentity.hash(sourceIdentity))
                .eq(TeacherSourceDocumentEntity::getFeishuExportFormat, normalizedFormat(feishuExportFormat)))
                .getRecords();
        TeacherSourceDocumentEntity entity = entities.stream().findFirst().orElse(null);
        return entity == null ? null : toResponse(entity);
    }

    @Override
    public List<TeacherResourceDocumentResponse> listSchedulableFeishu(String tenantId) {
        List<TeacherResourceDocumentResponse> documents = mapper.selectList(new LambdaQueryWrapper<TeacherSourceDocumentEntity>()
                        .eq(TeacherSourceDocumentEntity::getTenantId, tenantId)
                        .eq(TeacherSourceDocumentEntity::getSourceType, "feishu")
                        .ne(TeacherSourceDocumentEntity::getSyncStatus, "archived")
                        .orderByAsc(TeacherSourceDocumentEntity::getId))
                .stream()
                .filter(entity -> !"FILE".equalsIgnoreCase(parseMetadata(entity.getMetadataJson()).getOrDefault("documentKind", "")))
                .map(MyBatisTeacherResourceStore::toResponse)
                .toList();
        /*
         * A Feishu folder is identified by its canonical source identity, not by the display title or
         * the operator who registered it. Older registrations can therefore leave two rows for one
         * URL; scheduling both rows would download the same remote tree twice.
         */
        Map<String, TeacherResourceDocumentResponse> uniqueBySource = new LinkedHashMap<>();
        for (TeacherResourceDocumentResponse document : documents) {
            String identity = document.sourceIdentity();
            String key = identity == null || identity.isBlank()
                    ? "document:" + document.documentId()
                    : identity.strip();
            uniqueBySource.putIfAbsent(key, document);
        }
        return List.copyOf(uniqueBySource.values());
    }

    @Override
    public boolean supportsFileDocuments() {
        return true;
    }

    @Override
    public boolean hasArchivedFileDocuments(String tenantId, String rootDocumentId) {
        return tenantId != null && !tenantId.isBlank()
                && rootDocumentId != null && !rootDocumentId.isBlank()
                && mapper.existsArchivedFileByRoot(tenantId, rootDocumentId);
    }

    @Override
    public TeacherFileDocument findOrCreateFileDocument(
            TeacherResourceDocumentResponse rootDocument,
            String providerItemId,
            String sourcePath,
            String checksum,
            String splitFingerprint) {
        if (rootDocument == null || parseId(rootDocument.documentId()) == null) {
            return null;
        }
        String normalizedProviderId = normalizeIdentity(providerItemId);
        String normalizedPath = normalizePath(sourcePath);
        String pathIdentityHash = fileIdentityHash(rootDocument.documentId(), "", normalizedPath);
        String fileIdentityHash = fileIdentityHash(rootDocument.documentId(), normalizedProviderId, normalizedPath);
        TeacherSourceDocumentEntity existing = mapper.selectActiveFileByIdentity(
                        rootDocument.tenantId(),
                        rootDocument.documentId(),
                        normalizedProviderId,
                        fileIdentityHash,
                        pathIdentityHash,
                        normalizedPath)
                .stream()
                .findFirst()
                .orElse(null);
        TeacherSourceDocumentEntity entity = existing == null ? new TeacherSourceDocumentEntity() : existing;
        if (existing == null) {
            entity.setTenantId(rootDocument.tenantId());
            entity.setSourceType(rootDocument.sourceType());
            entity.setTitle(normalizedPath.isBlank() ? rootDocument.title() : normalizedPath);
            entity.setOriginalUrl(rootDocument.originalUrl());
            entity.setLocalPath(rootDocument.localPath());
            entity.setPermissionScope(rootDocument.permissionScope());
            entity.setCreatedBy(rootDocument.ownerSubjectId());
            entity.setSyncStatus("synced");
            entity.setParseStatus("pending");
            entity.setEmbeddingStatus("pending");
            entity.setFeishuExportFormat(normalizedFormat(rootDocument.feishuExportFormat()));
            entity.setMetadataJson(fileMetadata(
                    rootDocument.documentId(), normalizedProviderId, normalizedPath, fileIdentityHash, splitFingerprint));
            mapper.insert(entity);
        } else {
            Map<String, String> metadata = parseMetadata(entity.getMetadataJson());
            metadata.put("documentKind", "FILE");
            metadata.put("rootDocumentId", rootDocument.documentId());
            metadata.put("providerItemId", normalizedProviderId);
            metadata.put("sourcePath", normalizedPath);
            metadata.put("fileIdentityHash", fileIdentityHash);
            metadata.put("splitFingerprint", normalizeIdentity(splitFingerprint));
            entity.setTitle(normalizedPath.isBlank() ? entity.getTitle() : normalizedPath);
            entity.setOriginalUrl(rootDocument.originalUrl());
            entity.setLocalPath(rootDocument.localPath());
            entity.setPermissionScope(rootDocument.permissionScope());
            entity.setCreatedBy(rootDocument.ownerSubjectId());
            entity.setChecksum(blankToNull(checksum));
            entity.setParseStatus("pending");
            entity.setEmbeddingStatus("pending");
            metadata.put("indexStatus", "waiting_rebuild");
            entity.setMetadataJson(writeMetadata(metadata));
            entity.setSyncStatus("synced");
            mapper.updateById(entity);
        }
        TeacherResourceDocumentResponse fileResponse = toResponse(entity);
        return new TeacherFileDocument(
                String.valueOf(entity.getId()),
                rootDocument.documentId(),
                normalizedProviderId,
                normalizedPath,
                fileIdentityHash,
                normalizeIdentity(splitFingerprint),
                fileResponse);
    }

    @Override
    public List<TeacherFileDocument> listSearchableFileDocuments(
            String tenantId,
            String viewerRole,
            String viewerSubjectId,
            List<String> rootDocumentIds,
            int limit) {
        List<Long> roots = numericIds(rootDocumentIds);
        if (roots.isEmpty() || limit <= 0 || (!"admin".equals(viewerRole)
                && !"teacher".equals(viewerRole) && !"student".equals(viewerRole))) {
            return List.of();
        }
        List<TeacherSourceDocumentEntity> rows = mapper.selectSearchableFiles(
                tenantId,
                viewerRole,
                viewerSubjectId,
                roots,
                Math.max(1, Math.min(limit, 512)));
        LOGGER.info("teacher_file_searchable_query tenant={} role={} roots={} limit={} rows={}",
                tenantId, viewerRole, roots.size(), limit, rows.size());
        return rows.stream()
                .map(entity -> toFileDocument(entity, parseMetadata(entity.getMetadataJson())))
                .toList();
    }

    @Override
    public List<TeacherFileDocument> listSearchableFileDocumentsByIds(
            String tenantId,
            String viewerRole,
            String viewerSubjectId,
            List<String> fileDocumentIds,
            int limit) {
        List<String> ids = (fileDocumentIds == null ? List.<String>of() : fileDocumentIds).stream()
                .filter(value -> value != null && !value.isBlank())
                .map(String::strip)
                .distinct()
                .limit(12)
                .toList();
        if (ids.isEmpty() || limit <= 0 || tenantId == null || tenantId.isBlank()) {
            return List.of();
        }
        return mapper.selectSearchableFilesByIds(
                        tenantId,
                        viewerRole,
                        viewerSubjectId,
                        ids,
                        Math.max(1, Math.min(limit, 12)))
                .stream()
                .map(entity -> toFileDocument(entity, parseMetadata(entity.getMetadataJson())))
                .toList();
    }

    @Override
    public List<TeacherFileDocument> listFileDocumentsForIndexing(
            String tenantId,
            String rootDocumentId,
            int limit) {
        return listFileDocumentsForIndexing(tenantId, rootDocumentId, limit, "");
    }

    @Override
    public List<TeacherFileDocument> listFileDocumentsForIndexing(
            String tenantId,
            String rootDocumentId,
            int limit,
            String afterFileDocumentId) {
        if (tenantId == null || tenantId.isBlank() || rootDocumentId == null || rootDocumentId.isBlank()) {
            return List.of();
        }
        List<TeacherSourceDocumentEntity> rows = mapper.selectFileDocumentsForIndexing(
                tenantId,
                rootDocumentId,
                normalizeIdentity(afterFileDocumentId),
                Math.max(1, Math.min(limit, 128)));
        LOGGER.info("teacher_file_indexing_query tenant={} root={} after={} limit={} rows={}",
                tenantId, rootDocumentId, normalizeIdentity(afterFileDocumentId), limit, rows.size());
        return rows.stream()
                .map(entity -> toFileDocument(entity, parseMetadata(entity.getMetadataJson())))
                .toList();
    }

    @Override
    public List<TeacherFileDocument> listMissingFileDocuments(
            String tenantId,
            String rootDocumentId,
            List<String> activeFileIdentityHashes,
            String afterFileDocumentId,
            int limit) {
        if (tenantId == null || tenantId.isBlank() || rootDocumentId == null || rootDocumentId.isBlank()) {
            return List.of();
        }
        List<String> active = normalizeHashes(activeFileIdentityHashes);
        return mapper.selectMissingFileDocuments(
                        tenantId,
                        rootDocumentId,
                        active,
                        normalizeIdentity(afterFileDocumentId),
                        Math.max(1, Math.min(limit, 128)))
                .stream()
                .map(entity -> toFileDocument(entity, parseMetadata(entity.getMetadataJson())))
                .toList();
    }

    @Override
    public boolean archiveFileDocument(String tenantId, String fileDocumentId) {
        Long id = parseId(fileDocumentId);
        if (tenantId == null || tenantId.isBlank() || id == null) {
            return false;
        }
        return mapper.archiveFileDocument(tenantId, id) > 0;
    }

    @Override
    public int archiveMissingFileDocuments(String tenantId, String rootDocumentId, List<String> activeFileIdentityHashes) {
        String root = normalizeIdentity(rootDocumentId);
        if (root.isBlank()) {
            return 0;
        }
        List<String> active = normalizeHashes(activeFileIdentityHashes);
        return mapper.archiveMissingFiles(tenantId, root, active);
    }

    private static List<String> normalizeHashes(List<String> values) {
        return (values == null ? List.<String>of() : values).stream()
                .map(MyBatisTeacherResourceStore::normalizeIdentity)
                .filter(value -> !value.isBlank())
                .distinct()
                .toList();
    }

    private static TeacherFileDocument toFileDocument(TeacherSourceDocumentEntity entity, Map<String, String> metadata) {
        return new TeacherFileDocument(
                String.valueOf(entity.getId()),
                metadata.getOrDefault("rootDocumentId", ""),
                metadata.getOrDefault("providerItemId", ""),
                metadata.getOrDefault("sourcePath", ""),
                metadata.getOrDefault("fileIdentityHash", ""),
                metadata.getOrDefault("splitFingerprint", ""),
                toResponse(entity));
    }

    private static boolean visible(TeacherSourceDocumentEntity entity, String role, String subjectId) {
        if ("admin".equals(role)) return true;
        if ("teacher".equals(role)) {
            return subjectId != null && subjectId.equals(entity.getCreatedBy())
                    || TeacherResourceVisibilityPolicy.TEACHER_SHARED_SCOPES.contains(entity.getPermissionScope());
        }
        return TeacherResourceVisibilityPolicy.STUDENT_SHARED_SCOPES.contains(entity.getPermissionScope());
    }

    private static List<Long> numericIds(List<String> values) {
        List<Long> result = new ArrayList<>();
        for (String value : values == null ? List.<String>of() : values) {
            Long id = parseId(value);
            if (id != null) result.add(id);
        }
        return result;
    }

    private static String fileIdentityHash(String rootDocumentId, String providerItemId, String sourcePath) {
        String identity = providerItemId.isBlank() ? rootDocumentId + "\u001f" + sourcePath : providerItemId;
        return TeacherResourceSourceIdentity.hash(identity);
    }

    private static String normalizePath(String value) {
        return value == null ? "" : value.replace('\\', '/').strip();
    }

    private static String normalizeIdentity(String value) {
        return value == null ? "" : value.strip();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static Map<String, String> parseMetadata(String value) {
        Map<String, String> result = new LinkedHashMap<>();
        if (value == null || value.isBlank()) return result;
        try {
            JsonNode node = METADATA_MAPPER.readTree(value);
            node.fields().forEachRemaining(entry -> {
                if (entry.getValue().isValueNode()) result.put(entry.getKey(), entry.getValue().asText(""));
            });
        } catch (JsonProcessingException ignored) {
            // Legacy metadata is treated as absent and will be replaced on the next file sync.
        }
        return result;
    }

    private static String fileMetadata(
            String rootDocumentId, String providerItemId, String sourcePath, String fileIdentityHash, String splitFingerprint) {
        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put("documentKind", "FILE");
        metadata.put("rootDocumentId", normalizeIdentity(rootDocumentId));
        metadata.put("providerItemId", normalizeIdentity(providerItemId));
        metadata.put("sourcePath", normalizePath(sourcePath));
        metadata.put("fileIdentityHash", normalizeIdentity(fileIdentityHash));
        metadata.put("splitFingerprint", normalizeIdentity(splitFingerprint));
        return writeMetadata(metadata);
    }

    private static String writeMetadata(Map<String, String> values) {
        try {
            return METADATA_MAPPER.writeValueAsString(values);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Failed to serialize source document metadata", exception);
        }
    }
    /**
     * Converts response data to a source_document entity.
     *
     * @param document resource response
     * @return source document entity
     */
    private TeacherSourceDocumentEntity toEntity(TeacherResourceDocumentResponse document) {
        TeacherSourceDocumentEntity entity = new TeacherSourceDocumentEntity();
        entity.setId(parseId(document.documentId()));
        entity.setTenantId(document.tenantId());
        entity.setSourceType(document.sourceType());
        entity.setTitle(document.title());
        entity.setOriginalUrl(document.originalUrl());
        entity.setSourceIdentity(document.sourceIdentity());
        entity.setSourceIdentityHash(document.sourceIdentity() == null || document.sourceIdentity().isBlank()
                ? null : TeacherResourceSourceIdentity.hash(document.sourceIdentity()));
        entity.setLocalPath(document.localPath());
        entity.setVersion(document.providerRevision());
        entity.setProviderRevision(document.providerRevision());
        entity.setChecksum(document.contentChecksum());
        entity.setFeishuExportFormat(normalizedFormat(document.feishuExportFormat()));
        entity.setPermissionScope(document.permissionScope());
        entity.setCreatedBy(document.ownerSubjectId());
        entity.setSyncStatus(document.syncStatus());
        entity.setParseStatus(document.parseStatus());
        entity.setParseMode(normalizeParseMode(document.parseMode()));
        entity.setEmbeddingStatus(document.embeddingStatus());
        Map<String, String> metadata = parseMetadata(document.documentId() == null ? "" : existingMetadata(document.documentId()));
        metadata.put("indexStatus", document.indexStatus());
        metadata.put("feishuExportFormat", normalizedFormat(document.feishuExportFormat()));
        metadata.put("parseMode", normalizeParseMode(document.parseMode()));
        entity.setMetadataJson(writeMetadata(metadata));
        return entity;
    }

    private String existingMetadata(String documentId) {
        Long id = parseId(documentId);
        if (id == null) return "";
        TeacherSourceDocumentEntity existing = mapper.selectById(id);
        return existing == null ? "" : existing.getMetadataJson();
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
                firstNonBlank(entity.getFeishuExportFormat(), feishuExportFormat(entity.getSourceType(), entity.getMetadataJson())),
                List.of(),
                normalizeParseMode(firstNonBlank(entity.getParseMode(), textMetadataField(entity.getMetadataJson(), "parseMode"))),
                firstNonBlank(entity.getProviderRevision(), entity.getVersion()),
                entity.getChecksum(),
                entity.getSourceIdentity());
    }

    /**
     * Builds compact metadata JSON containing index status.
     *
     * @param indexStatus index status
     * @param feishuExportFormat native Feishu export format
     * @return metadata JSON
     */
    private static String indexMetadata(String indexStatus, String feishuExportFormat, String parseMode) {
        String value = indexStatus == null || indexStatus.isBlank() ? "waiting_rebuild" : indexStatus.strip();
        String exportFormat = feishuExportFormat == null || feishuExportFormat.isBlank()
                ? ""
                : feishuExportFormat.strip().toLowerCase();
        String normalizedParseMode = normalizeParseMode(parseMode);
        return "{\"indexStatus\":\"" + escapeJson(value) + "\","
                + "\"feishuExportFormat\":\"" + escapeJson(exportFormat) + "\","
                + "\"parseMode\":\"" + escapeJson(normalizedParseMode) + "\"}";
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
     * Extracts Feishu export format from compact metadata JSON.
     *
     * @param sourceType source type
     * @param metadataJson metadata JSON
     * @return md/docx/pdf for Feishu sources, or null for non-Feishu sources
     */
    private static String feishuExportFormat(String sourceType, String metadataJson) {
        if (!"feishu".equalsIgnoreCase(sourceType == null ? "" : sourceType)) {
            return null;
        }
        String value = textMetadataField(metadataJson, "feishuExportFormat");
        if ("docx".equals(value) || "pdf".equals(value) || "md".equals(value)) {
            return value;
        }
        return "md";
    }

    /**
     * Extracts a string field from compact metadata JSON.
     *
     * @param metadataJson metadata JSON
     * @param fieldName field name
     * @return extracted text or empty string
     */
    private static String textMetadataField(String metadataJson, String fieldName) {
        if (metadataJson == null || metadataJson.isBlank()) {
            return "";
        }
        int keyIndex = metadataJson.indexOf("\"" + fieldName + "\"");
        if (keyIndex < 0) {
            return "";
        }
        int colonIndex = metadataJson.indexOf(':', keyIndex);
        int firstQuote = metadataJson.indexOf('"', colonIndex + 1);
        int secondQuote = metadataJson.indexOf('"', firstQuote + 1);
        if (colonIndex < 0 || firstQuote < 0 || secondQuote < 0) {
            return "";
        }
        return metadataJson.substring(firstQuote + 1, secondQuote);
    }

    private static String normalizeParseMode(String value) {
        String normalized = value == null || value.isBlank() ? "TEXT" : value.strip().toUpperCase();
        if ("AI".equals(normalized)) return "AI";
        // Keep the persisted value so existing resources can be distinguished from image-localized Markdown imports.
        if ("MARKDOWN_ASSETS".equals(normalized)) return "MARKDOWN_ASSETS";
        return "TEXT";
    }

    private static String normalizedFormat(String value) {
        return value == null || value.isBlank() ? "" : value.strip().toLowerCase();
    }

    private static String firstNonBlank(String first, String second) {
        return first == null || first.isBlank() ? second : first;
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


