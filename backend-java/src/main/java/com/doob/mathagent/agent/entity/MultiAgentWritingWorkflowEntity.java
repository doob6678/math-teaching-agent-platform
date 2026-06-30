package com.doob.mathagent.agent.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.Instant;

/**
 * MyBatis-Plus entity for multi_agent_writing_workflow rows.
 */
@TableName("multi_agent_writing_workflow")
public class MultiAgentWritingWorkflowEntity {

    /** Backend workflow id shared by all writing stage traces. */
    @TableId
    private String workflowId;

    /** Tenant id used to isolate schools or deployments. */
    private String tenantId;

    /** Backend subject role, such as teacher or admin. */
    private String subjectType;

    /** Backend subject id that owns the workflow. */
    private String subjectId;

    /** Workflow status, such as RUNNING, COMPLETED, or FAILED. */
    private String status;

    /** Safe status message without raw prompt or model output. */
    private String message;

    /** JSON metadata containing safe stage results and provider token usage. */
    private String metadataJson;

    /** Workflow creation time. */
    private Instant createdAt;

    /** Latest workflow update time. */
    private Instant updatedAt;

    /**
     * Returns workflow id.
     */
    public String getWorkflowId() {
        return workflowId;
    }

    /**
     * Sets workflow id.
     */
    public void setWorkflowId(String workflowId) {
        this.workflowId = workflowId;
    }

    /**
     * Returns tenant id.
     */
    public String getTenantId() {
        return tenantId;
    }

    /**
     * Sets tenant id.
     */
    public void setTenantId(String tenantId) {
        this.tenantId = tenantId;
    }

    /**
     * Returns subject type.
     */
    public String getSubjectType() {
        return subjectType;
    }

    /**
     * Sets subject type.
     */
    public void setSubjectType(String subjectType) {
        this.subjectType = subjectType;
    }

    /**
     * Returns subject id.
     */
    public String getSubjectId() {
        return subjectId;
    }

    /**
     * Sets subject id.
     */
    public void setSubjectId(String subjectId) {
        this.subjectId = subjectId;
    }

    /**
     * Returns workflow status.
     */
    public String getStatus() {
        return status;
    }

    /**
     * Sets workflow status.
     */
    public void setStatus(String status) {
        this.status = status;
    }

    /**
     * Returns safe status message.
     */
    public String getMessage() {
        return message;
    }

    /**
     * Sets safe status message.
     */
    public void setMessage(String message) {
        this.message = message;
    }

    /**
     * Returns metadata JSON.
     */
    public String getMetadataJson() {
        return metadataJson;
    }

    /**
     * Sets metadata JSON.
     */
    public void setMetadataJson(String metadataJson) {
        this.metadataJson = metadataJson;
    }

    /**
     * Returns creation time.
     */
    public Instant getCreatedAt() {
        return createdAt;
    }

    /**
     * Sets creation time.
     */
    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    /**
     * Returns latest update time.
     */
    public Instant getUpdatedAt() {
        return updatedAt;
    }

    /**
     * Sets latest update time.
     */
    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
