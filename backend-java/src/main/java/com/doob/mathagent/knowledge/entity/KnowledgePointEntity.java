package com.doob.mathagent.knowledge.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/**
 * MyBatis entity for a standard knowledge point.
 */
@TableName("knowledge_point")
public class KnowledgePointEntity {

    /** Stable knowledge point id. */
    @TableId
    private String knowledgePointId;

    /** Tenant id for isolation. */
    private String tenantId;

    /** Creator subject id for private ownership. */
    private String ownerSubjectId;

    /** Permission scope such as TEACHER_PRIVATE or MATH_VIP. */
    private String permissionScope;

    /** Display name. */
    private String knowledgePointName;

    /** Curriculum chapter path. */
    private String chapterPath;

    /** Row status. */
    private String status;

    /** Source summary. */
    private String sourceSummary;

    public String getKnowledgePointId() {
        return knowledgePointId;
    }

    public void setKnowledgePointId(String knowledgePointId) {
        this.knowledgePointId = knowledgePointId;
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

    public String getPermissionScope() {
        return permissionScope;
    }

    public void setPermissionScope(String permissionScope) {
        this.permissionScope = permissionScope;
    }

    public String getKnowledgePointName() {
        return knowledgePointName;
    }

    public void setKnowledgePointName(String knowledgePointName) {
        this.knowledgePointName = knowledgePointName;
    }

    public String getChapterPath() {
        return chapterPath;
    }

    public void setChapterPath(String chapterPath) {
        this.chapterPath = chapterPath;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getSourceSummary() {
        return sourceSummary;
    }

    public void setSourceSummary(String sourceSummary) {
        this.sourceSummary = sourceSummary;
    }
}
