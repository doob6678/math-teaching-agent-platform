package com.doob.mathagent.learning;

import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

/** Thread-safe local store used when MySQL is disabled; production uses the MyBatis store. */
@Repository
@ConditionalOnProperty(prefix = "math-agent.database", name = "enabled", havingValue = "false", matchIfMissing = true)
public class InMemoryStudentLearningLoopStore implements StudentLearningLoopStore {
    private final ConcurrentHashMap<String, CopyOnWriteArrayList<StudentLearningAttempt>> attempts = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, StudentKnowledgeMastery> mastery = new ConcurrentHashMap<>();

    @Override
    public StudentLearningAttempt saveAttempt(StudentLearningAttempt attempt) {
        attempts.computeIfAbsent(studentKey(attempt.tenantId(), attempt.studentId()), ignored -> new CopyOnWriteArrayList<>()).add(attempt);
        return attempt;
    }

    @Override
    public List<StudentLearningAttempt> findAttempts(String tenantId, String studentId, String knowledgePointId) {
        return attempts.getOrDefault(studentKey(tenantId, studentId), new CopyOnWriteArrayList<>()).stream()
                .filter(item -> item.knowledgePointIds().contains(knowledgePointId))
                .sorted(Comparator.comparing(StudentLearningAttempt::submittedAt).reversed())
                .toList();
    }

    @Override
    public StudentKnowledgeMastery saveMastery(StudentKnowledgeMastery value) {
        mastery.put(masteryKey(value.tenantId(), value.studentId(), value.knowledgePointId()), value);
        return value;
    }

    @Override
    public List<StudentKnowledgeMastery> findMastery(String tenantId, String studentId) {
        return mastery.values().stream()
                .filter(item -> tenantId.equals(item.tenantId()) && studentId.equals(item.studentId()))
                .sorted(Comparator.comparingInt(StudentKnowledgeMastery::masteryPercent))
                .toList();
    }

    @Override
    public List<StudentKnowledgeMastery> findTenantMastery(String tenantId, String studentId) {
        return mastery.values().stream()
                .filter(item -> tenantId.equals(item.tenantId()))
                .filter(item -> studentId == null || studentId.isBlank() || studentId.equals(item.studentId()))
                .sorted(Comparator.comparingInt(StudentKnowledgeMastery::masteryPercent))
                .toList();
    }

    private static String studentKey(String tenantId, String studentId) { return tenantId + "\u0000" + studentId; }
    private static String masteryKey(String tenantId, String studentId, String point) { return studentKey(tenantId, studentId) + "\u0000" + point; }
}
