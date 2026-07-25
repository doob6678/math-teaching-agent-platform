package com.doob.mathagent.teaching.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.Instant;

/** MyBatis mapping for the append-only teaching_review_audit table. */
@TableName("teaching_review_audit")
public class TeachingReviewAuditEntity {
    @TableId private String reviewAuditId;
    private String taskId;
    private String tenantId;
    private String reviewerSubjectType;
    private String reviewerSubjectId;
    private String policyCode;
    private String decisionCode;
    private String reasonText;
    private String commonDraftHash;
    private String qualityStatus;
    private String teacherVersionHash;
    private String studentVersionHash;
    private String lectureVersionHash;
    private String publishedStatus;
    private Instant createdAt;
    public String getReviewAuditId() { return reviewAuditId; }
    public void setReviewAuditId(String value) { reviewAuditId = value; }
    public String getTaskId() { return taskId; }
    public void setTaskId(String value) { taskId = value; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String value) { tenantId = value; }
    public String getReviewerSubjectType() { return reviewerSubjectType; }
    public void setReviewerSubjectType(String value) { reviewerSubjectType = value; }
    public String getReviewerSubjectId() { return reviewerSubjectId; }
    public void setReviewerSubjectId(String value) { reviewerSubjectId = value; }
    public String getPolicyCode() { return policyCode; }
    public void setPolicyCode(String value) { policyCode = value; }
    public String getDecisionCode() { return decisionCode; }
    public void setDecisionCode(String value) { decisionCode = value; }
    public String getReasonText() { return reasonText; }
    public void setReasonText(String value) { reasonText = value; }
    public String getCommonDraftHash() { return commonDraftHash; }
    public void setCommonDraftHash(String value) { commonDraftHash = value; }
    public String getQualityStatus() { return qualityStatus; }
    public void setQualityStatus(String value) { qualityStatus = value; }
    public String getTeacherVersionHash() { return teacherVersionHash; }
    public void setTeacherVersionHash(String value) { teacherVersionHash = value; }
    public String getStudentVersionHash() { return studentVersionHash; }
    public void setStudentVersionHash(String value) { studentVersionHash = value; }
    public String getLectureVersionHash() { return lectureVersionHash; }
    public void setLectureVersionHash(String value) { lectureVersionHash = value; }
    public String getPublishedStatus() { return publishedStatus; }
    public void setPublishedStatus(String value) { publishedStatus = value; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant value) { createdAt = value; }
}
