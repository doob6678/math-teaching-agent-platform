package com.doob.mathagent.learning.service;

import com.doob.mathagent.agent.service.PythonMigratedWorkloadClient;
import com.doob.mathagent.infrastructure.security.RequestSubject;
import com.doob.mathagent.knowledge.service.KnowledgeQuestionBankService;
import com.doob.mathagent.knowledge.vo.KnowledgePointResponse;
import com.doob.mathagent.learning.dto.StudentLearningIntentRequest;
import com.doob.mathagent.learning.vo.StudentLearningIntentResponse;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * 将学生自然语言路由到既有学习 API；模型执行仅由 Python Worker 完成。
 *
 * <p>Java 保留角色、租户可见知识点、固定 intent 路径和最终知识点实体校验。</p>
 */
@Service
public class StudentLearningIntentService {
    private static final Map<String, String> INTENT_APIS = Map.ofEntries(
            Map.entry("LEARNING_PATH", "/api/students/learning/path"),
            Map.entry("WRONG_QUESTION_REVIEW", "/api/students/learning/recommendations"),
            Map.entry("MASTERY_STATUS", "/api/students/learning/mastery"),
            Map.entry("TARGETED_EXPLANATION", "/api/students/learning/explanations"),
            Map.entry("TARGETED_PRACTICE", "/api/students/learning/practice"),
            Map.entry("QUESTION_RECOMMENDATION", "/api/students/learning/recommendations"),
            Map.entry("ANSWER_SUBMISSION", "/api/students/learning/attempts"));

    private final KnowledgeQuestionBankService knowledgeService;
    private final PythonMigratedWorkloadClient workloadClient;

    public StudentLearningIntentService(
            KnowledgeQuestionBankService knowledgeService,
            PythonMigratedWorkloadClient workloadClient) {
        this.knowledgeService = knowledgeService;
        this.workloadClient = workloadClient;
    }

    /** 调用已授权的 Python intent runtime，并复验模型结果不会越过可见知识点边界。 */
    public StudentLearningIntentResponse recognize(RequestSubject subject, StudentLearningIntentRequest request) {
        RequestSubject normalized = requireStudent(subject);
        String message = request == null || request.message() == null ? "" : request.message().strip();
        if (message.isBlank()) {
            throw new IllegalArgumentException("message is required");
        }
        List<KnowledgePointResponse> visiblePoints = knowledgeService.listKnowledgePoints(
                normalized.tenantId(), "student", normalized.subjectId());
        PythonMigratedWorkloadClient.IntentResult result = workloadClient.recognizeIntent(
                UUID.randomUUID().toString(),
                message,
                visiblePoints.stream()
                        .map(point -> new PythonMigratedWorkloadClient.KnowledgePoint(
                                point.knowledgePointId(), point.knowledgePointName()))
                        .toList());
        String intentCode = result.intentCode().strip().toUpperCase(java.util.Locale.ROOT);
        String api = INTENT_APIS.get(intentCode);
        if (api == null) {
            return unknown(result);
        }
        KnowledgePointResponse point = visiblePoints.stream()
                .filter(candidate -> candidate.knowledgePointId().equals(result.knowledgePointId()))
                .findFirst()
                .orElse(null);
        return new StudentLearningIntentResponse(
                intentCode,
                result.confidence(),
                point == null ? null : point.knowledgePointId(),
                point == null ? null : point.knowledgePointName(),
                api,
                "model_" + result.providerName() + ":" + result.modelCode());
    }

    private static StudentLearningIntentResponse unknown(PythonMigratedWorkloadClient.IntentResult result) {
        return new StudentLearningIntentResponse(
                "UNKNOWN", 0, null, null, null,
                "model_" + result.providerName() + ":" + result.modelCode());
    }

    private static RequestSubject requireStudent(RequestSubject subject) {
        RequestSubject normalized = subject == null
                ? RequestSubject.anonymous("default", "unknown-device") : subject.normalize();
        if (!"student".equals(normalized.subjectType()) || normalized.subjectId() == null) {
            throw new IllegalArgumentException("Student role required");
        }
        return normalized;
    }
}
