package com.doob.mathagent.learning.service;

import com.doob.mathagent.infrastructure.security.RequestSubject;
import com.doob.mathagent.knowledge.service.KnowledgeQuestionBankService;
import com.doob.mathagent.knowledge.vo.KnowledgePointResponse;
import com.doob.mathagent.knowledge.vo.KnowledgeRelationResponse;
import com.doob.mathagent.learning.StudentKnowledgeMastery;
import com.doob.mathagent.learning.StudentLearningLoopService;
import com.doob.mathagent.learning.vo.StudentLearningPathResponse;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;

/** Builds a bounded prerequisite-first path without letting a model invent curriculum facts. */
@Service
public class StudentLearningPathService {
    private static final int MAX_STEPS = 20;
    private final StudentLearningLoopService learningLoopService;
    private final KnowledgeQuestionBankService knowledgeService;

    public StudentLearningPathService(StudentLearningLoopService learningLoopService, KnowledgeQuestionBankService knowledgeService) {
        this.learningLoopService = learningLoopService;
        this.knowledgeService = knowledgeService;
    }

    public StudentLearningPathResponse build(RequestSubject subject) {
        RequestSubject normalized = requireStudent(subject);
        List<StudentKnowledgeMastery> allMastery = learningLoopService
                .tenantWeakPoints(normalized.tenantId(), normalized.subjectId());
        List<StudentKnowledgeMastery> weakPoints = allMastery.stream()
                .filter(item -> item.weaknessLevel() > 0)
                .sorted(Comparator.comparingInt(StudentKnowledgeMastery::weaknessLevel).reversed()
                        .thenComparingInt(StudentKnowledgeMastery::masteryPercent)
                        .thenComparing(StudentKnowledgeMastery::knowledgePointId))
                .toList();
        List<KnowledgePointResponse> points = knowledgeService.listKnowledgePoints(
                normalized.tenantId(), "student", normalized.subjectId());
        List<KnowledgeRelationResponse> relations = knowledgeService.listKnowledgeRelations(
                normalized.tenantId(), "student", normalized.subjectId());
        Map<String, KnowledgePointResponse> pointById = points.stream()
                .collect(java.util.stream.Collectors.toMap(KnowledgePointResponse::knowledgePointId, item -> item));
        Map<String, StudentKnowledgeMastery> masteryById = new HashMap<>();
        allMastery.forEach(item -> masteryById.put(item.knowledgePointId(), item));
        Map<String, List<String>> prerequisites = new HashMap<>();
        for (KnowledgeRelationResponse relation : relations) {
            if ("PREREQUISITE_FOR".equalsIgnoreCase(relation.relationType())
                    && pointById.containsKey(relation.sourceKnowledgePointId())
                    && pointById.containsKey(relation.targetKnowledgePointId())) {
                prerequisites.computeIfAbsent(relation.targetKnowledgePointId(), ignored -> new ArrayList<>())
                        .add(relation.sourceKnowledgePointId());
            }
        }
        LinkedHashSet<String> orderedIds = new LinkedHashSet<>();
        for (StudentKnowledgeMastery weakPoint : weakPoints) {
            addPrerequisitesFirst(weakPoint.knowledgePointId(), prerequisites, orderedIds, new HashSet<>());
            if (orderedIds.size() >= MAX_STEPS) break;
        }
        List<String> boundedIds = orderedIds.stream().limit(MAX_STEPS).toList();
        List<StudentLearningPathResponse.Step> steps = new ArrayList<>();
        for (int index = 0; index < boundedIds.size(); index++) {
            String pointId = boundedIds.get(index);
            KnowledgePointResponse point = pointById.get(pointId);
            StudentKnowledgeMastery mastery = masteryById.get(pointId);
            String nextRelation = index + 1 < boundedIds.size()
                    && prerequisites.getOrDefault(boundedIds.get(index + 1), List.of()).contains(pointId)
                    ? "PREREQUISITE_FOR" : "WEAK_POINT_ORDER";
            steps.add(new StudentLearningPathResponse.Step(
                    pointId, point == null ? pointId : point.knowledgePointName(),
                    mastery == null ? 100 : mastery.masteryPercent(), mastery == null ? 0 : mastery.weaknessLevel(),
                    nextRelation, recommendation(mastery)));
        }
        return new StudentLearningPathResponse(normalized.subjectId(), List.copyOf(steps),
                "student_knowledge_mastery+visible_PREREQUISITE_FOR");
    }

    private static void addPrerequisitesFirst(
            String pointId, Map<String, List<String>> prerequisites, LinkedHashSet<String> ordered, Set<String> visiting) {
        if (pointId == null || ordered.size() >= MAX_STEPS || !visiting.add(pointId)) return;
        for (String prerequisite : prerequisites.getOrDefault(pointId, List.of()).stream().sorted().toList()) {
            addPrerequisitesFirst(prerequisite, prerequisites, ordered, visiting);
            if (ordered.size() >= MAX_STEPS) return;
        }
        visiting.remove(pointId);
        ordered.add(pointId);
    }

    private static String recommendation(StudentKnowledgeMastery mastery) {
        if (mastery == null) return "先完成一题基础练习，再进入当前知识点。";
        if (mastery.weaknessLevel() >= 4) return "先看教材证据和基础题，再做专项变式。";
        if (mastery.weaknessLevel() >= 2) return "完成一组中等难度变式并复盘错误步骤。";
        return "用综合题检验迁移能力。";
    }

    private static RequestSubject requireStudent(RequestSubject subject) {
        RequestSubject normalized = subject == null ? RequestSubject.anonymous("default", "unknown-device") : subject.normalize();
        if (!"student".equals(normalized.subjectType()) || normalized.subjectId() == null) {
            throw new IllegalArgumentException("Student role required");
        }
        return normalized;
    }
}
