package com.doob.mathagent.knowledge.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/**
 * MyBatis entity for one question bank item.
 */
@TableName("question_bank_item")
public class QuestionBankItemEntity {

    /** Stable question id. */
    @TableId
    private String questionId;

    /** Tenant id for isolation. */
    private String tenantId;

    /** Creator subject id for private ownership. */
    private String ownerSubjectId;

    /** Permission scope such as TEACHER_PRIVATE or MATH_VIP. */
    private String permissionScope;

    /** Compact display title. */
    private String questionTitle;

    /** Full question text. */
    private String questionText;

    /** Structured answer JSON. */
    private String answerJson;

    /** Difficulty label. */
    private String difficulty;

    /** Row status. */
    private String status;

    /** Teacher resource document id that produced the imported question. */
    private String sourceResourceDocumentId;

    /** Parsed block id that produced the imported question. */
    private String sourceBlockId;

    /** Parsed block checksum captured when the question was imported. */
    private String sourceChecksum;

    public String getQuestionId() {
        return questionId;
    }

    public void setQuestionId(String questionId) {
        this.questionId = questionId;
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

    public String getQuestionTitle() {
        return questionTitle;
    }

    public void setQuestionTitle(String questionTitle) {
        this.questionTitle = questionTitle;
    }

    public String getQuestionText() {
        return questionText;
    }

    public void setQuestionText(String questionText) {
        this.questionText = questionText;
    }

    public String getAnswerJson() {
        return answerJson;
    }

    public void setAnswerJson(String answerJson) {
        this.answerJson = answerJson;
    }

    public String getDifficulty() {
        return difficulty;
    }

    public void setDifficulty(String difficulty) {
        this.difficulty = difficulty;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getSourceResourceDocumentId() {
        return sourceResourceDocumentId;
    }

    public void setSourceResourceDocumentId(String sourceResourceDocumentId) {
        this.sourceResourceDocumentId = sourceResourceDocumentId;
    }

    public String getSourceBlockId() {
        return sourceBlockId;
    }

    public void setSourceBlockId(String sourceBlockId) {
        this.sourceBlockId = sourceBlockId;
    }

    public String getSourceChecksum() {
        return sourceChecksum;
    }

    public void setSourceChecksum(String sourceChecksum) {
        this.sourceChecksum = sourceChecksum;
    }
}
