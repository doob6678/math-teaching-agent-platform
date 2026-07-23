package com.doob.mathagent.learning;

import com.doob.mathagent.knowledge.service.KnowledgeQuestionBankService;
import com.doob.mathagent.knowledge.vo.QuestionBankItemResponse;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/** Orchestrates answer facts, mastery projection, weak-point diagnosis, and targeted question recommendation. */
@Service
public class StudentLearningLoopService {
    private final StudentLearningLoopStore store;
    private final KnowledgeQuestionBankService questionBankService;
    private final Clock clock;

    @Autowired
    public StudentLearningLoopService(StudentLearningLoopStore store, KnowledgeQuestionBankService questionBankService) {
        this(store, questionBankService, Clock.systemUTC());
    }

    StudentLearningLoopService(StudentLearningLoopStore store, KnowledgeQuestionBankService questionBankService, Clock clock) {
        this.store = Objects.requireNonNull(store);
        this.questionBankService = Objects.requireNonNull(questionBankService);
        this.clock = Objects.requireNonNull(clock);
    }

    /** Saves one real answer and recomputes every linked point from durable attempts. */
    public AttemptResult recordAttempt(String tenantId, String studentId, String questionId, String questionText,
            List<String> knowledgePointIds, boolean correct, long responseTimeMs) {
        return recordAttempt(tenantId, studentId, "student", questionId, questionText, knowledgePointIds, correct, responseTimeMs);
    }

    /**
     * Records an answer and resolves missing knowledge-point tags through the existing visible question-bank search.
     * That search already applies the local BGE/Rerank path when configured, so this method never introduces a second
     * model or a cloud call just to classify an answered question.
     */
    public AttemptResult recordAttempt(String tenantId, String studentId, String role, String questionId, String questionText,
            List<String> knowledgePointIds, boolean correct, long responseTimeMs) {
        String tenant = required(tenantId, "tenantId");
        String student = required(studentId, "studentId");
        List<String> points = normalizePoints(knowledgePointIds);
        if (points.isEmpty()) points = identifyKnowledgePoints(tenant, student, role, questionText);
        if (points.isEmpty()) throw new IllegalArgumentException("knowledgePointIds must contain at least one knowledge point");
        StudentLearningAttempt attempt = new StudentLearningAttempt(UUID.randomUUID().toString(), tenant, student,
                required(questionId, "questionId"), clean(questionText), points, correct, Math.max(0L, responseTimeMs), Instant.now(clock));
        store.saveAttempt(attempt);
        List<StudentKnowledgeMastery> updated = points.stream().map(point -> recompute(tenant, student, point)).toList();
        return new AttemptResult(attempt, updated, weakPoints(updated));
    }

    /** Returns one student's mastery ordered weak first. */
    public List<StudentKnowledgeMastery> mastery(String tenantId, String studentId) {
        return store.findMastery(required(tenantId, "tenantId"), required(studentId, "studentId"));
    }

    /** Uses the existing visible question-bank search and annotates each candidate with its weak point. */
    public List<RecommendedQuestion> recommendations(String tenantId, String studentId, String role, int limit) {
        List<StudentKnowledgeMastery> weak = weakPoints(mastery(tenantId, studentId));
        int boundedLimit = Math.max(1, Math.min(limit, 50));
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        List<RecommendedQuestion> result = new ArrayList<>();
        for (StudentKnowledgeMastery point : weak) {
            List<QuestionBankItemResponse> questions = questionBankService.searchQuestionsByKnowledgePoint(
                    tenantId, clean(role), studentId, point.knowledgePointId(), boundedLimit);
            for (QuestionBankItemResponse question : questions) {
                if (seen.add(question.questionId())) result.add(new RecommendedQuestion(question, point.knowledgePointId(), point.weaknessLevel()));
                if (result.size() >= boundedLimit) return List.copyOf(result);
            }
        }
        return List.copyOf(result);
    }

    /** Returns weak rows for one teacher-selected student or the whole tenant. */
    public List<StudentKnowledgeMastery> tenantWeakPoints(String tenantId, String studentId) {
        return weakPoints(store.findTenantMastery(required(tenantId, "tenantId"), clean(studentId)));
    }

    private StudentKnowledgeMastery recompute(String tenantId, String studentId, String point) {
        List<StudentLearningAttempt> values = store.findAttempts(tenantId, studentId, point);
        int correct = (int) values.stream().filter(StudentLearningAttempt::correct).count();
        int total = values.size();
        int incorrect = total - correct;
        int mastery = Math.round((correct + StudentLearningScoringPolicy.PRIOR_CORRECT) * 100.0f
                / (total + StudentLearningScoringPolicy.PRIOR_TOTAL));
        int weakness = StudentLearningScoringPolicy.weaknessLevel(mastery, incorrect, total);
        Instant last = values.isEmpty() ? null : values.get(0).submittedAt();
        return store.saveMastery(new StudentKnowledgeMastery(tenantId, studentId, point, mastery, total, correct, incorrect,
                weakness, last, "attempts=%d,correct=%d,incorrect=%d".formatted(total, correct, incorrect)));
    }

    /** Uses only source-backed question-bank tags; unmatched input is rejected instead of inventing a knowledge point. */
    private List<String> identifyKnowledgePoints(String tenantId, String studentId, String role, String questionText) {
        String query = clean(questionText);
        if (query.isBlank()) return List.of();
        return questionBankService.searchQuestions(tenantId, clean(role), studentId, query, 3).stream()
                .flatMap(item -> item.knowledgePointIds().stream()).filter(Objects::nonNull).map(String::strip)
                .filter(value -> !value.isBlank()).distinct().toList();
    }

    private static List<StudentKnowledgeMastery> weakPoints(List<StudentKnowledgeMastery> values) {
        return values.stream().filter(item -> item.weaknessLevel() > 0).toList();
    }
    private static List<String> normalizePoints(List<String> values) {
        if (values == null) return List.of();
        return values.stream().filter(Objects::nonNull).map(String::strip).filter(value -> !value.isBlank()).distinct().toList();
    }
    private static String required(String value, String name) {
        String normalized = clean(value);
        if (normalized.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
        return normalized;
    }
    private static String clean(String value) { return value == null ? "" : value.strip(); }

    public record AttemptResult(StudentLearningAttempt attempt, List<StudentKnowledgeMastery> updatedMastery,
            List<StudentKnowledgeMastery> weakPoints) { }
    public record RecommendedQuestion(QuestionBankItemResponse question, String knowledgePointId, int weaknessLevel) { }
}
