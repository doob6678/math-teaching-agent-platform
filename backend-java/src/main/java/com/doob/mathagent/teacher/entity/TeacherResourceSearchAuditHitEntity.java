package com.doob.mathagent.teacher.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;

/**
 * MyBatis-Plus entity mapped to teacher_resource_search_audit_hit.
 */
@TableName("teacher_resource_search_audit_hit")
public class TeacherResourceSearchAuditHitEntity {

    /** Database primary key. */
    @TableId
    private Long id;

    /** Query id that links the hit to its audit event. */
    private String queryId;

    /** One-based hit rank in returned order. */
    private Integer rankNo;

    /** Source document id without local filesystem path. */
    private String documentId;

    /** Source document display title. */
    private String documentTitle;

    /** Permission scope visible at query time. */
    private String permissionScope;

    /** Parsed block id. */
    private String blockId;

    /** Parsed block type. */
    private String blockType;

    /** Parsed block order in source document. */
    private Integer blockOrder;

    /** Source page number when available. */
    private Integer pageNo;

    /** Lexical score recorded for audit ranking. */
    private BigDecimal score;

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
     * Returns the hit rank.
     */
    public Integer getRankNo() {
        return rankNo;
    }

    /**
     * Sets the hit rank.
     */
    public void setRankNo(Integer rankNo) {
        this.rankNo = rankNo;
    }

    /**
     * Returns the source document id.
     */
    public String getDocumentId() {
        return documentId;
    }

    /**
     * Sets the source document id.
     */
    public void setDocumentId(String documentId) {
        this.documentId = documentId;
    }

    /**
     * Returns the source document title.
     */
    public String getDocumentTitle() {
        return documentTitle;
    }

    /**
     * Sets the source document title.
     */
    public void setDocumentTitle(String documentTitle) {
        this.documentTitle = documentTitle;
    }

    /**
     * Returns the permission scope.
     */
    public String getPermissionScope() {
        return permissionScope;
    }

    /**
     * Sets the permission scope.
     */
    public void setPermissionScope(String permissionScope) {
        this.permissionScope = permissionScope;
    }

    /**
     * Returns the parsed block id.
     */
    public String getBlockId() {
        return blockId;
    }

    /**
     * Sets the parsed block id.
     */
    public void setBlockId(String blockId) {
        this.blockId = blockId;
    }

    /**
     * Returns the parsed block type.
     */
    public String getBlockType() {
        return blockType;
    }

    /**
     * Sets the parsed block type.
     */
    public void setBlockType(String blockType) {
        this.blockType = blockType;
    }

    /**
     * Returns the block order.
     */
    public Integer getBlockOrder() {
        return blockOrder;
    }

    /**
     * Sets the block order.
     */
    public void setBlockOrder(Integer blockOrder) {
        this.blockOrder = blockOrder;
    }

    /**
     * Returns the page number.
     */
    public Integer getPageNo() {
        return pageNo;
    }

    /**
     * Sets the page number.
     */
    public void setPageNo(Integer pageNo) {
        this.pageNo = pageNo;
    }

    /**
     * Returns the score.
     */
    public BigDecimal getScore() {
        return score;
    }

    /**
     * Sets the score.
     */
    public void setScore(BigDecimal score) {
        this.score = score;
    }
}
