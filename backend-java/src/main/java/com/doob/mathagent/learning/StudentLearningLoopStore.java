package com.doob.mathagent.learning;

import java.util.List;

/** Persistence boundary for immutable attempts and their derived mastery rows. */
public interface StudentLearningLoopStore {

    /** Saves one answer fact and returns the stored value. */
    StudentLearningAttempt saveAttempt(StudentLearningAttempt attempt);

    /** Lists attempts for one tenant-scoped student and knowledge point. */
    List<StudentLearningAttempt> findAttempts(String tenantId, String studentId, String knowledgePointId);

    /** Saves or updates the current mastery projection. */
    StudentKnowledgeMastery saveMastery(StudentKnowledgeMastery mastery);

    /** Lists mastery rows for one student. */
    List<StudentKnowledgeMastery> findMastery(String tenantId, String studentId);

    /** Lists mastery rows visible to a teacher/admin inside one tenant. */
    List<StudentKnowledgeMastery> findTenantMastery(String tenantId, String studentId);
}
