package com.doob.mathagent.learning.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

/** MyBatis row for an immutable student answer fact. */
@TableName("student_learning_attempt")
public class StudentLearningAttemptEntity {
    @TableId private String attemptId;
    private String tenantId; private String studentId; private String questionId; private String questionText;
    private String knowledgePointIdsJson; private Boolean correct; private Long responseTimeMs; private LocalDateTime submittedAt;
    public String getAttemptId(){return attemptId;} public void setAttemptId(String v){attemptId=v;}
    public String getTenantId(){return tenantId;} public void setTenantId(String v){tenantId=v;}
    public String getStudentId(){return studentId;} public void setStudentId(String v){studentId=v;}
    public String getQuestionId(){return questionId;} public void setQuestionId(String v){questionId=v;}
    public String getQuestionText(){return questionText;} public void setQuestionText(String v){questionText=v;}
    public String getKnowledgePointIdsJson(){return knowledgePointIdsJson;} public void setKnowledgePointIdsJson(String v){knowledgePointIdsJson=v;}
    public Boolean getCorrect(){return correct;} public void setCorrect(Boolean v){correct=v;}
    public Long getResponseTimeMs(){return responseTimeMs;} public void setResponseTimeMs(Long v){responseTimeMs=v;}
    public LocalDateTime getSubmittedAt(){return submittedAt;} public void setSubmittedAt(LocalDateTime v){submittedAt=v;}
}
