package com.doob.mathagent.knowledge.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/**
 * MyBatis entity linking questions to knowledge points.
 */
@TableName("question_knowledge_link")
public class QuestionKnowledgeLinkEntity {

    /** Stable link id. */
    @TableId
    private String linkId;

    /** Tenant id for isolation. */
    private String tenantId;

    /** Question id. */
    private String questionId;

    /** Knowledge point id. */
    private String knowledgePointId;

    /** Link confidence when inferred by a parser or model. */
    private Double confidence;

    /** Bind type such as manual or inferred. */
    private String bindType;

    /** Row status. */
    private String status;

    public String getLinkId() {
        return linkId;
    }

    public void setLinkId(String linkId) {
        this.linkId = linkId;
    }

    public String getTenantId() {
        return tenantId;
    }

    public void setTenantId(String tenantId) {
        this.tenantId = tenantId;
    }

    public String getQuestionId() {
        return questionId;
    }

    public void setQuestionId(String questionId) {
        this.questionId = questionId;
    }

    public String getKnowledgePointId() {
        return knowledgePointId;
    }

    public void setKnowledgePointId(String knowledgePointId) {
        this.knowledgePointId = knowledgePointId;
    }

    public Double getConfidence() {
        return confidence;
    }

    public void setConfidence(Double confidence) {
        this.confidence = confidence;
    }

    public String getBindType() {
        return bindType;
    }

    public void setBindType(String bindType) {
        this.bindType = bindType;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }
}
