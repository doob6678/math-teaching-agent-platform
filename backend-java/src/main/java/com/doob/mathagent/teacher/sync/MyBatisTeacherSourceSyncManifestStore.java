package com.doob.mathagent.teacher.sync;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.doob.mathagent.teacher.sync.mapper.TeacherSourceSyncManifestMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

/** MyBatis implementation of the file-level Feishu state machine. */
@Repository
@ConditionalOnProperty(prefix = "math-agent.database", name = "enabled", havingValue = "true")
public class MyBatisTeacherSourceSyncManifestStore implements TeacherSourceSyncManifestStore {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final TeacherSourceSyncManifestMapper mapper;

    public MyBatisTeacherSourceSyncManifestStore(TeacherSourceSyncManifestMapper mapper) {
        this.mapper = mapper;
    }

    /** Reads provider identity from the existing manifest without changing source data. */
    @Override
    public String providerItemId(String tenantId, String documentId, String sourcePath) {
        String normalizedPath = sourcePath == null ? "" : sourcePath.replace('\\', '/').strip();
        if (tenantId == null || tenantId.isBlank() || documentId == null || documentId.isBlank()
                || normalizedPath.isBlank()) {
            return "";
        }
        return mapper.selectPage(Page.of(1, 1), new LambdaQueryWrapper<TeacherSourceSyncManifestEntity>()
                        .eq(TeacherSourceSyncManifestEntity::getTenantId, tenantId)
                        .eq(TeacherSourceSyncManifestEntity::getDocumentId, documentId)
                        .eq(TeacherSourceSyncManifestEntity::getLogicalPath, normalizedPath)
                        .eq(TeacherSourceSyncManifestEntity::getItemType, "file")
                        .orderByAsc(TeacherSourceSyncManifestEntity::getProviderItemId))
                .getRecords().stream()
                .map(TeacherSourceSyncManifestEntity::getProviderItemId)
                .filter(value -> value != null && !value.isBlank())
                .findFirst()
                .orElse("");
    }

    @Override
    public void recordDiscovery(String tenantId, String rootUrl, String createdBy, String documentId, String json) {
        String rootId = rootId(rootUrl);
        Instant now = Instant.now();
        List<JsonNode> items = array(json);
        for (JsonNode item : items) {
            String providerId = text(item, "token");
            if (providerId.isBlank()) {
                continue;
            }
            TeacherSourceSyncManifestEntity existing = find(rootId, providerId);
            TeacherSourceSyncManifestEntity entity = existing == null ? new TeacherSourceSyncManifestEntity() : existing;
            if (existing == null) {
                entity.setManifestId(UUID.randomUUID().toString());
                entity.setSyncRootId(rootId);
                entity.setTenantId(tenantId);
                entity.setProviderItemId(providerId);
                entity.setSyncStatus("DISCOVERED");
                entity.setAttempt(0);
                entity.setArchiveStatus("ACTIVE");
            }
            entity.setParentProviderItemId(text(item, "parentToken"));
            entity.setLogicalPath(text(item, "path"));
            entity.setItemType(text(item, "type"));
            entity.setRevision(first(item, "revision", "modifiedTime"));
            entity.setProviderModifiedAt(epochSeconds(text(item, "modifiedTime")));
            entity.setDocumentId(blankToNull(documentId));
            entity.setLocalPath(blankToNull(text(item, "relativePath")));
            entity.setDiscoveredAt(local(now));
            entity.setUpdatedAt(local(now));
            if (existing == null) {
                mapper.insert(entity);
            } else {
                mapper.updateById(entity);
            }
        }
        mapper.update(null, new LambdaUpdateWrapper<TeacherSourceSyncManifestEntity>()
                .eq(TeacherSourceSyncManifestEntity::getSyncRootId, rootId)
                .set(TeacherSourceSyncManifestEntity::getUpdatedAt, local(now)));
    }

    @Override
    public void markDownloaded(String tenantId, String rootUrl, String changedJson, Instant now) {
        updateChanged(rootId(rootUrl), changedJson, "DOWNLOADED", now, null, now, null);
    }

    @Override
    public void markParsing(String tenantId, String rootUrl, String changedJson, Instant now) {
        updateChanged(rootId(rootUrl), changedJson, "PARSING", now, null, null, now.plusSeconds(900));
    }

    @Override
    public void markParsed(String tenantId, String rootUrl, String changedJson, Instant now) {
        updateChanged(rootId(rootUrl), changedJson, "PARSED", now, null, null, now.plusSeconds(900));
    }

    @Override
    public void markEmbedding(String tenantId, String rootUrl, String changedJson, Instant now) {
        updateChanged(rootId(rootUrl), changedJson, "EMBEDDING", now, null, null, now.plusSeconds(1800));
    }

    @Override
    public void markIndexed(String tenantId, String rootUrl, String changedJson, Instant now) {
        updateChanged(rootId(rootUrl), changedJson, "INDEXED", now, now, null, null);
    }

    @Override
    public void markFailed(String tenantId, String rootUrl, String changedJson, String error, Instant nextRetryAt, Instant now) {
        for (String providerId : ids(changedJson)) {
            mapper.update(null, new LambdaUpdateWrapper<TeacherSourceSyncManifestEntity>()
                    .eq(TeacherSourceSyncManifestEntity::getSyncRootId, rootId(rootUrl))
                    .eq(TeacherSourceSyncManifestEntity::getProviderItemId, providerId)
                    .set(TeacherSourceSyncManifestEntity::getSyncStatus, "FAILED")
                    .set(TeacherSourceSyncManifestEntity::getLastError, truncate(error))
                    .set(TeacherSourceSyncManifestEntity::getNextRetryAt, local(nextRetryAt))
                    .set(TeacherSourceSyncManifestEntity::getLeaseUntil, null)
                    .set(TeacherSourceSyncManifestEntity::getUpdatedAt, local(now)));
        }
    }

    @Override
    public void markRootFailed(String tenantId, String rootUrl, String error, Instant nextRetryAt, Instant now) {
        mapper.update(null, new LambdaUpdateWrapper<TeacherSourceSyncManifestEntity>()
                .eq(TeacherSourceSyncManifestEntity::getSyncRootId, rootId(rootUrl))
                .in(TeacherSourceSyncManifestEntity::getSyncStatus,
                        List.of("DISCOVERED", "DOWNLOADING", "DOWNLOADED", "PARSING", "PARSED", "EMBEDDING"))
                .set(TeacherSourceSyncManifestEntity::getSyncStatus, "FAILED")
                .set(TeacherSourceSyncManifestEntity::getLastError, truncate(error))
                .set(TeacherSourceSyncManifestEntity::getNextRetryAt, local(nextRetryAt))
                .set(TeacherSourceSyncManifestEntity::getLeaseUntil, null)
                .set(TeacherSourceSyncManifestEntity::getUpdatedAt, local(now)));
    }

    @Override
    public int recoverExpiredLeases(Instant now) {
        return mapper.update(null, new LambdaUpdateWrapper<TeacherSourceSyncManifestEntity>()
                .in(TeacherSourceSyncManifestEntity::getSyncStatus, List.of("DOWNLOADING", "PARSING", "EMBEDDING"))
                .lt(TeacherSourceSyncManifestEntity::getLeaseUntil, local(now))
                .set(TeacherSourceSyncManifestEntity::getSyncStatus, "RETRY_PENDING")
                .set(TeacherSourceSyncManifestEntity::getNextRetryAt, local(now))
                .set(TeacherSourceSyncManifestEntity::getLeaseUntil, null)
                .set(TeacherSourceSyncManifestEntity::getUpdatedAt, local(now)));
    }

    private void updateChanged(String rootId, String json, String status, Instant now, Instant indexedAt,
            Instant downloadedAt, Instant leaseUntil) {
        for (String providerId : ids(json)) {
            LambdaUpdateWrapper<TeacherSourceSyncManifestEntity> update = new LambdaUpdateWrapper<TeacherSourceSyncManifestEntity>()
                    .eq(TeacherSourceSyncManifestEntity::getSyncRootId, rootId)
                    .eq(TeacherSourceSyncManifestEntity::getProviderItemId, providerId)
                    .set(TeacherSourceSyncManifestEntity::getSyncStatus, status)
                    .set(TeacherSourceSyncManifestEntity::getUpdatedAt, local(now));
            if (downloadedAt != null) update.set(TeacherSourceSyncManifestEntity::getDownloadedAt, local(downloadedAt));
            if (indexedAt != null) update.set(TeacherSourceSyncManifestEntity::getIndexedAt, local(indexedAt));
            if ("PARSED".equals(status)) update.set(TeacherSourceSyncManifestEntity::getParsedAt, local(now));
            if ("INDEXED".equals(status)) update.set(TeacherSourceSyncManifestEntity::getLeaseUntil, null);
            if ("DOWNLOADED".equals(status) || "PARSING".equals(status) || "EMBEDDING".equals(status)) {
                update.set(TeacherSourceSyncManifestEntity::getLastError, null)
                        .set(TeacherSourceSyncManifestEntity::getNextRetryAt, null);
            }
            if (leaseUntil != null) update.set(TeacherSourceSyncManifestEntity::getLeaseUntil, local(leaseUntil));
            mapper.update(null, update);
        }
    }

    private TeacherSourceSyncManifestEntity find(String rootId, String providerId) {
        return mapper.selectPage(Page.of(1, 1), new LambdaQueryWrapper<TeacherSourceSyncManifestEntity>()
                .eq(TeacherSourceSyncManifestEntity::getSyncRootId, rootId)
                .eq(TeacherSourceSyncManifestEntity::getProviderItemId, providerId))
                .getRecords().stream().findFirst().orElse(null);
    }

    private static List<String> ids(String json) {
        List<String> values = new ArrayList<>();
        for (JsonNode item : array(json)) {
            String id = text(item, "token");
            if (!id.isBlank()) values.add(id);
        }
        return values;
    }

    private static List<JsonNode> array(String json) {
        try {
            JsonNode node = MAPPER.readTree(json == null || json.isBlank() ? "[]" : json);
            List<JsonNode> values = new ArrayList<>();
            if (node != null && node.isArray()) node.forEach(values::add);
            return values;
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private static String rootId(String rootUrl) {
        return com.doob.mathagent.teacher.support.TeacherResourceSourceIdentity.hash(rootUrl == null ? "" : rootUrl);
    }

    private static String text(JsonNode node, String field) { return node.path(field).asText("").strip(); }
    private static String first(JsonNode node, String first, String fallback) {
        String value = text(node, first); return value.isBlank() ? text(node, fallback) : value;
    }
    private static LocalDateTime epochSeconds(String value) {
        try { return LocalDateTime.ofInstant(Instant.ofEpochSecond(Long.parseLong(value)), ZoneOffset.UTC); }
        catch (RuntimeException ignored) { return null; }
    }
    private static LocalDateTime local(Instant value) { return value == null ? null : LocalDateTime.ofInstant(value, ZoneOffset.UTC); }
    private static String blankToNull(String value) { return value == null || value.isBlank() ? null : value; }
    private static String truncate(String value) { return value == null ? null : value.substring(0, Math.min(value.length(), 4000)); }

    /** MyBatis entity for one provider file. */
    @TableName("teacher_source_sync_manifest")
    public static class TeacherSourceSyncManifestEntity {
        @TableId private String manifestId;
        private String syncRootId; private String tenantId; private String providerItemId; private String parentProviderItemId;
        private String logicalPath; private String itemType; private String revision; private LocalDateTime providerModifiedAt;
        private String contentChecksum; private String documentId; private String archiveStatus; private String indexedRevision;
        private LocalDateTime discoveredAt; private LocalDateTime archivedAt; private LocalDateTime updatedAt;
        private String syncStatus; private Integer attempt; private String localPath; private String lastError;
        private LocalDateTime leaseUntil; private LocalDateTime nextRetryAt; private LocalDateTime downloadedAt;
        private LocalDateTime parsedAt; private LocalDateTime indexedAt;
        public String getManifestId(){return manifestId;} public void setManifestId(String v){manifestId=v;}
        public String getSyncRootId(){return syncRootId;} public void setSyncRootId(String v){syncRootId=v;}
        public String getTenantId(){return tenantId;} public void setTenantId(String v){tenantId=v;}
        public String getProviderItemId(){return providerItemId;} public void setProviderItemId(String v){providerItemId=v;}
        public String getParentProviderItemId(){return parentProviderItemId;} public void setParentProviderItemId(String v){parentProviderItemId=v;}
        public String getLogicalPath(){return logicalPath;} public void setLogicalPath(String v){logicalPath=v;}
        public String getItemType(){return itemType;} public void setItemType(String v){itemType=v;}
        public String getRevision(){return revision;} public void setRevision(String v){revision=v;}
        public LocalDateTime getProviderModifiedAt(){return providerModifiedAt;} public void setProviderModifiedAt(LocalDateTime v){providerModifiedAt=v;}
        public String getContentChecksum(){return contentChecksum;} public void setContentChecksum(String v){contentChecksum=v;}
        public String getDocumentId(){return documentId;} public void setDocumentId(String v){documentId=v;}
        public String getArchiveStatus(){return archiveStatus;} public void setArchiveStatus(String v){archiveStatus=v;}
        public String getIndexedRevision(){return indexedRevision;} public void setIndexedRevision(String v){indexedRevision=v;}
        public LocalDateTime getDiscoveredAt(){return discoveredAt;} public void setDiscoveredAt(LocalDateTime v){discoveredAt=v;}
        public LocalDateTime getArchivedAt(){return archivedAt;} public void setArchivedAt(LocalDateTime v){archivedAt=v;}
        public LocalDateTime getUpdatedAt(){return updatedAt;} public void setUpdatedAt(LocalDateTime v){updatedAt=v;}
        public String getSyncStatus(){return syncStatus;} public void setSyncStatus(String v){syncStatus=v;}
        public Integer getAttempt(){return attempt;} public void setAttempt(Integer v){attempt=v;}
        public String getLocalPath(){return localPath;} public void setLocalPath(String v){localPath=v;}
        public String getLastError(){return lastError;} public void setLastError(String v){lastError=v;}
        public LocalDateTime getLeaseUntil(){return leaseUntil;} public void setLeaseUntil(LocalDateTime v){leaseUntil=v;}
        public LocalDateTime getNextRetryAt(){return nextRetryAt;} public void setNextRetryAt(LocalDateTime v){nextRetryAt=v;}
        public LocalDateTime getDownloadedAt(){return downloadedAt;} public void setDownloadedAt(LocalDateTime v){downloadedAt=v;}
        public LocalDateTime getParsedAt(){return parsedAt;} public void setParsedAt(LocalDateTime v){parsedAt=v;}
        public LocalDateTime getIndexedAt(){return indexedAt;} public void setIndexedAt(LocalDateTime v){indexedAt=v;}
    }
}
