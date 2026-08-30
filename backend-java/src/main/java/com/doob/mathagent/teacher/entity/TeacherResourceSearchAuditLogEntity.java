package com.doob.mathagent.teacher.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.Instant;

/**
 * MyBatis-Plus entity mapped to teacher_resource_search_audit_log.
 */
@TableName("teacher_resource_search_audit_log")
public class TeacherResourceSearchAuditLogEntity {

    /** Database primary key. */
    @TableId
    private Long id;

    /** Server-generated query id returned to UI and MCP callers. */
    private String queryId;

    /** Audit event timestamp. */
    private Instant occurredAt;

    /** Backend resolved tenant id. */
    private String tenantId;

    /** Backend resolved subject type. */
    private String subjectType;

    /** Backend resolved subject id. */
    private String subjectId;

    /** Normalized search text. */
    private String queryText;

    /** Bounded search limit used by the service. */
    private Integer requestedLimit;

    /** Retrieval mode used by the service. */
    private String retrievalMode;

    /** Number of returned visible hits. */
    private Integer hitCount;

    /** Service-side elapsed milliseconds. */
    private Long elapsedMs;

    /** Logical API or MCP endpoint that initiated the query. */
    private String endpoint;

    /**
     * Returns the primary key.
     */
    public Long getId() {
        return id;
    }

    /**
     * Sets the primary key.
     */
    public void setId(Long id) {
        this.id = id;
    }

    /**
     * Returns the query id.
     */
    public String getQueryId() {
        return queryId;
    }

    /**
     * Sets the query id.
     */
    public void setQueryId(String queryId) {
        this.queryId = queryId;
    }

    /**
     * Returns the event timestamp.
     */
    public Instant getOccurredAt() {
        return occurredAt;
    }

    /**
     * Sets the event timestamp.
     */
    public void setOccurredAt(Instant occurredAt) {
        this.occurredAt = occurredAt;
    }

    /**
     * Returns the tenant id.
     */
    public String getTenantId() {
        return tenantId;
    }

    /**
     * Sets the tenant id.
     */
    public void setTenantId(String tenantId) {
        this.tenantId = tenantId;
    }

    /**
     * Returns the subject type.
     */
    public String getSubjectType() {
        return subjectType;
    }

    /**
     * Sets the subject type.
     */
    public void setSubjectType(String subjectType) {
        this.subjectType = subjectType;
    }

    /**
     * Returns the subject id.
     */
    public String getSubjectId() {
        return subjectId;
    }

    /**
     * Sets the subject id.
     */
    public void setSubjectId(String subjectId) {
        this.subjectId = subjectId;
    }

    /**
     * Returns the query text.
     */
    public String getQueryText() {
        return queryText;
    }

    /**
     * Sets the query text.
     */
    public void setQueryText(String queryText) {
        this.queryText = queryText;
    }

    /**
     * Returns the requested limit.
     */
    public Integer getRequestedLimit() {
        return requestedLimit;
    }

    /**
     * Sets the requested limit.
     */
    public void setRequestedLimit(Integer requestedLimit) {
        this.requestedLimit = requestedLimit;
    }

    /**
     * Returns the retrieval mode.
     */
    public String getRetrievalMode() {
        return retrievalMode;
    }

    /**
     * Sets the retrieval mode.
     */
    public void setRetrievalMode(String retrievalMode) {
        this.retrievalMode = retrievalMode;
    }

    /**
     * Returns the hit count.
     */
    public Integer getHitCount() {
        return hitCount;
    }

    /**
     * Sets the hit count.
     */
    public void setHitCount(Integer hitCount) {
        this.hitCount = hitCount;
    }

    /**
     * Returns elapsed milliseconds.
     */
    public Long getElapsedMs() {
        return elapsedMs;
    }

    /**
     * Sets elapsed milliseconds.
     */
    public void setElapsedMs(Long elapsedMs) {
        this.elapsedMs = elapsedMs;
    }

    /**
     * Returns the logical endpoint.
     */
    public String getEndpoint() {
        return endpoint;
    }

    /**
     * Sets the logical endpoint.
     */
    public void setEndpoint(String endpoint) {
        this.endpoint = endpoint;
    }
}
