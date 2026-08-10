package com.doob.mathagent.student.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

/**
 * MyBatis entity for durable student explanation conversations.
 */
@TableName("student_explanation_session")
public class StudentExplanationSessionEntity {

    @TableId
    private String conversationId;
    private String tenantId;
    private String subjectType;
    private String subjectId;
    private String studentId;
    private String viewerRole;
    private String title;
    private String lastExplanationId;
    private String lastQuestionText;
    private Integer totalMessages;
    private String contextSummaryFromMessageId;
    private String contextSummaryToMessageId;
    private Integer contextSummaryVersion;
    private String contextSummaryHash;
    private String contextSummaryText;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public String getConversationId() {
        return conversationId;
    }

    public void setConversationId(String conversationId) {
        this.conversationId = conversationId;
    }

    public String getTenantId() {
        return tenantId;
    }

    public void setTenantId(String tenantId) {
        this.tenantId = tenantId;
    }

    public String getSubjectType() {
        return subjectType;
    }

    public void setSubjectType(String subjectType) {
        this.subjectType = subjectType;
    }

    public String getSubjectId() {
        return subjectId;
    }

    public void setSubjectId(String subjectId) {
        this.subjectId = subjectId;
    }

    public String getStudentId() {
        return studentId;
    }

    public void setStudentId(String studentId) {
        this.studentId = studentId;
    }

    public String getViewerRole() {
        return viewerRole;
    }

    public void setViewerRole(String viewerRole) {
        this.viewerRole = viewerRole;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getLastExplanationId() {
        return lastExplanationId;
    }

    public void setLastExplanationId(String lastExplanationId) {
        this.lastExplanationId = lastExplanationId;
    }

    public String getLastQuestionText() {
        return lastQuestionText;
    }

    public void setLastQuestionText(String lastQuestionText) {
        this.lastQuestionText = lastQuestionText;
    }

    public Integer getTotalMessages() {
        return totalMessages;
    }

    public void setTotalMessages(Integer totalMessages) {
        this.totalMessages = totalMessages;
    }

    public String getContextSummaryFromMessageId() {
        return contextSummaryFromMessageId;
    }

    public void setContextSummaryFromMessageId(String contextSummaryFromMessageId) {
        this.contextSummaryFromMessageId = contextSummaryFromMessageId;
    }

    public String getContextSummaryToMessageId() {
        return contextSummaryToMessageId;
    }

    public void setContextSummaryToMessageId(String contextSummaryToMessageId) {
        this.contextSummaryToMessageId = contextSummaryToMessageId;
    }

    public Integer getContextSummaryVersion() {
        return contextSummaryVersion;
    }

    public void setContextSummaryVersion(Integer contextSummaryVersion) {
        this.contextSummaryVersion = contextSummaryVersion;
    }

    public String getContextSummaryHash() {
        return contextSummaryHash;
    }

    public void setContextSummaryHash(String contextSummaryHash) {
        this.contextSummaryHash = contextSummaryHash;
    }

    public String getContextSummaryText() {
        return contextSummaryText;
    }

    public void setContextSummaryText(String contextSummaryText) {
        this.contextSummaryText = contextSummaryText;
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
