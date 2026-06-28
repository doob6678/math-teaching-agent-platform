package com.doob.mathagent.teaching.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/**
 * MyBatis-Plus entity for recoverable teaching workflow tasks.
 *
 * <p>Only the field contract is introduced in this stage. The write path stays on the in-memory store until the
 * migration and JSON serialization contract are added with dedicated persistence tests.</p>
 */
@TableName("teaching_task")
public class TeachingTaskEntity {

    /** Backend-generated task id returned to the frontend for resume queries. */
    @TableId
    private String taskId;

    /** Tenant id used to separate public school/team data from private user data. */
    private String tenantId;

    /** Subject type, for example teacher, student, admin, or anonymous. */
    private String subjectType;

    /** Subject id used with tenant id to isolate private task ownership. */
    private String subjectId;

    /** Idempotency key built from tenant, subject, device, and client request id. */
    private String idempotencyKey;

    /** Workflow status, such as PENDING, RUNNING, COMPLETED, or FAILED. */
    private String status;

    /** Full JSON response for frontend resume before normalized child tables are introduced. */
    private String responseJson;

    /**
     * Returns the backend task id.
     *
     * @return task id
     */
    public String getTaskId() {
        return taskId;
    }

    /**
     * Sets the backend task id.
     *
     * @param taskId task id
     */
    public void setTaskId(String taskId) {
        this.taskId = taskId;
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
     * Returns the subject type.
     *
     * @return subject type
     */
    public String getSubjectType() {
        return subjectType;
    }

    /**
     * Sets the subject type.
     *
     * @param subjectType subject type
     */
    public void setSubjectType(String subjectType) {
        this.subjectType = subjectType;
    }

    /**
     * Returns the subject id.
     *
     * @return subject id
     */
    public String getSubjectId() {
        return subjectId;
    }

    /**
     * Sets the subject id.
     *
     * @param subjectId subject id
     */
    public void setSubjectId(String subjectId) {
        this.subjectId = subjectId;
    }

    /**
     * Returns the idempotency key.
     *
     * @return idempotency key
     */
    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    /**
     * Sets the idempotency key.
     *
     * @param idempotencyKey idempotency key
     */
    public void setIdempotencyKey(String idempotencyKey) {
        this.idempotencyKey = idempotencyKey;
    }

    /**
     * Returns the workflow status.
     *
     * @return status
     */
    public String getStatus() {
        return status;
    }

    /**
     * Sets the workflow status.
     *
     * @param status status
     */
    public void setStatus(String status) {
        this.status = status;
    }

    /**
     * Returns the serialized response JSON.
     *
     * @return response JSON
     */
    public String getResponseJson() {
        return responseJson;
    }

    /**
     * Sets the serialized response JSON.
     *
     * @param responseJson response JSON
     */
    public void setResponseJson(String responseJson) {
        this.responseJson = responseJson;
    }
}
