package com.doob.mathagent.memory.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.doob.mathagent.memory.entity.StudentMemoryEntryEntity;
import com.doob.mathagent.memory.mapper.StudentMemoryEntryMapper;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

/**
 * MyBatis-backed student memory store for durable reuse and dashboard aggregation.
 */
@Repository
@ConditionalOnProperty(prefix = "math-agent.database", name = "enabled", havingValue = "true", matchIfMissing = true)
public class MyBatisStudentMemoryStore implements StudentMemoryStore {

    private final StudentMemoryEntryMapper mapper;

    /**
     * Creates a durable student memory store.
     *
     * @param mapper MyBatis mapper for student memory entries
     */
    public MyBatisStudentMemoryStore(StudentMemoryEntryMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public StudentMemoryEntry save(StudentMemoryEntry entry) {
        StudentMemoryEntryEntity entity = toEntity(entry);
        mapper.insert(entity);
        return entry;
    }

    @Override
    public List<StudentMemoryEntry> candidates(String tenantId, String studentId) {
        return candidates(tenantId, studentId, MAX_REUSE_CANDIDATES);
    }

    /** Maximum recent rows inspected by lexical reuse; SQL applies the bound before materializing entities. */
    private static final int MAX_REUSE_CANDIDATES = 500;

    @Override
    public List<StudentMemoryEntry> candidates(String tenantId, String studentId, int limit) {
        int normalizedLimit = Math.max(1, Math.min(MAX_REUSE_CANDIDATES, limit));
        return mapper.selectPage(Page.of(1, normalizedLimit), new LambdaQueryWrapper<StudentMemoryEntryEntity>()
                        .eq(StudentMemoryEntryEntity::getTenantId, tenantId)
                        .eq(StudentMemoryEntryEntity::getStatus, "active")
                        .and(wrapper -> wrapper
                                .eq(StudentMemoryEntryEntity::getMemoryScope, "public")
                                .or()
                                .eq(StudentMemoryEntryEntity::getStudentId, studentId))
                        .orderByDesc(StudentMemoryEntryEntity::getCreatedAt)
                        .orderByDesc(StudentMemoryEntryEntity::getMemoryId))
                .getRecords().stream()
                .map(MyBatisStudentMemoryStore::toRecord)
                .toList();
    }

    @Override
    public List<StudentMemoryEntry> tenantCandidates(String tenantId, int limit) {
        int normalizedLimit = Math.max(1, Math.min(500, limit));
        return mapper.selectPage(
                        Page.of(1, normalizedLimit),
                        new LambdaQueryWrapper<StudentMemoryEntryEntity>()
                                .eq(StudentMemoryEntryEntity::getTenantId, tenantId)
                                .eq(StudentMemoryEntryEntity::getStatus, "active")
                                .orderByDesc(StudentMemoryEntryEntity::getCreatedAt)
                                .orderByDesc(StudentMemoryEntryEntity::getMemoryId))
                .getRecords()
                .stream()
                .map(MyBatisStudentMemoryStore::toRecord)
                .toList();
    }

    private static StudentMemoryEntryEntity toEntity(StudentMemoryEntry entry) {
        StudentMemoryEntryEntity entity = new StudentMemoryEntryEntity();
        entity.setMemoryId(entry.memoryId());
        entity.setTenantId(entry.tenantId());
        entity.setStudentId(entry.studentId());
        entity.setMemoryScope(entry.memoryScope());
        entity.setKnowledgePointName(entry.knowledgePointName());
        entity.setQuestionText(entry.questionText());
        entity.setAnswerText(entry.answerText());
        entity.setStatus(entry.status());
        entity.setMetadataJson("{}");
        entity.setCreatedAt(toLocalDateTime(entry.createdAt()));
        return entity;
    }

    private static StudentMemoryEntry toRecord(StudentMemoryEntryEntity entity) {
        return new StudentMemoryEntry(
                entity.getMemoryId(),
                entity.getTenantId(),
                entity.getStudentId(),
                entity.getMemoryScope(),
                entity.getKnowledgePointName(),
                entity.getQuestionText(),
                entity.getAnswerText(),
                entity.getStatus(),
                toInstant(entity.getCreatedAt()));
    }

    private static LocalDateTime toLocalDateTime(Instant value) {
        Instant normalized = value == null ? Instant.now() : value;
        return LocalDateTime.ofInstant(normalized, ZoneOffset.UTC);
    }

    private static Instant toInstant(LocalDateTime value) {
        LocalDateTime normalized = value == null ? LocalDateTime.now(ZoneOffset.UTC) : value;
        return normalized.toInstant(ZoneOffset.UTC);
    }
}
