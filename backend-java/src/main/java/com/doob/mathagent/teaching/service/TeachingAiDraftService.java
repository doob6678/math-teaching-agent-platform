package com.doob.mathagent.teaching.service;

import com.doob.mathagent.agent.service.AiChatGateway;
import com.doob.mathagent.agent.service.AiChatRequest;
import com.doob.mathagent.agent.service.AiChatResult;
import com.doob.mathagent.infrastructure.ai.AiProviderCatalog;
import com.doob.mathagent.infrastructure.text.FormulaMarkupSanitizer;
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
    private static final TeachingHandoutTemplateProfile DEFAULT_TEMPLATE =
            new TeachingHandoutTemplateService().resolve("default_standard");

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
            StudentMemoryResponse memoryResponse,
            TeachingHandoutTemplateProfile template) {
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
            String nextPrompt = prompt(request, evidence, memoryResponse, template);
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
                    nextPrompt = retryPrompt(request, evidence, memoryResponse, template, result.generatedContent(), parsed.parseError());
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
                    nextPrompt = transientFailureRetryPrompt(request, evidence, memoryResponse, template, exception);
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

    /**
     * Backward-compatible overload used by older tests and internal call sites.
     */
    public TeachingTaskResponse.AiDraft draft(
            TeachingTaskRequest request,
            List<TeachingEvidence> evidence,
            StudentMemoryResponse memoryResponse) {
        return draft(request, evidence, memoryResponse, DEFAULT_TEMPLATE);
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
     * Builds a classroom-ready prompt from real task data and retrieved evidence.
     */
    private static String prompt(
            TeachingTaskRequest request,
            List<TeachingEvidence> evidence,
            StudentMemoryResponse memoryResponse,
            TeachingHandoutTemplateProfile template) {
        return """
                You are a high-school math lesson-preparation agent.
                Generate evidence-grounded content that can be placed directly into a polished printable handout.
                Return exactly one valid JSON object. Do not output Markdown, code fences, or extra explanation.
                All user-facing text values must be written in concise Chinese.
                Math must use Feishu-supported delimiters only: inline $...$ or display $$...$$.
                Do not use \\[...\\], \\(...\\), \\begin{align}, \\begin{aligned}, \\begin{equation}, or Markdown code fences.
                Treat template layout instructions as rendering constraints only. Do not write header/footer/color/PDF layout rules in any JSON value.
                JSON schema:
                {
                  "teacherExplanation": "Chinese teacher handout body with labeled parts: 【知识定位】【题型识别】【方法步骤】【例题详解】【答案与评分点】【易错提醒】【课堂追问】. It must be printable and complete, not chatty.",
                  "studentHint": "Chinese student worksheet body with labeled parts: 【知识速记】【题型识别】【例题任务】【练习任务】【作答提醒】. Hint only; do not reveal full answers or scoring points.",
                  "knowledgePoints": ["3-8 Chinese knowledge points or method cards, formula-first when useful"],
                  "followUpQuestions": ["3-8 Chinese exercises/questions, include easy/medium/hard progression when possible"]
                }
                Do not write "as an AI". Do not invent sources not provided below.
                Do not output raw page OCR fragments, raw source ids, model names, token usage, backend diagnostics, JSON/parse/debug words, or model-health wording.
                Do not mention page header, page footer, colors, rendering engines, prompt rules, template rules, or "PDF layout requirements" as handout content.
                Teacher content must include answers when enough information is available from the problem/evidence; student content must leave blanks instead of answers.
                Respect printable handout layout, but do not describe layout rules such as header/footer or color requirements as user-facing content.
                If the user only gives a topic rather than a problem, create a complete mini-handout around that topic.
                Selected handout template: %s
                Template instructions: %s
                Learning goal: %s
                Problem: %s
                Reused memory: %s
                Retrieved evidence: %s
                """.formatted(
                template.summary().displayName(),
                template.promptInstructions(),
                request.learningGoal(),
                request.questionText(),
                memoryResponse.reused() ? memoryResponse.answer() : memoryResponse.reason(),
                evidence.stream().map(TeachingAiDraftService::evidenceLine).toList());
    }

    private static String retryPrompt(
            TeachingTaskRequest request,
            List<TeachingEvidence> evidence,
            StudentMemoryResponse memoryResponse,
            TeachingHandoutTemplateProfile template,
            String previousContent,
            String parseError) {
        return """
                The previous output failed backend JSON schema parsing.
                Fix format only. Do not add sources. Do not output Markdown.
                All user-facing text values must be written in concise Chinese.
                Math must use only $...$ or $$...$$; never use \\[...\\], \\(...\\), or align/equation environments.
                Do not write header/footer/color/PDF layout rules, AI, token, debug, JSON, or model-health wording inside user-facing values.
                Parse error: %s
                Previous output: %s

                Return exactly one valid JSON object with all fields present and non-empty:
                {
                  "teacherExplanation": "printable Chinese teacher handout body",
                  "studentHint": "printable Chinese student worksheet hint",
                  "knowledgePoints": ["..."],
                  "followUpQuestions": ["..."]
                }
                Selected handout template: %s
                Template instructions: %s
                Learning goal: %s
                Problem: %s
                Reused memory: %s
                Retrieved evidence: %s
                """.formatted(
                parseError,
                previousContent == null ? "" : previousContent,
                template.summary().displayName(),
                template.promptInstructions(),
                request.learningGoal(),
                request.questionText(),
                memoryResponse.reused() ? memoryResponse.answer() : memoryResponse.reason(),
                evidence.stream().map(TeachingAiDraftService::evidenceLine).toList());
    }

    private static String transientFailureRetryPrompt(
            TeachingTaskRequest request,
            List<TeachingEvidence> evidence,
            StudentMemoryResponse memoryResponse,
            TeachingHandoutTemplateProfile template,
            RuntimeException exception) {
        return prompt(request, evidence, memoryResponse, template)
                + "\nPrevious provider call failed with: " + exception.getClass().getSimpleName()
                + ". This is an automatic backend retry. Still return only valid JSON.";
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
        return evidence.sourceScope()
                + "/"
                + evidence.sourceTitle()
                + "/p."
                + evidence.pageNo()
                + ": "
                + TeachingEvidenceSnippetSanitizer.sanitizeCompact(evidence.snippet());
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
        return FormulaMarkupSanitizer.sanitizeFeishuMath(value);
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
