package com.doob.mathagent.teacher.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

/**
 * MyBatis entity for extracted teacher resource images and attachments.
 */
@TableName("teacher_resource_asset")
public class TeacherResourceAssetEntity {

    @TableId
    private String assetId;
    private String tenantId;
    private String ownerSubjectId;
    private Long documentId;
    private Long blockId;
    private String permissionScope;
    private String sourcePath;
    private Integer pageNo;
    private String providerAssetId;
    private String checksum;
    private String mimeType;
    private Integer width;
    private Integer height;
    private String storageKey;
    private String status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public String getAssetId() {
        return assetId;
    }

    public void setAssetId(String assetId) {
        this.assetId = assetId;
    }

    public String getTenantId() {
        return tenantId;
    }

    public void setTenantId(String tenantId) {
        this.tenantId = tenantId;
    }

    public String getOwnerSubjectId() {
        return ownerSubjectId;
    }

    public void setOwnerSubjectId(String ownerSubjectId) {
        this.ownerSubjectId = ownerSubjectId;
    }

    public Long getDocumentId() {
        return documentId;
    }

    public void setDocumentId(Long documentId) {
        this.documentId = documentId;
    }

    public Long getBlockId() {
        return blockId;
    }

    public void setBlockId(Long blockId) {
        this.blockId = blockId;
    }

    public String getPermissionScope() {
        return permissionScope;
    }

    public void setPermissionScope(String permissionScope) {
        this.permissionScope = permissionScope;
    }

    public String getSourcePath() {
        return sourcePath;
    }

    public void setSourcePath(String sourcePath) {
        this.sourcePath = sourcePath;
    }

    public Integer getPageNo() {
        return pageNo;
    }

    public void setPageNo(Integer pageNo) {
        this.pageNo = pageNo;
    }

    public String getProviderAssetId() {
        return providerAssetId;
    }

    public void setProviderAssetId(String providerAssetId) {
        this.providerAssetId = providerAssetId;
    }

    public String getChecksum() {
        return checksum;
    }

    public void setChecksum(String checksum) {
        this.checksum = checksum;
    }

    public String getMimeType() {
        return mimeType;
    }

    public void setMimeType(String mimeType) {
        this.mimeType = mimeType;
    }

    public Integer getWidth() {
        return width;
    }

    public void setWidth(Integer width) {
        this.width = width;
    }

    public Integer getHeight() {
        return height;
    }

    public void setHeight(Integer height) {
        this.height = height;
    }

    public String getStorageKey() {
        return storageKey;
    }

    public void setStorageKey(String storageKey) {
        this.storageKey = storageKey;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
