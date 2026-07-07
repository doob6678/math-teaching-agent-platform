package com.doob.mathagent.knowledge.service;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory knowledge and question bank store for local development and tests.
 */
public class InMemoryKnowledgeQuestionBankStore implements KnowledgeQuestionBankStore {

    private final Map<String, KnowledgePointRecord> knowledgePoints = new ConcurrentHashMap<>();
    private final Map<String, KnowledgeRelationRecord> relations = new ConcurrentHashMap<>();
    private final Map<String, QuestionBankItemRecord> questions = new ConcurrentHashMap<>();

    @Override
    public KnowledgePointRecord saveKnowledgePoint(KnowledgePointRecord record) {
        knowledgePoints.put(record.knowledgePointId(), record);
        return record;
    }

    @Override
    public KnowledgeRelationRecord saveKnowledgeRelation(KnowledgeRelationRecord record) {
        relations.put(record.relationId(), record);
        return record;
    }

    @Override
    public Optional<KnowledgePointRecord> findKnowledgePoint(
            String tenantId,
            String ownerSubjectId,
            String permissionScope,
            String knowledgePointName,
            String chapterPath) {
        return knowledgePoints.values().stream()
                .filter(record -> tenantId.equals(record.tenantId()))
                .filter(record -> "active".equals(record.status()))
                .filter(record -> equalsText(ownerSubjectId, record.ownerSubjectId()))
                .filter(record -> equalsText(permissionScope, record.permissionScope()))
                .filter(record -> equalsText(knowledgePointName, record.knowledgePointName()))
                .filter(record -> equalsText(chapterPath, record.chapterPath()))
                .findFirst();
    }

    @Override
    public QuestionBankItemRecord saveQuestion(QuestionBankItemRecord record) {
        questions.put(record.questionId(), record);
        return record;
    }

    @Override
    public Optional<QuestionBankItemRecord> findQuestionBySource(
            String tenantId,
            String sourceResourceDocumentId,
            String sourceBlockId,
            String sourceChecksum) {
        return questions.values().stream()
                .filter(record -> tenantId.equals(record.tenantId()))
                .filter(record -> "active".equals(record.status()))
                .filter(record -> equalsText(sourceResourceDocumentId, record.sourceResourceDocumentId()))
                .filter(record -> equalsText(sourceBlockId, record.sourceBlockId()))
                .filter(record -> equalsText(sourceChecksum, record.sourceChecksum()))
                .findFirst();
    }

    @Override
    public int archiveQuestionsBySourceDocumentExcept(
            String tenantId,
            String sourceResourceDocumentId,
            Set<String> activeSourceKeys) {
        int archived = 0;
        for (QuestionBankItemRecord record : questions.values()) {
            if (!tenantId.equals(record.tenantId())
                    || !"active".equals(record.status())
                    || !equalsText(sourceResourceDocumentId, record.sourceResourceDocumentId())) {
                continue;
            }
            String sourceKey = sourceKey(record.sourceBlockId(), record.sourceChecksum());
            if (activeSourceKeys.contains(sourceKey)) {
                continue;
            }
            questions.put(record.questionId(), new QuestionBankItemRecord(
                    record.questionId(),
                    record.tenantId(),
                    record.ownerSubjectId(),
                    record.permissionScope(),
                    record.questionTitle(),
                    record.questionText(),
                    record.answerJson(),
                    record.difficulty(),
                    "archived",
                    record.sourceResourceDocumentId(),
                    record.sourceBlockId(),
                    record.sourceChecksum(),
                    record.knowledgePointIds()));
            archived += 1;
        }
        return archived;
    }

    @Override
    public List<KnowledgePointRecord> listKnowledgePoints(String tenantId, String viewerRole, String viewerSubjectId) {
        return knowledgePoints.values().stream()
                .filter(record -> tenantId.equals(record.tenantId()))
                .filter(record -> "active".equals(record.status()))
                .filter(record -> visible(record.permissionScope(), record.ownerSubjectId(), viewerRole, viewerSubjectId))
                .sorted(Comparator.comparing(KnowledgePointRecord::knowledgePointName))
                .toList();
    }

    @Override
    public List<KnowledgeRelationRecord> listKnowledgeRelations(String tenantId, String viewerRole, String viewerSubjectId) {
        Map<String, KnowledgePointRecord> visiblePoints = listKnowledgePoints(tenantId, viewerRole, viewerSubjectId)
                .stream()
                .collect(java.util.stream.Collectors.toMap(KnowledgePointRecord::knowledgePointId, record -> record));
        return relations.values().stream()
                .filter(record -> tenantId.equals(record.tenantId()))
                .filter(record -> "active".equals(record.status()))
                .filter(record -> visiblePoints.containsKey(record.sourceKnowledgePointId()))
                .filter(record -> visiblePoints.containsKey(record.targetKnowledgePointId()))
                .sorted(Comparator.comparing(KnowledgeRelationRecord::relationId))
                .toList();
    }

    @Override
    public List<QuestionBankItemRecord> searchQuestions(
            String tenantId,
            String viewerRole,
            String viewerSubjectId,
            String query,
            int limit) {
        String normalizedQuery = QuestionBankSearchText.normalize(query);
        List<String> keywords = QuestionBankSearchText.keywords(normalizedQuery);
        return questions.values().stream()
                .filter(record -> tenantId.equals(record.tenantId()))
                .filter(record -> "active".equals(record.status()))
                .filter(record -> visible(record.permissionScope(), record.ownerSubjectId(), viewerRole, viewerSubjectId))
                .filter(record -> matches(record, normalizedQuery, keywords))
                .sorted(Comparator.comparing(QuestionBankItemRecord::questionTitle))
                .limit(Math.max(1, Math.min(50, limit)))
                .toList();
    }

    private static boolean visible(String permissionScope, String ownerSubjectId, String viewerRole, String viewerSubjectId) {
        if ("admin".equals(viewerRole)) {
            return true;
        }
        if ("TEACHER_PRIVATE".equals(permissionScope)) {
            return "teacher".equals(viewerRole) && viewerSubjectId != null && viewerSubjectId.equals(ownerSubjectId);
        }
        return "MATH_VIP".equals(permissionScope) || "PUBLIC_TEXTBOOK".equals(permissionScope);
    }

    private static boolean matches(QuestionBankItemRecord record, String query, List<String> keywords) {
        if (query.isBlank()) {
            return true;
        }
        if (contains(record.questionTitle(), query)
                || contains(record.questionText(), query)
                || contains(record.sourceBlockId(), query)
                || record.knowledgePointIds().stream().anyMatch(id -> contains(id, query))) {
            return true;
        }
        return keywords.stream().anyMatch(keyword -> contains(record.questionTitle(), keyword)
                || contains(record.questionText(), keyword)
                || contains(record.sourceBlockId(), keyword)
                || record.knowledgePointIds().stream().anyMatch(id -> contains(id, keyword)));
    }

    private static boolean contains(String value, String query) {
        return value != null && value.toLowerCase().contains(query);
    }

    private static boolean equalsText(String expected, String actual) {
        String left = expected == null ? "" : expected;
        String right = actual == null ? "" : actual;
        return left.equals(right);
    }

    private static String sourceKey(String sourceBlockId, String sourceChecksum) {
        return (sourceBlockId == null ? "" : sourceBlockId) + "\n" + (sourceChecksum == null ? "" : sourceChecksum);
    }
}
