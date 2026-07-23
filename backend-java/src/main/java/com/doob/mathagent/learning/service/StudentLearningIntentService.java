package com.doob.mathagent.learning.service;

import com.doob.mathagent.infrastructure.security.RequestSubject;
import com.doob.mathagent.agent.service.AiChatGateway;
import com.doob.mathagent.agent.service.AiChatRequest;
import com.doob.mathagent.agent.service.AiChatResult;
import com.doob.mathagent.infrastructure.ai.AiProviderCatalog;
import com.doob.mathagent.knowledge.service.KnowledgeQuestionBankService;
import com.doob.mathagent.knowledge.vo.KnowledgePointResponse;
import com.doob.mathagent.learning.dto.StudentLearningIntentRequest;
import com.doob.mathagent.learning.vo.StudentLearningIntentResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * Routes student language to existing learning APIs through the configured model gateway.
 *
 * <p>The model is limited to a machine-readable classification contract. The backend remains the authority for
 * allowed intent codes, API routes, tenant visibility, and the final knowledge-point entity.</p>
 */
@Service
public class StudentLearningIntentService {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final String AGENT_CODE = "StudentLearningIntentAgent";
    private static final Map<String, String> INTENT_APIS = Map.ofEntries(
            Map.entry("LEARNING_PATH", "/api/students/learning/path"),
            Map.entry("WRONG_QUESTION_REVIEW", "/api/students/learning/recommendations"),
            Map.entry("MASTERY_STATUS", "/api/students/learning/mastery"),
            Map.entry("TARGETED_EXPLANATION", "/api/students/learning/explanations"),
            Map.entry("TARGETED_PRACTICE", "/api/students/learning/practice"),
            Map.entry("QUESTION_RECOMMENDATION", "/api/students/learning/recommendations"),
            Map.entry("ANSWER_SUBMISSION", "/api/students/learning/attempts"));
    private final KnowledgeQuestionBankService knowledgeService;
    private final AiChatGateway aiChatGateway;
    private final AiProviderCatalog providerCatalog;

    public StudentLearningIntentService(
            KnowledgeQuestionBankService knowledgeService,
            AiChatGateway aiChatGateway,
            AiProviderCatalog providerCatalog) {
        this.knowledgeService = knowledgeService;
        this.aiChatGateway = aiChatGateway;
        this.providerCatalog = providerCatalog;
    }

    /** Calls the configured model and validates one authenticated student's structured intent result. */
    public StudentLearningIntentResponse recognize(RequestSubject subject, StudentLearningIntentRequest request) {
        RequestSubject normalized = requireStudent(subject);
        String message = request == null || request.message() == null ? "" : request.message().strip();
        if (message.isBlank()) {
            throw new IllegalArgumentException("message is required");
        }
        List<KnowledgePointResponse> visiblePoints = knowledgeService.listKnowledgePoints(
                normalized.tenantId(), "student", normalized.subjectId());
        AiProviderCatalog.Provider provider = providerCatalog.enabledProviders().stream()
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No AI provider is enabled for intent recognition"));
        AiChatResult result = aiChatGateway.call(new AiChatRequest(
                provider.name(), provider.chatModel(), AGENT_CODE,
                modelPrompt(message, visiblePoints),
                visiblePoints.stream()
                        .map(point -> "knowledge_point_id=" + point.knowledgePointId()
                                + ";name=" + point.knowledgePointName())
                        .toList()));
        JsonNode root = parseJson(result.generatedContent());
        String intentCode = root.path("intentCode").asText("").strip().toUpperCase(java.util.Locale.ROOT);
        String api = INTENT_APIS.get(intentCode);
        if (api == null) {
            return unknown(result);
        }
        double confidence = boundedConfidence(root.path("confidence").asDouble(0));
        KnowledgePointResponse point = visiblePoints.stream()
                .filter(candidate -> candidate.knowledgePointId().equals(root.path("knowledgePointId").asText("")))
                .findFirst()
                .orElse(null);
        return new StudentLearningIntentResponse(
                intentCode, confidence,
                point == null ? null : point.knowledgePointId(),
                point == null ? null : point.knowledgePointName(),
                api, "model_" + result.providerName() + ":" + result.modelCode());
    }

    private static String modelPrompt(String message, List<KnowledgePointResponse> visiblePoints) {
        return """
                你是学习系统的意图分类模型。请理解学生的自然语言，不要根据固定关键词表机械匹配。
                只能从下面的 intentCode 中选择一个，并且只能从可见知识点 ID 中选择 knowledgePointId。
                如果无法确定，返回 UNKNOWN；不要编造知识点 ID，不要回答问题。
                只返回一个 JSON 对象，不要 Markdown，不要解释：
                {"intentCode":"LEARNING_PATH|WRONG_QUESTION_REVIEW|MASTERY_STATUS|TARGETED_EXPLANATION|TARGETED_PRACTICE|QUESTION_RECOMMENDATION|ANSWER_SUBMISSION|UNKNOWN","confidence":0.0,"knowledgePointId":null}
                可见知识点：%s
                学生输入：%s
                """.formatted(visiblePoints.stream()
                .map(point -> point.knowledgePointId() + " / " + point.knowledgePointName())
                .toList(), message);
    }

    private static StudentLearningIntentResponse unknown(AiChatResult result) {
        return new StudentLearningIntentResponse(
                "UNKNOWN", 0, null, null, null,
                "model_" + result.providerName() + ":" + result.modelCode());
    }

    private static JsonNode parseJson(String content) {
        String value = content == null ? "" : content.strip();
        int start = value.indexOf('{');
        int end = value.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new IllegalArgumentException("Model intent response is not a JSON object");
        }
        try {
            return OBJECT_MAPPER.readTree(value.substring(start, end + 1));
        } catch (Exception exception) {
            throw new IllegalArgumentException("Model intent response JSON is invalid", exception);
        }
    }

    private static double boundedConfidence(double confidence) {
        if (Double.isNaN(confidence) || Double.isInfinite(confidence)) return 0;
        return Math.max(0, Math.min(1, confidence));
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
