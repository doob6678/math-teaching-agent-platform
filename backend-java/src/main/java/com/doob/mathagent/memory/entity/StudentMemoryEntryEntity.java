package com.doob.mathagent.memory.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/**
 * MyBatis-Plus entity for student long/short term memory entries.
 */
@TableName("student_memory_entry")
public class StudentMemoryEntryEntity {

    /** Memory primary key. */
    @TableId
    private String memoryId;

    /** Tenant id used for school or organization isolation. */
    private String tenantId;

    /** Student id that owns private memory. Public memory may use creator subject id. */
    private String studentId;

    /** Memory scope: private or public. */
    private String memoryScope;

    /** Knowledge point label used for reuse matching. */
    private String knowledgePointName;

    /** Canonical question text used for similar question matching. */
    private String questionText;

    /** Reusable answer text. */
    private String answerText;

    /** Status: active, stale, archived, or blocked. */
    private String status;

    /** Metadata JSON for future prompt version, source task id, and expiry fields. */
    private String metadataJson;

    /**
     * Returns memory id.
     *
     * @return memory id
     */
    public String getMemoryId() {
        return memoryId;
    }

    /**
     * Sets memory id.
     *
     * @param memoryId memory id
     */
    public void setMemoryId(String memoryId) {
        this.memoryId = memoryId;
    }

    /**
     * Returns tenant id.
     *
     * @return tenant id
     */
    public String getTenantId() {
        return tenantId;
    }

    /**
     * Sets tenant id.
     *
     * @param tenantId tenant id
     */
    public void setTenantId(String tenantId) {
        this.tenantId = tenantId;
    }

    /**
     * Returns student id.
     *
     * @return student id
     */
    public String getStudentId() {
        return studentId;
    }

    /**
     * Sets student id.
     *
     * @param studentId student id
     */
    public void setStudentId(String studentId) {
        this.studentId = studentId;
    }

    /**
     * Returns memory scope.
     *
     * @return memory scope
     */
    public String getMemoryScope() {
        return memoryScope;
    }

    /**
     * Sets memory scope.
     *
     * @param memoryScope memory scope
     */
    public void setMemoryScope(String memoryScope) {
        this.memoryScope = memoryScope;
    }

    /**
     * Returns knowledge point name.
     *
     * @return knowledge point name
     */
    public String getKnowledgePointName() {
        return knowledgePointName;
    }

    /**
     * Sets knowledge point name.
     *
     * @param knowledgePointName knowledge point name
     */
    public void setKnowledgePointName(String knowledgePointName) {
        this.knowledgePointName = knowledgePointName;
    }

    /**
     * Returns question text.
     *
     * @return question text
     */
    public String getQuestionText() {
        return questionText;
    }

    /**
     * Sets question text.
     *
     * @param questionText question text
     */
    public void setQuestionText(String questionText) {
        this.questionText = questionText;
    }

    /**
     * Returns answer text.
     *
     * @return answer text
     */
    public String getAnswerText() {
        return answerText;
    }

    /**
     * Sets answer text.
     *
     * @param answerText answer text
     */
    public void setAnswerText(String answerText) {
        this.answerText = answerText;
    }

    /**
     * Returns status.
     *
     * @return status
     */
    public String getStatus() {
        return status;
    }

    /**
     * Sets status.
     *
     * @param status status
     */
    public void setStatus(String status) {
        this.status = status;
    }

    /**
     * Returns metadata JSON.
     *
     * @return metadata JSON
     */
    public String getMetadataJson() {
        return metadataJson;
    }

    /**
     * Sets metadata JSON.
     *
     * @param metadataJson metadata JSON
     */
    public void setMetadataJson(String metadataJson) {
        this.metadataJson = metadataJson;
    }
}
