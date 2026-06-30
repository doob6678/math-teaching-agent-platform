package com.doob.mathagent.knowledge.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/**
 * MyBatis entity for a directed knowledge point relation.
 */
@TableName("knowledge_relation")
public class KnowledgeRelationEntity {

    /** Stable relation id. */
    @TableId
    private String relationId;

    /** Tenant id for isolation. */
    private String tenantId;

    /** Source knowledge point id. */
    private String sourceKnowledgePointId;

    /** Target knowledge point id. */
    private String targetKnowledgePointId;

    /** Relation type such as PREREQUISITE_FOR or RELATED_TO. */
    private String relationType;

    /** Human-reviewed or imported evidence summary for the relation. */
    private String evidenceSummary;

    /** Row status. */
    private String status;

    public String getRelationId() {
        return relationId;
    }

    public void setRelationId(String relationId) {
        this.relationId = relationId;
    }

    public String getTenantId() {
        return tenantId;
    }

    public void setTenantId(String tenantId) {
        this.tenantId = tenantId;
    }

    public String getSourceKnowledgePointId() {
        return sourceKnowledgePointId;
    }

    public void setSourceKnowledgePointId(String sourceKnowledgePointId) {
        this.sourceKnowledgePointId = sourceKnowledgePointId;
    }

    public String getTargetKnowledgePointId() {
        return targetKnowledgePointId;
    }

    public void setTargetKnowledgePointId(String targetKnowledgePointId) {
        this.targetKnowledgePointId = targetKnowledgePointId;
    }

    public String getRelationType() {
        return relationType;
    }

    public void setRelationType(String relationType) {
        this.relationType = relationType;
    }

    public String getEvidenceSummary() {
        return evidenceSummary;
    }

    public void setEvidenceSummary(String evidenceSummary) {
        this.evidenceSummary = evidenceSummary;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }
}
