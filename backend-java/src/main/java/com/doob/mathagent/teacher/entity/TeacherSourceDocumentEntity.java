package com.doob.mathagent.teacher.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/**
 * MyBatis-Plus entity mapped to the shared source_document table.
 */
@TableName("source_document")
public class TeacherSourceDocumentEntity {

    /** Source document primary key. */
    @TableId
    private Long id;

    /** Tenant id used for school or organization isolation. */
    private String tenantId;

    /** Source type, such as feishu, local_path, local_docx, textbook_pdf, or textbook_md. */
    private String sourceType;

    /** Display title used in teacher/admin resource pages. */
    private String title;

    /** Remote source URL, usually a Feishu document or folder URL. */
    private String originalUrl;

    /** Local file or folder path configured by teacher/admin. */
    private String localPath;

    /** Version label used to keep old RAG references traceable. */
    private String version;

    /** Content checksum for incremental sync and parse skip decisions. */
    private String checksum;

    /** Synchronization status, such as pending, registered, synced, failed, or archived. */
    private String syncStatus;

    /** Parse status for document parsing tasks. */
    private String parseStatus;

    /** User-selected parse mode: TEXT or AI. */
    private String parseMode;

    /** Embedding status for vector indexing tasks. */
    private String embeddingStatus;

    /** Permission scope used when filtering RAG sources. */
    private String permissionScope;

    /** Subject id that created or owns this resource. */
    private String createdBy;

    /** Additional metadata JSON for source-specific attributes. */
    private String metadataJson;

    /**
     * Returns the source document id.
     *
     * @return source document id
     */
    public Long getId() {
        return id;
    }

    /**
     * Sets the source document id.
     *
     * @param id source document id
     */
    public void setId(Long id) {
        this.id = id;
    }

    /**
     * Returns the tenant id.
     *
     * @return tenant id
     */
    public String getTenantId() {
        return tenantId;
    }

    /**
     * Sets the tenant id.
     *
     * @param tenantId tenant id
     */
    public void setTenantId(String tenantId) {
        this.tenantId = tenantId;
    }

    /**
     * Returns the source type.
     *
     * @return source type
     */
    public String getSourceType() {
        return sourceType;
    }

    /**
     * Sets the source type.
     *
     * @param sourceType source type
     */
    public void setSourceType(String sourceType) {
        this.sourceType = sourceType;
    }

    /**
     * Returns the display title.
     *
     * @return display title
     */
    public String getTitle() {
        return title;
    }

    /**
     * Sets the display title.
     *
     * @param title display title
     */
    public void setTitle(String title) {
        this.title = title;
    }

    /**
     * Returns the original URL.
     *
     * @return original URL
     */
    public String getOriginalUrl() {
        return originalUrl;
    }

    /**
     * Sets the original URL.
     *
     * @param originalUrl original URL
     */
    public void setOriginalUrl(String originalUrl) {
        this.originalUrl = originalUrl;
    }

    /**
     * Returns the local path.
     *
     * @return local path
     */
    public String getLocalPath() {
        return localPath;
    }

    /**
     * Sets the local path.
     *
     * @param localPath local path
     */
    public void setLocalPath(String localPath) {
        this.localPath = localPath;
    }

    /**
     * Returns the version label.
     *
     * @return version label
     */
    public String getVersion() {
        return version;
    }

    /**
     * Sets the version label.
     *
     * @param version version label
     */
    public void setVersion(String version) {
        this.version = version;
    }

    /**
     * Returns the content checksum.
     *
     * @return content checksum
     */
    public String getChecksum() {
        return checksum;
    }

    /**
     * Sets the content checksum.
     *
     * @param checksum content checksum
     */
    public void setChecksum(String checksum) {
        this.checksum = checksum;
    }

    /**
     * Returns the sync status.
     *
     * @return sync status
     */
    public String getSyncStatus() {
        return syncStatus;
    }

    /**
     * Sets the sync status.
     *
     * @param syncStatus sync status
     */
    public void setSyncStatus(String syncStatus) {
        this.syncStatus = syncStatus;
    }

    /**
     * Returns the parse status.
     *
     * @return parse status
     */
    public String getParseStatus() {
        return parseStatus;
    }

    /**
     * Sets the parse status.
     *
     * @param parseStatus parse status
     */
    public void setParseStatus(String parseStatus) {
        this.parseStatus = parseStatus;
    }

    public String getParseMode() {
        return parseMode;
    }

    public void setParseMode(String parseMode) {
        this.parseMode = parseMode;
    }

    /**
     * Returns the embedding status.
     *
     * @return embedding status
     */
    public String getEmbeddingStatus() {
        return embeddingStatus;
    }

    /**
     * Sets the embedding status.
     *
     * @param embeddingStatus embedding status
     */
    public void setEmbeddingStatus(String embeddingStatus) {
        this.embeddingStatus = embeddingStatus;
    }

    /**
     * Returns the permission scope.
     *
     * @return permission scope
     */
    public String getPermissionScope() {
        return permissionScope;
    }

    /**
     * Sets the permission scope.
     *
     * @param permissionScope permission scope
     */
    public void setPermissionScope(String permissionScope) {
        this.permissionScope = permissionScope;
    }

    /**
     * Returns the creator subject id.
     *
     * @return creator subject id
     */
    public String getCreatedBy() {
        return createdBy;
    }

    /**
     * Sets the creator subject id.
     *
     * @param createdBy creator subject id
     */
    public void setCreatedBy(String createdBy) {
        this.createdBy = createdBy;
    }

    /**
     * Returns metadata JSON.
     *
     * @return metadata JSON
     */
    public String getMetadataJson() {
        return metadataJson;
    }

    /**
     * Sets metadata JSON.
     *
     * @param metadataJson metadata JSON
     */
    public void setMetadataJson(String metadataJson) {
        this.metadataJson = metadataJson;
    }
}
