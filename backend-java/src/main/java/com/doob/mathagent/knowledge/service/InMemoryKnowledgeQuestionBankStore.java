package com.doob.mathagent.knowledge.service;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

/**
 * In-memory knowledge and question bank store for local development and tests.
 */
@Repository
@ConditionalOnProperty(prefix = "math-agent.database", name = "enabled", havingValue = "false", matchIfMissing = true)
public class InMemoryKnowledgeQuestionBankStore implements KnowledgeQuestionBankStore {

    private final Map<String, KnowledgePointRecord> knowledgePoints = new ConcurrentHashMap<>();
    private final Map<String, QuestionBankItemRecord> questions = new ConcurrentHashMap<>();

    /**
     * Saves or replaces one knowledge point.
     */
    @Override
    public KnowledgePointRecord saveKnowledgePoint(KnowledgePointRecord record) {
        knowledgePoints.put(record.knowledgePointId(), record);
        return record;
    }

    /**
     * Saves or replaces one question item.
     */
    @Override
    public QuestionBankItemRecord saveQuestion(QuestionBankItemRecord record) {
        questions.put(record.questionId(), record);
        return record;
    }

    /**
     * Lists active knowledge points visible to the viewer.
     */
    @Override
    public List<KnowledgePointRecord> listKnowledgePoints(String tenantId, String viewerRole, String viewerSubjectId) {
        return knowledgePoints.values().stream()
                .filter(record -> tenantId.equals(record.tenantId()))
                .filter(record -> "active".equals(record.status()))
                .filter(record -> visible(record.permissionScope(), record.ownerSubjectId(), viewerRole, viewerSubjectId))
                .sorted(Comparator.comparing(KnowledgePointRecord::knowledgePointName))
                .toList();
    }

    /**
     * Searches active questions visible to the viewer.
     */
    @Override
    public List<QuestionBankItemRecord> searchQuestions(
            String tenantId,
            String viewerRole,
            String viewerSubjectId,
            String query,
            int limit) {
        String normalizedQuery = query == null ? "" : query.strip().toLowerCase();
        return questions.values().stream()
                .filter(record -> tenantId.equals(record.tenantId()))
                .filter(record -> "active".equals(record.status()))
                .filter(record -> visible(record.permissionScope(), record.ownerSubjectId(), viewerRole, viewerSubjectId))
                .filter(record -> matches(record, normalizedQuery))
                .sorted(Comparator.comparing(QuestionBankItemRecord::questionTitle))
                .limit(Math.max(1, Math.min(50, limit)))
                .toList();
    }

    /**
     * Returns whether one scoped row is visible to the backend viewer.
     */
    private static boolean visible(String permissionScope, String ownerSubjectId, String viewerRole, String viewerSubjectId) {
        if ("admin".equals(viewerRole)) {
            return true;
        }
        if ("TEACHER_PRIVATE".equals(permissionScope)) {
            return "teacher".equals(viewerRole) && viewerSubjectId != null && viewerSubjectId.equals(ownerSubjectId);
        }
        return "MATH_VIP".equals(permissionScope) || "PUBLIC_TEXTBOOK".equals(permissionScope);
    }

    /**
     * Matches a question against the normalized query text.
     */
    private static boolean matches(QuestionBankItemRecord record, String query) {
        if (query.isBlank()) {
            return true;
        }
        return contains(record.questionTitle(), query)
                || contains(record.questionText(), query)
                || record.knowledgePointIds().stream().anyMatch(id -> contains(id, query));
    }

    /**
     * Case-insensitive contains helper.
     */
    private static boolean contains(String value, String query) {
        return value != null && value.toLowerCase().contains(query);
    }
}
