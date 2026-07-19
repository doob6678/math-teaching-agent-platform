package com.doob.mathagent.memory.service;

import java.util.List;

/**
 * Store abstraction for student long/short term memory entries.
 */
public interface StudentMemoryStore {

    /**
     * Saves a memory entry.
     *
     * @param entry memory entry
     * @return saved memory entry
     */
    StudentMemoryEntry save(StudentMemoryEntry entry);

    /**
     * Lists reusable memory candidates for a student.
     *
     * @param tenantId tenant id
     * @param studentId student id
     * @return private owner memory plus public tenant memory
     */
    List<StudentMemoryEntry> candidates(String tenantId, String studentId);

    /**
     * Lists active memory entries across one tenant for teacher/admin global dashboards.
     *
     * @param tenantId tenant id
     * @return active tenant memory entries
     */
    default List<StudentMemoryEntry> tenantCandidates(String tenantId, int limit) {
        return candidates(tenantId, "").stream()
                .limit(Math.max(1, limit))
                .toList();
    }
}
