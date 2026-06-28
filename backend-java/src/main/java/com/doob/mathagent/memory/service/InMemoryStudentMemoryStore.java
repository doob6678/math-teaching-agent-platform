package com.doob.mathagent.memory.service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

/**
 * In-memory student memory store for local development and tests.
 */
@Repository
@ConditionalOnProperty(prefix = "math-agent.database", name = "enabled", havingValue = "false", matchIfMissing = true)
public class InMemoryStudentMemoryStore implements StudentMemoryStore {

    /** Memory entries keyed by memory id. */
    private final Map<String, StudentMemoryEntry> entries = new ConcurrentHashMap<>();

    /**
     * Saves a memory entry.
     *
     * @param entry memory entry
     * @return saved memory entry
     */
    @Override
    public StudentMemoryEntry save(StudentMemoryEntry entry) {
        entries.put(entry.memoryId(), entry);
        return entry;
    }

    /**
     * Returns private owner entries and public tenant entries.
     *
     * @param tenantId tenant id
     * @param studentId student id
     * @return reusable candidates
     */
    @Override
    public List<StudentMemoryEntry> candidates(String tenantId, String studentId) {
        return entries.values().stream()
                .filter(entry -> tenantId.equals(entry.tenantId()))
                .filter(entry -> "active".equals(entry.status()))
                .filter(entry -> "public".equals(entry.memoryScope()) || studentId.equals(entry.studentId()))
                .toList();
    }
}
