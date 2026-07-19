package com.doob.mathagent.student.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

/**
 * MyBatis entity for one persisted explanation result.
 */
@TableName("student_explanation_message")
public class StudentExplanationMessageEntity {

    @TableId
    private String explanationId;
    private String conversationId;
    private String tenantId;
    private String subjectType;
    private String subjectId;
    private String studentId;
    private String viewerRole;
    private String questionText;
    private String imageUploadId;
    private String imageStatus;
    private String imageProblemText;
    private String aiProviderName;
    private String aiModelCode;
    private Integer promptTokens;
    private Integer completionTokens;
    private Integer totalTokens;
    private Long totalElapsedMs;
    private String requestJson;
    private String imageUnderstandingJson;
    private String aiDraftJson;
    private String workflowStagesJson;
    private String cardsJson;
    private String sourcesJson;
    private LocalDateTime createdAt;

    public String getExplanationId() {
        return explanationId;
    }

    public void setExplanationId(String explanationId) {
        this.explanationId = explanationId;
    }

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

    public String getQuestionText() {
        return questionText;
    }

    public void setQuestionText(String questionText) {
        this.questionText = questionText;
    }

    public String getImageUploadId() {
        return imageUploadId;
    }

    public void setImageUploadId(String imageUploadId) {
        this.imageUploadId = imageUploadId;
    }

    public String getImageStatus() {
        return imageStatus;
    }

    public void setImageStatus(String imageStatus) {
        this.imageStatus = imageStatus;
    }

    public String getImageProblemText() {
        return imageProblemText;
    }

    public void setImageProblemText(String imageProblemText) {
        this.imageProblemText = imageProblemText;
    }

    public String getAiProviderName() {
        return aiProviderName;
    }

    public void setAiProviderName(String aiProviderName) {
        this.aiProviderName = aiProviderName;
    }

    public String getAiModelCode() {
        return aiModelCode;
    }

    public void setAiModelCode(String aiModelCode) {
        this.aiModelCode = aiModelCode;
    }

    public Integer getPromptTokens() {
        return promptTokens;
    }

    public void setPromptTokens(Integer promptTokens) {
        this.promptTokens = promptTokens;
    }

    public Integer getCompletionTokens() {
        return completionTokens;
    }

    public void setCompletionTokens(Integer completionTokens) {
        this.completionTokens = completionTokens;
    }

    public Integer getTotalTokens() {
        return totalTokens;
    }

    public void setTotalTokens(Integer totalTokens) {
        this.totalTokens = totalTokens;
    }

    public Long getTotalElapsedMs() {
        return totalElapsedMs;
    }

    public void setTotalElapsedMs(Long totalElapsedMs) {
        this.totalElapsedMs = totalElapsedMs;
    }

    public String getRequestJson() {
        return requestJson;
    }

    public void setRequestJson(String requestJson) {
        this.requestJson = requestJson;
    }

    public String getImageUnderstandingJson() {
        return imageUnderstandingJson;
    }

    public void setImageUnderstandingJson(String imageUnderstandingJson) {
        this.imageUnderstandingJson = imageUnderstandingJson;
    }

    public String getAiDraftJson() {
        return aiDraftJson;
    }

    public void setAiDraftJson(String aiDraftJson) {
        this.aiDraftJson = aiDraftJson;
    }

    public String getWorkflowStagesJson() {
        return workflowStagesJson;
    }

    public void setWorkflowStagesJson(String workflowStagesJson) {
        this.workflowStagesJson = workflowStagesJson;
    }

    public String getCardsJson() {
        return cardsJson;
    }

    public void setCardsJson(String cardsJson) {
        this.cardsJson = cardsJson;
    }

    public String getSourcesJson() {
        return sourcesJson;
    }

    public void setSourcesJson(String sourcesJson) {
        this.sourcesJson = sourcesJson;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
