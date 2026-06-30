package com.doob.mathagent.teaching.service;

import com.doob.mathagent.agent.service.AiChatGateway;
import com.doob.mathagent.agent.service.AiChatRequest;
import com.doob.mathagent.agent.service.AiChatResult;
import com.doob.mathagent.infrastructure.ai.AiProviderCatalog;
import com.doob.mathagent.memory.vo.StudentMemoryResponse;
import com.doob.mathagent.teaching.TeachingEvidence;
import com.doob.mathagent.teaching.dto.TeachingTaskRequest;
import com.doob.mathagent.teaching.vo.TeachingTaskResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * AI drafting service for the teaching DAG.
 */
@Service
public class TeachingAiDraftService {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final AiChatGateway aiChatGateway;
    private final AiProviderCatalog providerCatalog;
    private final TeachingAiDraftProperties aiDraftProperties;

    /**
     * Creates the teaching AI draft service.
     *
     * @param aiChatGateway real model gateway
     * @param providerCatalog enabled provider catalog
     * @param aiDraftProperties runtime retry policy
     */
    public TeachingAiDraftService(
            AiChatGateway aiChatGateway,
            AiProviderCatalog providerCatalog,
            TeachingAiDraftProperties aiDraftProperties) {
        this.aiChatGateway = aiChatGateway;
        this.providerCatalog = providerCatalog;
        this.aiDraftProperties = aiDraftProperties;
    }

    /**
     * Calls enabled providers in backend fallback order and returns usable teaching content.
     *
     * @param request teaching task request
     * @param evidence retrieved evidence
     * @param memoryResponse student memory reuse decision
     * @return AI draft metadata and content
     */
    public TeachingTaskResponse.AiDraft draft(
            TeachingTaskRequest request,
            List<TeachingEvidence> evidence,
            StudentMemoryResponse memoryResponse) {
        List<AiProviderCatalog.Provider> providers = providerCatalog.enabledProviders();
        if (providers.isEmpty()) {
            return new TeachingTaskResponse.AiDraft(false, "", "", 0, 0, 0, "", "No enabled AI provider.");
        }
        RuntimeException lastFailure = null;
        TeachingTaskResponse.AiDraft lastUnstructuredDraft = null;
        List<TeachingTaskResponse.AiRecoveryEvent> recoveryEvents = new ArrayList<>();
        int totalPromptTokens = 0;
        int totalCompletionTokens = 0;
        int totalTokens = 0;
        int maxRetries = aiDraftProperties.resolvedMaxRetries();
        for (int providerIndex = 0; providerIndex < providers.size(); providerIndex++) {
            AiProviderCatalog.Provider provider = providers.get(providerIndex);
            String nextPrompt = prompt(request, evidence, memoryResponse);
            for (int attempt = 0; attempt <= maxRetries; attempt++) {
                boolean canRetryProvider = attempt < maxRetries;
                boolean canRotateProvider = providerIndex < providers.size() - 1;
                try {
                    AiChatResult result = aiChatGateway.call(new AiChatRequest(
                            provider.name(),
                            provider.chatModel(),
                            "CoursewareAgent",
                            nextPrompt,
                            evidenceRefs(evidence)));
                    totalPromptTokens += result.promptTokens();
                    totalCompletionTokens += result.completionTokens();
                    totalTokens += result.totalTokens();
                    recoveryEvents.add(event(
                            "MODEL_CALL_SUCCEEDED",
                            result.providerName(),
                            result.modelCode(),
                            attempt,
                            false,
                            true,
                            result.safeMessage()));
                    ParsedDraft parsed = parseStructuredDraft(result.generatedContent());
                    if (parsed.structured()) {
                        recoveryEvents.add(event(
                                "JSON_PARSE_SUCCEEDED",
                                result.providerName(),
                                result.modelCode(),
                                attempt,
                                true,
                                false,
                                "Structured teaching draft parsed."));
                        return toAiDraft(
                                result,
                                parsed,
                                totalPromptTokens,
                                totalCompletionTokens,
                                totalTokens,
                                attempt,
                                maxRetries,
                                recoveryEvents);
                    }
                    recoveryEvents.add(event(
                            "JSON_PARSE_FAILED",
                            result.providerName(),
                            result.modelCode(),
                            attempt,
                            false,
                            canRetryProvider || canRotateProvider,
                            parsed.parseError()));
                    if (attempt == maxRetries) {
                        lastUnstructuredDraft = toAiDraft(
                                result,
                                parsed,
                                totalPromptTokens,
                                totalCompletionTokens,
                                totalTokens,
                                attempt,
                                maxRetries,
                                recoveryEvents);
                        break;
                    }
                    recoveryEvents.add(event(
                            "RETRY_SCHEDULED",
                            provider.name(),
                            provider.chatModel(),
                            attempt + 1,
                            false,
                            true,
                            "Retrying model output repair after JSON parse failure."));
                    nextPrompt = retryPrompt(request, evidence, memoryResponse, result.generatedContent(), parsed.parseError());
                } catch (RuntimeException exception) {
                    lastFailure = exception;
                    recoveryEvents.add(event(
                            "MODEL_CALL_FAILED",
                            provider.name(),
                            provider.chatModel(),
                            attempt,
                            false,
                            canRetryProvider || canRotateProvider,
                            exception.getClass().getSimpleName()));
                    if (attempt == maxRetries) {
                        break;
                    }
                    recoveryEvents.add(event(
                            "RETRY_SCHEDULED",
                            provider.name(),
                            provider.chatModel(),
                            attempt + 1,
                            false,
                            true,
                            "Retrying after transient model gateway failure."));
                    nextPrompt = transientFailureRetryPrompt(request, evidence, memoryResponse, exception);
                }
            }
            if (providerIndex < providers.size() - 1) {
                AiProviderCatalog.Provider nextProvider = providers.get(providerIndex + 1);
                recoveryEvents.add(event(
                        "PROVIDER_ROTATED",
                        nextProvider.name(),
                        nextProvider.chatModel(),
                        0,
                        false,
                        true,
                        "Switching to next enabled provider after failed attempts."));
            }
        }
        if (lastUnstructuredDraft != null) {
            return lastUnstructuredDraft;
        }
        return new TeachingTaskResponse.AiDraft(
                true,
                "",
                "",
                0,
                0,
                0,
                "",
                "AI provider failed: " + (lastFailure == null ? "unknown" : lastFailure.getClass().getSimpleName()),
                false,
                "",
                "",
                List.of(),
                List.of(),
                lastFailure == null ? "" : lastFailure.getClass().getSimpleName(),
                maxRetries,
                maxRetries,
                false,
                List.copyOf(recoveryEvents));
    }

    private static TeachingTaskResponse.AiDraft toAiDraft(
            AiChatResult result,
            ParsedDraft parsed,
            int promptTokens,
            int completionTokens,
            int totalTokens,
            int retryCount,
            int maxRetries,
            List<TeachingTaskResponse.AiRecoveryEvent> recoveryEvents) {
        String message = parsed.structured()
                ? result.safeMessage()
                : result.safeMessage() + " Structured parse failed after " + retryCount + " retry.";
        return new TeachingTaskResponse.AiDraft(
                true,
                result.providerName(),
                result.modelCode(),
                promptTokens,
                completionTokens,
                totalTokens,
                result.generatedContent(),
                message,
                parsed.structured(),
                parsed.teacherExplanation(),
                parsed.studentHint(),
                parsed.knowledgePoints(),
                parsed.followUpQuestions(),
                parsed.parseError(),
                retryCount,
                maxRetries,
                parsed.structured() && retryCount > 0,
                List.copyOf(recoveryEvents));
    }

    private static TeachingTaskResponse.AiRecoveryEvent event(
            String eventType,
            String providerName,
            String modelCode,
            int attemptNo,
            boolean structured,
            boolean retryable,
            String message) {
        return new TeachingTaskResponse.AiRecoveryEvent(
                eventType,
                providerName,
                modelCode,
                attemptNo,
                structured,
                retryable,
                safeEventMessage(message));
    }

    /**
     * Returns a disabled service for focused tests that do not configure real provider credentials.
     *
     * @return disabled draft service
     */
    public static TeachingAiDraftService disabled() {
        return new TeachingAiDraftService(
                request -> {
                    throw new IllegalStateException("Live AI gateway is not configured");
                },
                new AiProviderCatalog(new com.doob.mathagent.infrastructure.ai.AiProviderProperties()),
                new TeachingAiDraftProperties());
    }

    /**
     * Builds a classroom-ready prompt from real task data and retrieved evidence.
     */
    private static String prompt(
            TeachingTaskRequest request,
            List<TeachingEvidence> evidence,
            StudentMemoryResponse memoryResponse) {
        return """
                你是高中数学备课智能体。基于给定证据生成可直接放入讲义的内容。
                只输出一个 JSON 对象，不要 Markdown，不要代码块，不要额外解释。
                JSON schema：
                {
                  "teacherExplanation": "教师版讲解，2-5 句话，必须贴合题目和证据",
                  "studentHint": "学生版提示，1-3 句话，只给思路不直接泄露完整答案",
                  "knowledgePoints": ["关键知识点，2-6 条"],
                  "followUpQuestions": ["后续互动问题，2-5 条"]
                }
                不要写“作为AI”，不要编造没有给出的来源。
                学习目标：%s
                题目：%s
                记忆复用：%s
                检索证据：%s
                """.formatted(
                request.learningGoal(),
                request.questionText(),
                memoryResponse.reused() ? memoryResponse.answer() : memoryResponse.reason(),
                evidence.stream().map(TeachingAiDraftService::evidenceLine).toList());
    }

    private static String retryPrompt(
            TeachingTaskRequest request,
            List<TeachingEvidence> evidence,
            StudentMemoryResponse memoryResponse,
            String previousContent,
            String parseError) {
        return """
                上一次输出没有通过后端 JSON schema 解析。请只修复格式，不要扩展来源，不要输出 Markdown。
                解析错误：%s
                上一次输出：%s

                重新输出唯一 JSON 对象，字段必须完整且非空：
                {
                  "teacherExplanation": "...",
                  "studentHint": "...",
                  "knowledgePoints": ["..."],
                  "followUpQuestions": ["..."]
                }
                学习目标：%s
                题目：%s
                记忆复用：%s
                检索证据：%s
                """.formatted(
                parseError,
                previousContent == null ? "" : previousContent,
                request.learningGoal(),
                request.questionText(),
                memoryResponse.reused() ? memoryResponse.answer() : memoryResponse.reason(),
                evidence.stream().map(TeachingAiDraftService::evidenceLine).toList());
    }

    private static String transientFailureRetryPrompt(
            TeachingTaskRequest request,
            List<TeachingEvidence> evidence,
            StudentMemoryResponse memoryResponse,
            RuntimeException exception) {
        return prompt(request, evidence, memoryResponse)
                + "\n上一次调用异常：" + exception.getClass().getSimpleName()
                + "。这是后端自动重试，请仍然只输出合法 JSON。";
    }

    /**
     * Converts evidence rows to compact references passed to the model gateway.
     */
    private static List<String> evidenceRefs(List<TeachingEvidence> evidence) {
        return evidence.stream()
                .map(item -> item.sourceScope() + ":" + item.sourceTitle() + ":" + item.chunkId())
                .toList();
    }

    /**
     * Converts one evidence row to prompt text.
     */
    private static String evidenceLine(TeachingEvidence evidence) {
        return evidence.sourceScope() + "/" + evidence.sourceTitle() + "/p." + evidence.pageNo() + ": " + evidence.snippet();
    }

    /**
     * Parses model content into the expected classroom JSON schema without inventing missing fields.
     */
    static ParsedDraft parseStructuredDraft(String content) {
        if (content == null || content.isBlank()) {
            return ParsedDraft.failed("empty model content");
        }
        String json = extractJsonObject(stripCodeFence(content.strip()));
        try {
            StructuredDraftJson parsed = OBJECT_MAPPER.readValue(json, StructuredDraftJson.class);
            String teacherExplanation = normalizeText(parsed.teacherExplanation());
            String studentHint = normalizeText(parsed.studentHint());
            List<String> knowledgePoints = normalizeList(parsed.knowledgePoints());
            List<String> followUpQuestions = normalizeList(parsed.followUpQuestions());
            if (teacherExplanation.isBlank()
                    || studentHint.isBlank()
                    || knowledgePoints.isEmpty()
                    || followUpQuestions.isEmpty()) {
                return ParsedDraft.failed("JSON schema missing required nonblank teaching fields");
            }
            return new ParsedDraft(
                    true,
                    teacherExplanation,
                    studentHint,
                    knowledgePoints,
                    followUpQuestions,
                    "");
        } catch (JsonProcessingException | IllegalArgumentException exception) {
            return ParsedDraft.failed(exception.getClass().getSimpleName() + ": " + safeErrorMessage(exception));
        }
    }

    private static String stripCodeFence(String content) {
        if (!content.startsWith("```")) {
            return content;
        }
        int firstLineEnd = content.indexOf('\n');
        int lastFenceStart = content.lastIndexOf("```");
        if (firstLineEnd >= 0 && lastFenceStart > firstLineEnd) {
            return content.substring(firstLineEnd + 1, lastFenceStart).strip();
        }
        return content;
    }

    private static String extractJsonObject(String content) {
        int start = content.indexOf('{');
        int end = content.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return content;
        }
        return content.substring(start, end + 1);
    }

    private static String normalizeText(String value) {
        return value == null ? "" : value.strip();
    }

    private static List<String> normalizeList(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        List<String> normalized = new ArrayList<>();
        for (String value : values) {
            String item = normalizeText(value);
            if (!item.isBlank()) {
                normalized.add(item);
            }
        }
        return List.copyOf(normalized);
    }

    private static String safeErrorMessage(Exception exception) {
        String message = exception.getMessage();
        if (message == null || message.isBlank()) {
            return "";
        }
        return message.length() <= 180 ? message : message.substring(0, 180);
    }

    private static String safeEventMessage(String message) {
        if (message == null || message.isBlank()) {
            return "";
        }
        String stripped = message.strip();
        return stripped.length() <= 180 ? stripped : stripped.substring(0, 180);
    }

    record ParsedDraft(
            boolean structured,
            String teacherExplanation,
            String studentHint,
            List<String> knowledgePoints,
            List<String> followUpQuestions,
            String parseError) {

        static ParsedDraft failed(String parseError) {
            return new ParsedDraft(false, "", "", List.of(), List.of(), parseError);
        }
    }

    private record StructuredDraftJson(
            String teacherExplanation,
            String studentHint,
            List<String> knowledgePoints,
            List<String> followUpQuestions) {
    }
}
