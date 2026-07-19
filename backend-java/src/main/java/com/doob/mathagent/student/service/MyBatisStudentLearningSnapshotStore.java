package com.doob.mathagent.student.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.doob.mathagent.student.entity.StudentLearningSnapshotEntity;
import com.doob.mathagent.student.mapper.StudentLearningSnapshotMapper;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

/**
 * MyBatis-backed store for latest student learning snapshots.
 */
@Repository
@ConditionalOnProperty(prefix = "math-agent.database", name = "enabled", havingValue = "true")
public class MyBatisStudentLearningSnapshotStore implements StudentLearningSnapshotStore {

    private final StudentLearningSnapshotMapper mapper;

    /**
     * Creates a MyBatis snapshot store.
     *
     * @param mapper student learning snapshot mapper
     */
    public MyBatisStudentLearningSnapshotStore(StudentLearningSnapshotMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * Finds the latest snapshot for one tenant and one student without cross-tenant leakage.
     */
    @Override
    public Optional<StudentLearningSnapshotRecord> findLatest(String tenantId, String studentId) {
        Page<StudentLearningSnapshotEntity> page = mapper.selectPage(
                Page.of(1, 1),
                new LambdaQueryWrapper<StudentLearningSnapshotEntity>()
                        .eq(StudentLearningSnapshotEntity::getTenantId, tenantId)
                        .eq(StudentLearningSnapshotEntity::getStudentId, studentId)
                        .orderByDesc(StudentLearningSnapshotEntity::getUpdatedAt)
                        .orderByDesc(StudentLearningSnapshotEntity::getSnapshotId));
        return page.getRecords().stream().findFirst().map(MyBatisStudentLearningSnapshotStore::toRecord);
    }

    /**
     * Inserts an immutable snapshot row for auditability and resume-friendly history.
     *
     * @param record snapshot record assembled by backend services
     * @return saved snapshot record
     */
    @Override
    public StudentLearningSnapshotRecord save(StudentLearningSnapshotRecord record) {
        StudentLearningSnapshotEntity entity = new StudentLearningSnapshotEntity();
        entity.setSnapshotId(record.snapshotId());
        entity.setTenantId(record.tenantId());
        entity.setStudentId(record.studentId());
        entity.setGradeName(record.gradeName());
        entity.setKnowledgeProgressJson(jsonOrEmptyArray(record.knowledgeProgressJson()));
        entity.setKnowledgeGraphJson(jsonOrDefaultGraph(record.knowledgeGraphJson(), record.sourceSummary()));
        entity.setWeakPointsJson(jsonOrEmptyArray(record.weakPointsJson()));
        entity.setRecentQuestionsJson(jsonOrEmptyArray(record.recentQuestionsJson()));
        entity.setScoreTrendJson(jsonOrEmptyArray(record.scoreTrendJson()));
        entity.setResourceScopesJson(jsonOrEmptyArray(record.resourceScopesJson()));
        entity.setSourceSummary(textOrDefault(record.sourceSummary(), "snapshot_refresh"));
        mapper.insert(entity);
        return record;
    }

    /**
     * Converts a database entity to a dashboard snapshot record.
     *
     * @param entity persisted snapshot entity
     * @return service-layer snapshot record
     */
    private static StudentLearningSnapshotRecord toRecord(StudentLearningSnapshotEntity entity) {
        return new StudentLearningSnapshotRecord(
                entity.getSnapshotId(),
                entity.getTenantId(),
                entity.getStudentId(),
                entity.getGradeName(),
                jsonOrEmptyArray(entity.getKnowledgeProgressJson()),
                jsonOrDefaultGraph(entity.getKnowledgeGraphJson(), entity.getSourceSummary()),
                jsonOrEmptyArray(entity.getWeakPointsJson()),
                jsonOrEmptyArray(entity.getRecentQuestionsJson()),
                jsonOrEmptyArray(entity.getScoreTrendJson()),
                jsonOrEmptyArray(entity.getResourceScopesJson()),
                textOrDefault(entity.getSourceSummary(), "mysql_snapshot"));
    }

    /**
     * Defaults blank JSON arrays.
     *
     * @param value persisted JSON value
     * @return JSON array string
     */
    private static String jsonOrEmptyArray(String value) {
        return value == null || value.isBlank() ? "[]" : value;
    }

    /**
     * Defaults blank knowledge graph JSON.
     *
     * @param value persisted graph JSON
     * @param sourceSummary graph source summary
     * @return JSON graph object
     */
    private static String jsonOrDefaultGraph(String value, String sourceSummary) {
        if (value != null && !value.isBlank()) {
            return value;
        }
        return "{\"nodes\":[],\"edges\":[],\"generatedFrom\":\""
                + textOrDefault(sourceSummary, "mysql_snapshot").replace("\"", "\\\"")
                + "\"}";
    }

    /**
     * Defaults blank text values.
     *
     * @param value input text
     * @param defaultValue fallback value
     * @return normalized text
     */
    private static String textOrDefault(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value;
    }
}
