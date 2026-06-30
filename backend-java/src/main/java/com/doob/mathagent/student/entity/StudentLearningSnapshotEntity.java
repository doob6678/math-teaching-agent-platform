package com.doob.mathagent.student.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/**
 * MyBatis-Plus entity for a student's dashboard snapshot.
 */
@TableName("student_learning_snapshot")
public class StudentLearningSnapshotEntity {

    /** Snapshot id. */
    @TableId
    private String snapshotId;

    /** Tenant id used for data isolation. */
    private String tenantId;

    /** Student id that owns the snapshot. */
    private String studentId;

    /** Grade name, for example 高一 or 高二. */
    private String gradeName;

    /** JSON payload for knowledge progress graph. */
    private String knowledgeProgressJson;

    /** JSON payload for the visible knowledge graph nodes, edges, and evidence links. */
    private String knowledgeGraphJson;

    /** JSON payload for weak points extracted from questions and exams. */
    private String weakPointsJson;

    /** JSON payload for recoverable recent question records. */
    private String recentQuestionsJson;

    /** JSON payload for score trend chart. */
    private String scoreTrendJson;

    /** JSON payload for resource scopes visible to this student. */
    private String resourceScopesJson;

    /** Source summary describing how the snapshot was assembled. */
    private String sourceSummary;

    /**
     * Returns the snapshot id.
     *
     * @return snapshot id
     */
    public String getSnapshotId() {
        return snapshotId;
    }

    /**
     * Sets the snapshot id.
     *
     * @param snapshotId snapshot id
     */
    public void setSnapshotId(String snapshotId) {
        this.snapshotId = snapshotId;
    }

    /**
     * Returns the tenant id.
     *
     * @return tenant id
     */
    public String getTenantId() {
        return tenantId;
    }

    /**
     * Sets the tenant id.
     *
     * @param tenantId tenant id
     */
    public void setTenantId(String tenantId) {
        this.tenantId = tenantId;
    }

    /**
     * Returns the student id.
     *
     * @return student id
     */
    public String getStudentId() {
        return studentId;
    }

    /**
     * Sets the student id.
     *
     * @param studentId student id
     */
    public void setStudentId(String studentId) {
        this.studentId = studentId;
    }

    /**
     * Returns the grade name.
     *
     * @return grade name
     */
    public String getGradeName() {
        return gradeName;
    }

    /**
     * Sets the grade name.
     *
     * @param gradeName grade name
     */
    public void setGradeName(String gradeName) {
        this.gradeName = gradeName;
    }

    /**
     * Returns the knowledge progress JSON.
     *
     * @return knowledge progress JSON
     */
    public String getKnowledgeProgressJson() {
        return knowledgeProgressJson;
    }

    /**
     * Sets the knowledge progress JSON.
     *
     * @param knowledgeProgressJson knowledge progress JSON
     */
    public void setKnowledgeProgressJson(String knowledgeProgressJson) {
        this.knowledgeProgressJson = knowledgeProgressJson;
    }

    /**
     * Returns the knowledge graph JSON.
     *
     * @return knowledge graph JSON
     */
    public String getKnowledgeGraphJson() {
        return knowledgeGraphJson;
    }

    /**
     * Sets the knowledge graph JSON.
     *
     * @param knowledgeGraphJson knowledge graph JSON
     */
    public void setKnowledgeGraphJson(String knowledgeGraphJson) {
        this.knowledgeGraphJson = knowledgeGraphJson;
    }

    /**
     * Returns the weak points JSON.
     *
     * @return weak points JSON
     */
    public String getWeakPointsJson() {
        return weakPointsJson;
    }

    /**
     * Sets the weak points JSON.
     *
     * @param weakPointsJson weak points JSON
     */
    public void setWeakPointsJson(String weakPointsJson) {
        this.weakPointsJson = weakPointsJson;
    }

    /**
     * Returns recent question JSON.
     *
     * @return recent question JSON
     */
    public String getRecentQuestionsJson() {
        return recentQuestionsJson;
    }

    /**
     * Sets recent question JSON.
     *
     * @param recentQuestionsJson recent question JSON
     */
    public void setRecentQuestionsJson(String recentQuestionsJson) {
        this.recentQuestionsJson = recentQuestionsJson;
    }

    /**
     * Returns the score trend JSON.
     *
     * @return score trend JSON
     */
    public String getScoreTrendJson() {
        return scoreTrendJson;
    }

    /**
     * Sets the score trend JSON.
     *
     * @param scoreTrendJson score trend JSON
     */
    public void setScoreTrendJson(String scoreTrendJson) {
        this.scoreTrendJson = scoreTrendJson;
    }

    /**
     * Returns resource scope JSON.
     *
     * @return resource scope JSON
     */
    public String getResourceScopesJson() {
        return resourceScopesJson;
    }

    /**
     * Sets resource scope JSON.
     *
     * @param resourceScopesJson resource scope JSON
     */
    public void setResourceScopesJson(String resourceScopesJson) {
        this.resourceScopesJson = resourceScopesJson;
    }

    /**
     * Returns the source summary.
     *
     * @return source summary
     */
    public String getSourceSummary() {
        return sourceSummary;
    }

    /**
     * Sets the source summary.
     *
     * @param sourceSummary source summary
     */
    public void setSourceSummary(String sourceSummary) {
        this.sourceSummary = sourceSummary;
    }
}
