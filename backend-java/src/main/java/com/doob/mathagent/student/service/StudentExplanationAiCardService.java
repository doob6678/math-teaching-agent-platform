package com.doob.mathagent.student.service;

import com.doob.mathagent.agent.service.AiChatGateway;
import com.doob.mathagent.agent.service.AiChatRequest;
import com.doob.mathagent.agent.service.AiChatResult;
import com.doob.mathagent.infrastructure.ai.AiProviderCatalog;
import com.doob.mathagent.infrastructure.text.FormulaMarkupSanitizer;
import com.doob.mathagent.student.dto.StudentExplanationRequest;
import com.doob.mathagent.student.vo.StudentExplanationResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Service;

/**
 * Isolates real AI card composition, JSON repair, and safe card normalization.
 */
@Service
public class StudentExplanationAiCardService {

    private static final int MAX_AI_RETRIES_PER_PROVIDER = 1;
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final AiChatGateway aiChatGateway;
    private final AiProviderCatalog aiProviderCatalog;

    public StudentExplanationAiCardService(
            AiChatGateway aiChatGateway,
            AiProviderCatalog aiProviderCatalog) {
        this.aiChatGateway = aiChatGateway;
        this.aiProviderCatalog = aiProviderCatalog;
    }

    /**
     * Generates explanation cards through a live AI provider. Failed model calls are surfaced instead of local prose.
     */
    public AiCardDraft generate(
            StudentExplanationRequest request,
            String query,
            String imageStatus,
            List<StudentExplanationResponse.ExplanationSource> sources,
            List<StudentExplanationHistorySummary> recentHistory,
            List<StudentExplanationResponse.WorkflowStage> stages) {
        long stageStarted = System.nanoTime();
        List<AiProviderCatalog.Provider> providers;
        try {
            providers = aiProviderCatalog.enabledProviders();
        } catch (RuntimeException e) {
            stages.add(stageFrom(stageStarted, "ai_compose_cards", "AI 生成卡片", "skipped", e.getMessage()));
            throw new IllegalStateException("No enabled AI provider: " + e.getMessage(), e);
        }
        if (providers.isEmpty()) {
            stages.add(stageFrom(stageStarted, "ai_compose_cards", "AI 生成卡片", "skipped", "没有可用模型配置。"));
            throw new IllegalStateException("No enabled AI provider.");
        }
        List<StudentExplanationResponse.AiRecoveryEvent> events = new ArrayList<>();
        int totalPromptTokens = 0;
        int totalCompletionTokens = 0;
        int totalTokens = 0;
        RuntimeException lastFailure = null;
        ParsedAiCards lastParseFailure = ParsedAiCards.failed("model was not called");
        String queryForPrompt = queryWithHistory(query, recentHistory);
        for (int providerIndex = 0; providerIndex < providers.size(); providerIndex++) {
            AiProviderCatalog.Provider provider = providers.get(providerIndex);
            String userInput = aiPrompt(request, queryForPrompt, imageStatus, sources);
            for (int attempt = 0; attempt <= MAX_AI_RETRIES_PER_PROVIDER; attempt++) {
                boolean canRetry = attempt < MAX_AI_RETRIES_PER_PROVIDER || providerIndex < providers.size() - 1;
                try {
                    AiChatResult result = aiChatGateway.call(new AiChatRequest(
                            provider.name(),
                            provider.chatModel(),
                            "StudentExplanationAgent",
                            userInput,
                            sourceRefs(sources)));
                    totalPromptTokens += result.promptTokens();
                    totalCompletionTokens += result.completionTokens();
                    totalTokens += result.totalTokens();
                    events.add(aiEvent("MODEL_CALL_SUCCEEDED", result.providerName(), result.modelCode(), attempt,
                            false, true, result.safeMessage()));
                    ParsedAiCards parsed = parseAiCards(result.generatedContent(), sources);
                    if (parsed.structured()) {
                        events.add(aiEvent("JSON_PARSE_SUCCEEDED", result.providerName(), result.modelCode(), attempt,
                                true, false, "Student explanation cards parsed."));
                        stages.add(stageFrom(stageStarted, "ai_compose_cards", "AI 生成卡片", "completed",
                                result.providerName() + "/" + result.modelCode() + " tokens=" + result.totalTokens()));
                        return new AiCardDraft(parsed.cards(), new StudentExplanationResponse.AiDraft(
                                true,
                                result.providerName(),
                                result.modelCode(),
                                totalPromptTokens,
                                totalCompletionTokens,
                                totalTokens,
                                true,
                                result.safeMessage(),
                                List.copyOf(events)));
                    }
                    lastParseFailure = parsed;
                    events.add(aiEvent("JSON_PARSE_FAILED", result.providerName(), result.modelCode(), attempt,
                            false, canRetry, parsed.parseError()));
                    userInput = aiRepairPrompt(
                            request, queryForPrompt, sources, result.generatedContent(), parsed.parseError());
                } catch (RuntimeException e) {
                    lastFailure = e;
                    events.add(aiEvent("MODEL_CALL_FAILED", provider.name(), provider.chatModel(), attempt,
                            false, canRetry, e.getClass().getSimpleName()));
                }
            }
            if (providerIndex < providers.size() - 1) {
                AiProviderCatalog.Provider nextProvider = providers.get(providerIndex + 1);
                events.add(aiEvent("PROVIDER_ROTATED", nextProvider.name(), nextProvider.chatModel(), 0,
                        false, true, "Switching to next enabled provider."));
            }
        }
        String message = lastFailure == null
                ? "AI card JSON parse failed: " + lastParseFailure.parseError()
                : "AI provider failed: " + lastFailure.getClass().getSimpleName();
        stages.add(stageFrom(stageStarted, "ai_compose_cards", "AI 生成卡片", "failed", message));
        throw new IllegalStateException(message);
    }

    private static String queryWithHistory(String query, List<StudentExplanationHistorySummary> recentHistory) {
        if (recentHistory == null || recentHistory.isEmpty()) {
            return query;
        }
        return query + "\nRecent conversation history:\n" + String.join("\n", historyLines(recentHistory));
    }

    private static List<String> historyLines(List<StudentExplanationHistorySummary> recentHistory) {
        if (recentHistory == null || recentHistory.isEmpty()) {
            return List.of();
        }
        return recentHistory.stream()
                .limit(6)
                .map(item -> "- "
                        + safe(item.questionText()).replaceAll("\\s+", " ").strip()
                        + " | image=" + safe(item.imageStatus())
                        + " | model=" + safe(item.aiProviderName()) + "/" + safe(item.aiModelCode())
                        + " | tokens=" + item.totalTokens())
                .toList();
    }

    private static String aiPrompt(
            StudentExplanationRequest request,
            String query,
            String imageStatus,
            List<StudentExplanationResponse.ExplanationSource> sources) {
        return """
                You are a high-school math explanation agent for students.
                Return exactly one valid JSON object. Do not output Markdown, code fences, or self-introduction.
                All user-facing text values must be written in concise Chinese.
                Explain like a teacher: short, direct, step-by-step.
                Math must use Feishu-supported delimiters only: inline $...$ or display $$...$$.
                Do not use \\[...\\], \\(...\\), \\begin{align}, \\begin{aligned}, \\begin{equation}, or Markdown code fences.
                Only cite sourceUri values from evidenceSources. Do not invent textbook pages, Feishu links, or knowledge URIs.
                JSON schema:
                {
                  "cards": [
                    {
                      "cardKey": "problem_understanding|knowledge_points|method_hint|step_by_step|common_mistakes|source_links",
                      "title": "Chinese short title",
                      "summary": "1-3 Chinese sentences",
                      "items": ["Chinese short bullet"],
                      "sourceUris": ["must come from evidenceSources.sourceUri"],
                      "renderMode": "text|formula|source_list"
                    }
                  ]
                }
                Return at least 4 cards and include step_by_step and source_links.
                Problem text: %s
                Retrieval query: %s
                Image status: %s
                evidenceSources: %s
                """.formatted(
                safe(request.questionText()),
                query,
                imageStatus,
                sourceLines(sources));
    }

    private static String aiRepairPrompt(
            StudentExplanationRequest request,
            String query,
            List<StudentExplanationResponse.ExplanationSource> sources,
            String previousContent,
            String parseError) {
        return """
                The previous output failed backend JSON parsing. Fix format only.
                Do not add new sources. Do not output Markdown or code fences.
                All user-facing text values must be written in concise Chinese.
                Math must use only $...$ or $$...$$; never use \\[...\\], \\(...\\), or align/equation environments.
                Parse error: %s
                Previous output: %s
                Return exactly one valid JSON object with this schema:
                {"cards":[{"cardKey":"step_by_step","title":"...","summary":"...","items":["..."],"sourceUris":["..."],"renderMode":"formula"}]}
                Problem text: %s
                Retrieval query: %s
                Allowed sourceUri values: %s
                """.formatted(
                safe(parseError),
                trimForPrompt(previousContent),
                safe(request.questionText()),
                query,
                sources.stream().map(StudentExplanationResponse.ExplanationSource::sourceUri).toList());
    }

    static ParsedAiCards parseAiCards(
            String content,
            List<StudentExplanationResponse.ExplanationSource> sources) {
        if (content == null || content.isBlank()) {
            return ParsedAiCards.failed("empty model content");
        }
        Set<String> allowedSourceUris = sources.stream()
                .map(StudentExplanationResponse.ExplanationSource::sourceUri)
                .collect(LinkedHashSet::new, LinkedHashSet::add, LinkedHashSet::addAll);
        String jsonObject = extractJsonObject(stripCodeFence(content.strip()));
        try {
            AiCardsJson parsed = readAiCardsJson(jsonObject);
            List<StudentExplanationResponse.ExplanationCard> cards = normalizeAiCards(parsed.cards(), allowedSourceUris);
            if (cards.size() < 4 || cards.stream().noneMatch(card -> "step_by_step".equals(card.cardKey()))
                    || cards.stream().noneMatch(card -> "source_links".equals(card.cardKey()))) {
                return ParsedAiCards.failed("JSON schema missing required explanation cards");
            }
            return new ParsedAiCards(true, cards, "");
        } catch (JsonProcessingException | IllegalArgumentException e) {
            return ParsedAiCards.failed(e.getClass().getSimpleName() + ": " + safeErrorMessage(e));
        }
    }

    private static AiCardsJson readAiCardsJson(String jsonObject) throws JsonProcessingException {
        try {
            return OBJECT_MAPPER.readValue(jsonObject, AiCardsJson.class);
        } catch (JsonProcessingException firstFailure) {
            String repaired = repairInvalidJsonBackslashes(jsonObject);
            if (repaired.equals(jsonObject)) {
                throw firstFailure;
            }
            return OBJECT_MAPPER.readValue(repaired, AiCardsJson.class);
        }
    }

    private static String repairInvalidJsonBackslashes(String jsonObject) {
        StringBuilder repaired = new StringBuilder(jsonObject.length() + 16);
        boolean insideString = false;
        boolean escaped = false;
        boolean changed = false;
        for (int index = 0; index < jsonObject.length(); index++) {
            char current = jsonObject.charAt(index);
            if (!insideString) {
                repaired.append(current);
                if (current == '"') {
                    insideString = true;
                }
                continue;
            }
            if (escaped) {
                if (!isValidJsonEscape(current)) {
                    repaired.append('\\');
                    changed = true;
                }
                repaired.append(current);
                escaped = false;
                continue;
            }
            if (current == '\\') {
                repaired.append(current);
                escaped = true;
                continue;
            }
            repaired.append(current);
            if (current == '"') {
                insideString = false;
            }
        }
        if (escaped) {
            repaired.append('\\');
            changed = true;
        }
        return changed ? repaired.toString() : jsonObject;
    }

    private static boolean isValidJsonEscape(char value) {
        return value == '"' || value == '\\' || value == '/' || value == 'b'
                || value == 'f' || value == 'n' || value == 'r' || value == 't' || value == 'u';
    }

    private static List<StudentExplanationResponse.ExplanationCard> normalizeAiCards(
            List<AiCardJson> cards,
            Set<String> allowedSourceUris) {
        if (cards == null || cards.isEmpty()) {
            return List.of();
        }
        List<StudentExplanationResponse.ExplanationCard> normalized = new ArrayList<>();
        for (AiCardJson card : cards) {
            String cardKey = safe(card.cardKey()).strip();
            String title = sanitizeFormulaText(card.title());
            String summary = sanitizeFormulaText(card.summary());
            String renderMode = normalizeRenderMode(card.renderMode());
            List<String> items = normalizeTextItems(card.items());
            List<String> sourceUris = normalizeSourceUris(card.sourceUris(), allowedSourceUris);
            if (!cardKey.isBlank() && !title.isBlank() && !summary.isBlank()) {
                normalized.add(new StudentExplanationResponse.ExplanationCard(
                        cardKey,
                        title,
                        summary,
                        items,
                        sourceUris,
                        renderMode));
            }
        }
        return List.copyOf(normalized);
    }

    private static String normalizeRenderMode(String renderMode) {
        String value = safe(renderMode).strip();
        return switch (value) {
            case "formula", "source_list" -> value;
            default -> "text";
        };
    }

    private static List<String> normalizeTextItems(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        return values.stream()
                .map(StudentExplanationAiCardService::safe)
                .map(StudentExplanationAiCardService::sanitizeFormulaText)
                .filter(value -> !value.isBlank())
                .limit(8)
                .toList();
    }

    private static List<String> normalizeSourceUris(List<String> values, Set<String> allowedSourceUris) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        return values.stream()
                .map(StudentExplanationAiCardService::safe)
                .map(String::strip)
                .filter(allowedSourceUris::contains)
                .distinct()
                .toList();
    }

    private static List<String> sourceLines(List<StudentExplanationResponse.ExplanationSource> sources) {
        return sources.stream()
                .map(source -> source.sourceType() + " | " + source.title() + " | "
                        + source.sourceUri() + " | " + source.permissionScope() + " | " + compact(source.snippet()))
                .toList();
    }

    private static List<String> sourceRefs(List<StudentExplanationResponse.ExplanationSource> sources) {
        return sources.stream()
                .map(source -> source.sourceType() + ":" + source.sourceUri())
                .toList();
    }

    private static StudentExplanationResponse.AiRecoveryEvent aiEvent(
            String eventType,
            String providerName,
            String modelCode,
            int attemptNo,
            boolean structured,
            boolean retryable,
            String message) {
        return new StudentExplanationResponse.AiRecoveryEvent(
                eventType,
                safe(providerName),
                safe(modelCode),
                attemptNo,
                structured,
                retryable,
                safeEventMessage(message));
    }

    private static String compact(String value) {
        String stripped = safe(value).replaceAll("\\s+", " ").strip();
        return stripped.length() <= 180 ? stripped : stripped.substring(0, 180);
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

    private static String trimForPrompt(String content) {
        String value = safe(content).strip();
        return value.length() <= 1200 ? value : value.substring(0, 1200);
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

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static String sanitizeFormulaText(String value) {
        return FormulaMarkupSanitizer.sanitizeFeishuMath(StudentExplanationVisionService.repairMojibake(safe(value)));
    }

    private static StudentExplanationResponse.WorkflowStage stageFrom(
            long stageStartedNanos,
            String key,
            String title,
            String status,
            String detail) {
        return new StudentExplanationResponse.WorkflowStage(key, title, status, safe(detail), elapsedMs(stageStartedNanos));
    }

    private static long elapsedMs(long startedNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos);
    }

    public record AiCardDraft(
            List<StudentExplanationResponse.ExplanationCard> cards,
            StudentExplanationResponse.AiDraft aiDraft) {
    }

    record ParsedAiCards(
            boolean structured,
            List<StudentExplanationResponse.ExplanationCard> cards,
            String parseError) {

        static ParsedAiCards failed(String parseError) {
            return new ParsedAiCards(false, List.of(), parseError);
        }
    }

    private record AiCardsJson(List<AiCardJson> cards) {
    }

    private record AiCardJson(
            String cardKey,
            String title,
            String summary,
            List<String> items,
            List<String> sourceUris,
            String renderMode) {
    }
}
