package com.doob.mathagent.memory.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
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
        return mapper.selectList(new LambdaQueryWrapper<StudentMemoryEntryEntity>()
                        .eq(StudentMemoryEntryEntity::getTenantId, tenantId)
                        .eq(StudentMemoryEntryEntity::getStatus, "active")
                        .and(wrapper -> wrapper
                                .eq(StudentMemoryEntryEntity::getMemoryScope, "public")
                                .or()
                                .eq(StudentMemoryEntryEntity::getStudentId, studentId))
                        .orderByDesc(StudentMemoryEntryEntity::getCreatedAt)
                        .orderByDesc(StudentMemoryEntryEntity::getMemoryId))
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
