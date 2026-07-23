package com.doob.mathagent.learning.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

/** MyBatis row for the derived mastery projection. */
@TableName("student_knowledge_mastery")
public class StudentKnowledgeMasteryEntity {
    private String tenantId; private String studentId; private String knowledgePointId; private Integer masteryPercent;
    private Integer attemptCount; private Integer correctCount; private Integer incorrectCount; private Integer weaknessLevel;
    private LocalDateTime lastAttemptAt; private String evidenceSummary;
    public String getTenantId(){return tenantId;} public void setTenantId(String v){tenantId=v;}
    public String getStudentId(){return studentId;} public void setStudentId(String v){studentId=v;}
    public String getKnowledgePointId(){return knowledgePointId;} public void setKnowledgePointId(String v){knowledgePointId=v;}
    public Integer getMasteryPercent(){return masteryPercent;} public void setMasteryPercent(Integer v){masteryPercent=v;}
    public Integer getAttemptCount(){return attemptCount;} public void setAttemptCount(Integer v){attemptCount=v;}
    public Integer getCorrectCount(){return correctCount;} public void setCorrectCount(Integer v){correctCount=v;}
    public Integer getIncorrectCount(){return incorrectCount;} public void setIncorrectCount(Integer v){incorrectCount=v;}
    public Integer getWeaknessLevel(){return weaknessLevel;} public void setWeaknessLevel(Integer v){weaknessLevel=v;}
    public LocalDateTime getLastAttemptAt(){return lastAttemptAt;} public void setLastAttemptAt(LocalDateTime v){lastAttemptAt=v;}
    public String getEvidenceSummary(){return evidenceSummary;} public void setEvidenceSummary(String v){evidenceSummary=v;}
}
