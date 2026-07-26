package com.doob.mathagent.student.service;

import com.doob.mathagent.agent.service.AiChatGateway;
import com.doob.mathagent.agent.service.AiChatRequest;
import com.doob.mathagent.agent.service.AiChatResult;
import com.doob.mathagent.infrastructure.ai.AiProviderCatalog;
import com.doob.mathagent.infrastructure.text.FormulaMarkupSanitizer;
import com.doob.mathagent.infrastructure.text.TextEncodingRepair;
import com.doob.mathagent.student.dto.StudentExplanationRequest;
import com.doob.mathagent.student.vo.StudentExplanationResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Isolates real AI card composition, JSON repair, and safe card normalization.
 */
@Service
public class StudentExplanationAiCardService {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final AiChatGateway aiChatGateway;
    private final AiProviderCatalog aiProviderCatalog;
    private final int maxProviderAttempts;
    private final int conversationContextMaxChars;

    /**
     * Asks the model for its next ReAct decision.  The returned action is only a request: the caller remains the
     * authority that checks permissions and executes the read-only retrieval tool before returning an observation.
     */
    public ReactDecision nextReactDecision(
            String problem,
            List<StudentExplanationResponse.ExplanationSource> sources,
            List<String> observations,
            Set<String> availableTools) {
        return nextReactDecision(problem, sources, observations, availableTools, StudentExplanationAiStreamListener.NOOP);
    }

    /**
     * Plans the bounded ReAct turn while forwarding the provider's actual visible content as it arrives.
     *
     * <p>A self-contained question can finish during this planning call.  It must therefore use the same streaming
     * gateway as the later card-composition call; otherwise that common path leaves the browser waiting for the
     * complete JSON response with no visible AI output.</p>
     */
    public ReactDecision nextReactDecision(
            String problem,
            List<StudentExplanationResponse.ExplanationSource> sources,
            List<String> observations,
            Set<String> availableTools,
            StudentExplanationAiStreamListener streamListener) {
        return nextReactDecision(problem, sources, observations, availableTools, "", streamListener);
    }

    /** Plans retrieval while allowing the model to inspect the original owner-validated image. */
    public ReactDecision nextReactDecision(
            String problem,
            List<StudentExplanationResponse.ExplanationSource> sources,
            List<String> observations,
            Set<String> availableTools,
            String imageDataUrl,
            StudentExplanationAiStreamListener streamListener) {
        List<AiProviderCatalog.Provider> providers = aiProviderCatalog.enabledProviders();
        if (providers.isEmpty()) {
            throw new IllegalStateException("No enabled AI provider.");
        }
        AiProviderCatalog.Provider provider = providers.getFirst();
        StudentExplanationAiStreamListener listener = streamListener == null
                ? StudentExplanationAiStreamListener.NOOP
                : streamListener;
        AiChatResult result = aiChatGateway.stream(new AiChatRequest(
                provider.name(), provider.chatModel(), "StudentExplanationReactAgent",
                reactPrompt(problem, sources, observations, availableTools), sourceRefs(sources), imageDataUrl),
                // Planning output is not yet a trusted card.  Forward its visible JSON text only; cards are emitted
                // after the final parser validates the source allow-list below.
                delta -> listener.onDelta(delta, List.of()));
        try {
            JsonNode root = OBJECT_MAPPER.readTree(extractJsonObject(stripCodeFence(safe(result.generatedContent()).strip())));
            String decision = safe(root.path("decision").asText()).strip().toLowerCase(java.util.Locale.ROOT);
            if ("action".equals(decision)) {
                LinkedHashSet<String> requestedTools = new LinkedHashSet<>();
                JsonNode toolsNode = root.path("tools");
                if (toolsNode.isArray()) {
                    toolsNode.forEach(node -> requestedTools.add(safe(node.asText()).strip()));
                } else {
                    // Accept the former single-tool form while providers roll over to the batched planning contract.
                    requestedTools.add(safe(root.path("tool").asText()).strip());
                }
                requestedTools.removeIf(String::isBlank);
                if (requestedTools.isEmpty() || !availableTools.containsAll(requestedTools)) {
                    // The model cannot expand the server-side allow-list.  A malformed or stale tool name must not
                    // turn an otherwise valid user turn into a hard failure; the first remaining permitted tool is
                    // a deterministic, auditable recovery action and is still executed by the backend below.
                    return fallbackDecision(availableTools, "模型请求了不可用工具：" + requestedTools);
                }
                return ReactDecision.action(List.copyOf(requestedTools), safe(root.path("query").asText()).strip());
            }
            if ("final".equals(decision)) {
                // The same provider turn must contain the actual answer. Removing controller-only fields lets the
                // existing strict card parser remain the single validation and source-allow-list boundary.
                com.fasterxml.jackson.databind.node.ObjectNode cardsJson = root.deepCopy();
                cardsJson.remove(List.of("decision", "tool", "tools", "query"));
                ParsedAiCards parsed = parseAiCards(cardsJson.toString(), sources);
                if (!parsed.structured()) {
                    return fallbackDecision(availableTools, "ReAct final 缺少有效讲解卡片：" + parsed.parseError());
                }
                StudentExplanationResponse.AiDraft aiDraft = new StudentExplanationResponse.AiDraft(
                        true,
                        result.providerName(),
                        result.modelCode(),
                        result.promptTokens(),
                        result.completionTokens(),
                        result.totalTokens(),
                        true,
                        result.safeMessage(),
                        List.of(
                                aiEvent("MODEL_CALL_SUCCEEDED", result.providerName(), result.modelCode(), 0,
                                        false, true, result.safeMessage()),
                                aiEvent("JSON_PARSE_SUCCEEDED", result.providerName(), result.modelCode(), 0,
                                        true, false, "Student explanation cards parsed in ReAct final.")));
                return ReactDecision.finalAnswer(new AiCardDraft(parsed.conversationTitle(), parsed.cards(), aiDraft));
            }
            return fallbackDecision(availableTools, "模型未返回 ReAct decision");
        } catch (JsonProcessingException exception) {
            return fallbackDecision(availableTools, "ReAct 决策格式无效");
        }
    }

    /**
     * Recovers one bounded retrieval step when a provider violates the machine-readable ReAct contract.
     * The set is assembled by {@code StudentExplanationService} after permission checks, so this fallback cannot
     * access a teacher-private tool that the current subject is not allowed to use.
     */
    private static ReactDecision fallbackDecision(Set<String> availableTools, String reason) {
        return availableTools.stream()
                .findFirst()
                .map(tool -> ReactDecision.recoveredAction(List.of(tool), reason))
                .orElseGet(() -> ReactDecision.invalid(reason));
    }

    /** Keeps internal reasoning private while making the action contract strict and machine-verifiable. */
    private static String reactPrompt(
            String problem,
            List<StudentExplanationResponse.ExplanationSource> sources,
            List<String> observations,
            Set<String> availableTools) {
        return """
                You are a high-school math teacher operating one bounded ReAct turn. Return exactly one JSON object.
                If retrieval is required, plan every useful permitted read-only tool now and return:
                {"decision":"action","tools":["tool_a","tool_b"],"query":"concrete search terms extracted from text and image"}.
                Do not split independent retrieval tools across later turns and never repeat a tool.
                If the problem is self-contained, solve it in this same turn and return:
                {"decision":"final","conversationTitle":"15 Chinese characters or fewer","cards":[{"cardKey":"stable_snake_case","title":"optional Chinese title or empty","summary":"concise Chinese explanation","items":[],"sourceUris":[],"renderMode":"text|formula|source_list"}]}.
                For final answers, math uses only $...$ or $$...$$ and sourceUris must come from Current evidence.
                A self-contained calculation or proof normally needs no retrieval. Choose retrieval only when the user asks
                for a source/teaching material, or the statement lacks a fact that an available tool can actually supply.
                Never retrieve merely because a tool is available. Do not expose private reasoning or output Markdown fences.
                Available tools: %s
                Problem: %s
                Observations from actually executed tools: %s
                Current evidence: %s
                """.formatted(availableTools, safe(problem), observations, sourceLines(sources));
    }

    public StudentExplanationAiCardService(
            AiChatGateway aiChatGateway,
            AiProviderCatalog aiProviderCatalog) {
        this(aiChatGateway, aiProviderCatalog, 2, 100_000);
    }

    /** Limits one student turn to a bounded provider budget instead of multiplying every timeout by every provider. */
    @Autowired
    public StudentExplanationAiCardService(
            AiChatGateway aiChatGateway,
            AiProviderCatalog aiProviderCatalog,
            @Value("${math-agent.student.explanation.max-provider-attempts:2}") int maxProviderAttempts,
            @Value("${math-agent.student.explanation.conversation-context-max-chars:100000}") int conversationContextMaxChars) {
        this.aiChatGateway = aiChatGateway;
        this.aiProviderCatalog = aiProviderCatalog;
        this.maxProviderAttempts = Math.max(1, maxProviderAttempts);
        this.conversationContextMaxChars = Math.max(1_000, Math.min(conversationContextMaxChars, 100_000));
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
            List<String> longTermMemories,
            List<StudentExplanationResponse.WorkflowStage> stages) {
        return generate(request, query, imageStatus, sources, recentHistory, longTermMemories, stages, StudentExplanationAiStreamListener.NOOP);
    }

    public AiCardDraft generate(
            StudentExplanationRequest request,
            String query,
            String imageStatus,
            List<StudentExplanationResponse.ExplanationSource> sources,
            List<StudentExplanationHistorySummary> recentHistory,
            List<StudentExplanationResponse.WorkflowStage> stages) {
        return generate(request, query, imageStatus, sources, recentHistory, List.of(), stages, StudentExplanationAiStreamListener.NOOP);
    }

    /** Streams actual provider deltas to the caller while retaining the final strict JSON validation. */
    public AiCardDraft generate(
            StudentExplanationRequest request,
            String query,
            String imageStatus,
            List<StudentExplanationResponse.ExplanationSource> sources,
            List<StudentExplanationHistorySummary> recentHistory,
            List<String> longTermMemories,
            List<StudentExplanationResponse.WorkflowStage> stages,
            StudentExplanationAiStreamListener streamListener) {
        return generate(request, query, imageStatus, sources, recentHistory, longTermMemories, stages, "", streamListener);
    }

    /** Streams a final explanation while retaining the original image as first-class model context. */
    public AiCardDraft generate(
            StudentExplanationRequest request,
            String query,
            String imageStatus,
            List<StudentExplanationResponse.ExplanationSource> sources,
            List<StudentExplanationHistorySummary> recentHistory,
            List<String> longTermMemories,
            List<StudentExplanationResponse.WorkflowStage> stages,
            String imageDataUrl,
            StudentExplanationAiStreamListener streamListener) {
        long stageStarted = System.nanoTime();
        List<AiProviderCatalog.Provider> providers;
        try {
            providers = aiProviderCatalog.enabledProviders();
        } catch (RuntimeException e) {
            upsertStage(stages, stageFrom(stageStarted, "ai_compose_cards", "AI 生成卡片", "skipped", e.getMessage()));
            throw new IllegalStateException("No enabled AI provider: " + e.getMessage(), e);
        }
        if (providers.isEmpty()) {
            upsertStage(stages, stageFrom(stageStarted, "ai_compose_cards", "AI 生成卡片", "skipped", "没有可用模型配置。"));
            throw new IllegalStateException("No enabled AI provider.");
        }
        List<StudentExplanationResponse.AiRecoveryEvent> events = new ArrayList<>();
        int totalPromptTokens = 0;
        int totalCompletionTokens = 0;
        int totalTokens = 0;
        RuntimeException lastFailure = null;
        ParsedAiCards lastParseFailure = ParsedAiCards.failed("model was not called");
        String queryForPrompt = queryWithContext(query, recentHistory, longTermMemories);
        int providerLimit = Math.min(providers.size(), maxProviderAttempts);
        for (int providerIndex = 0; providerIndex < providerLimit; providerIndex++) {
            AiProviderCatalog.Provider provider = providers.get(providerIndex);
            String userInput = aiPrompt(request, queryForPrompt, imageStatus, sources);
            for (int attempt = 0; attempt < 1; attempt++) {
                boolean canRetry = providerIndex < providerLimit - 1;
                try {
                    StringBuilder streamedContent = new StringBuilder();
                    Set<String> streamedCardKeys = new LinkedHashSet<>();
                    AiChatResult result = aiChatGateway.stream(new AiChatRequest(
                            provider.name(),
                            provider.chatModel(),
                            "StudentExplanationAgent",
                            userInput,
                            sourceRefs(sources),
                            imageDataUrl), delta -> {
                                streamedContent.append(delta.contentDelta());
                                streamListener.onDelta(delta, completeStreamedCards(
                                        streamedContent.toString(), sources, streamedCardKeys));
                            });
                    totalPromptTokens += result.promptTokens();
                    totalCompletionTokens += result.completionTokens();
                    totalTokens += result.totalTokens();
                    events.add(aiEvent("MODEL_CALL_SUCCEEDED", result.providerName(), result.modelCode(), attempt,
                            false, true, result.safeMessage()));
                    ParsedAiCards parsed = parseAiCards(result.generatedContent(), sources);
                    if (parsed.structured()) {
                        events.add(aiEvent("JSON_PARSE_SUCCEEDED", result.providerName(), result.modelCode(), attempt,
                                true, false, "Student explanation cards parsed."));
                        upsertStage(stages, stageFrom(stageStarted, "ai_compose_cards", "AI 生成卡片", "completed",
                                result.providerName() + "/" + result.modelCode() + " tokens=" + result.totalTokens()));
                        return new AiCardDraft(parsed.conversationTitle(), parsed.cards(), new StudentExplanationResponse.AiDraft(
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
            if (providerIndex < providerLimit - 1) {
                AiProviderCatalog.Provider nextProvider = providers.get(providerIndex + 1);
                events.add(aiEvent("PROVIDER_ROTATED", nextProvider.name(), nextProvider.chatModel(), 0,
                        false, true, "Switching to next enabled provider."));
            }
        }
        String message = lastFailure == null
                ? "AI card JSON parse failed: " + lastParseFailure.parseError()
                : "AI provider failed: " + lastFailure.getClass().getSimpleName();
        upsertStage(stages, stageFrom(stageStarted, "ai_compose_cards", "AI 生成卡片", "failed", message));
        throw new IllegalStateException(message);
    }

    /**
     * 与主编排保持一致，同一 stageKey 只保留最新状态，避免前端看到重复的 AI 阶段。
     */
    private static void upsertStage(
            List<StudentExplanationResponse.WorkflowStage> stages,
            StudentExplanationResponse.WorkflowStage nextStage) {
        for (int index = 0; index < stages.size(); index++) {
            if (safe(stages.get(index).stageKey()).equals(nextStage.stageKey())) {
                stages.set(index, nextStage);
                return;
            }
        }
        stages.add(nextStage);
    }

    private String queryWithContext(
            String query,
            List<StudentExplanationHistorySummary> recentHistory,
            List<String> longTermMemories) {
        String normalizedQuery = safe(query);
        if (normalizedQuery.length() > conversationContextMaxChars) {
            normalizedQuery = normalizedQuery.substring(0, conversationContextMaxChars);
        }
        int contextBudget = conversationContextMaxChars - normalizedQuery.length();
        List<String> memoryLines = longTermMemoryLines(longTermMemories, Math.min(contextBudget, 20_000));
        int memoryChars = memoryLines.stream().mapToInt(String::length).sum() + memoryLines.size();
        List<String> historyLines = historyLines(recentHistory, Math.max(0, contextBudget - memoryChars));
        StringBuilder context = new StringBuilder(normalizedQuery);
        if (!historyLines.isEmpty()) {
            context.append("\nRecent conversation history:\n").append(String.join("\n", historyLines));
        }
        if (!memoryLines.isEmpty()) {
            context.append("\nRelevant long-term student memories:\n").append(String.join("\n", memoryLines));
        }
        return context.toString();
    }

    private static List<String> longTermMemoryLines(List<String> memories, int maxChars) {
        if (memories == null || memories.isEmpty() || maxChars <= 0) {
            return List.of();
        }
        List<String> selected = new ArrayList<>();
        int usedChars = 0;
        for (String memory : memories) {
            String line = "- " + safe(memory).replaceAll("\\s+", " ").strip();
            if (line.isBlank()) {
                continue;
            }
            int available = maxChars - usedChars;
            if (available <= 0) {
                break;
            }
            if (line.length() > available) {
                line = line.substring(0, available);
            }
            selected.add(line);
            usedChars += line.length() + 1;
        }
        return List.copyOf(selected);
    }

    private static List<String> historyLines(List<StudentExplanationHistorySummary> recentHistory, int maxChars) {
        if (recentHistory == null || recentHistory.isEmpty()) {
            return List.of();
        }
        List<String> selected = new ArrayList<>();
        int usedChars = 0;
        for (StudentExplanationHistorySummary item : recentHistory) {
            String line = "- " + safe(item.questionText()).replaceAll("\\s+", " ").strip()
                    + " | image=" + safe(item.imageStatus())
                    + " | model=" + safe(item.aiProviderName()) + "/" + safe(item.aiModelCode())
                    + " | tokens=" + item.totalTokens();
            if (line.length() > maxChars - usedChars) {
                break;
            }
            selected.addFirst(line);
            usedChars += line.length() + 1;
        }
        return List.copyOf(selected);
    }

    private static String aiPrompt(
            StudentExplanationRequest request,
            String query,
            String imageStatus,
            List<StudentExplanationResponse.ExplanationSource> sources) {
        return """
                You are ByteDance's high-school math AI teacher for students.
                Return exactly one valid JSON object. Do not output Markdown, code fences, or self-introduction.
                All user-facing text values must be written in concise Chinese.
                Speak like a real teacher in class: natural, connected, patient, and rigorous.
                Math must use Feishu-supported delimiters only: inline $...$ or display $$...$$.
                Do not use \\[...\\], \\(...\\), \\begin{align}, \\begin{aligned}, \\begin{equation}, or Markdown code fences.
                Do not expose model names, provider names, retries, prompts, JSON parsing, or internal workflow details.
                If you need a classroom pause, you may use <wait> inside summary text, but keep the overall prose continuous and human.
                Only cite sourceUri values from evidenceSources. Do not invent textbook pages, Feishu links, or knowledge URIs.
                JSON schema:
                {
                  "conversationTitle": "Chinese short title within 15 chars",
                  "cards": [
                    {
                      "cardKey": "a short stable snake_case key chosen for this problem",
                      "title": "optional Chinese short title; leave empty for an uninterrupted explanation",
                      "summary": "1-3 Chinese sentences",
                      "items": ["Chinese short bullet"],
                      "sourceUris": ["must come from evidenceSources.sourceUri"],
                      "renderMode": "text|formula|source_list"
                    }
                  ]
                }
                conversationTitle must be a specific short Chinese title for this exact problem, within 15 Chinese characters, and must not be generic words like AI讲题, 讲解, 解析.
                Cards are a transport envelope, not a teaching template. You independently decide whether this exact
                question needs one uninterrupted explanation or several sections. Choose the number, order, titles,
                and focus only from the problem; do not create familiar headings such as step-by-step reasoning,
                common mistakes, summary, or practice merely because they are common in a lesson. A single-card
                answer may leave `title` empty. Add a derivation, mistake reminder, conclusion, or extension exercise
                only when it adds concrete value for this learner and this question. `items` is optional: use it only
                when a list is clearer than prose, otherwise return an empty array. Evidence may support any card but
                must not become a standalone card unless it materially helps the learner.
                When knowledge or method evidence clearly comes from teacher resources, reflect that in wording and keep the explanation aligned with teacher notes.
                Keep the wording natural, continuous, and classroom-like. Do not sound like disconnected bullet fragments.
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
                {"conversationTitle":"...","cards":[{"cardKey":"chosen_by_agent","title":"optional title or empty","summary":"...","items":["..."],"sourceUris":["..."],"renderMode":"text|formula|source_list"}]}
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
            String conversationTitle = StudentExplanationConversationTitleSupport.normalizeAiTitle(parsed.conversationTitle());
            List<StudentExplanationResponse.ExplanationCard> cards = normalizeAiCards(parsed.cards(), allowedSourceUris);
            if (cards.isEmpty()) {
                return ParsedAiCards.failed("JSON schema did not contain any usable explanation card");
            }
            return new ParsedAiCards(true, conversationTitle, cards, "");
        } catch (JsonProcessingException | IllegalArgumentException e) {
            return ParsedAiCards.failed(e.getClass().getSimpleName() + ": " + safeErrorMessage(e));
        }
    }

    /** Emits only provider card objects whose braces are closed and whose JSON validates. */
    private static List<StudentExplanationResponse.ExplanationCard> completeStreamedCards(
            String content,
            List<StudentExplanationResponse.ExplanationSource> sources,
            Set<String> emittedCardKeys) {
        int cardsKey = content.indexOf("\"cards\"");
        int arrayStart = cardsKey < 0 ? -1 : content.indexOf('[', cardsKey);
        if (arrayStart < 0) return List.of();
        Set<String> allowed = sources.stream().map(StudentExplanationResponse.ExplanationSource::sourceUri)
                .collect(LinkedHashSet::new, LinkedHashSet::add, LinkedHashSet::addAll);
        List<StudentExplanationResponse.ExplanationCard> completed = new ArrayList<>();
        int objectStart = -1, depth = 0;
        boolean inString = false, escaped = false;
        for (int index = arrayStart + 1; index < content.length(); index++) {
            char current = content.charAt(index);
            if (inString) {
                if (escaped) escaped = false;
                else if (current == '\\') escaped = true;
                else if (current == '"') inString = false;
                continue;
            }
            if (current == '"') inString = true;
            else if (current == '{') { if (depth++ == 0) objectStart = index; }
            else if (current == '}' && depth > 0 && --depth == 0 && objectStart >= 0) {
                try {
                    AiCardJson parsed = OBJECT_MAPPER.readValue(content.substring(objectStart, index + 1), AiCardJson.class);
                    for (StudentExplanationResponse.ExplanationCard card : normalizeAiCards(List.of(parsed), allowed)) {
                        if (emittedCardKeys.add(card.cardKey())) completed.add(card);
                    }
                } catch (JsonProcessingException ignored) {
                    // Wait for later bytes when a provider split an escaped JSON sequence.
                }
            } else if (current == ']' && depth == 0) break;
        }
        return List.copyOf(completed);
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
        Set<String> occupiedCardKeys = new LinkedHashSet<>();
        for (int index = 0; index < cards.size(); index++) {
            AiCardJson card = cards.get(index);
            // cardKey identifies a transport item only. Derive one for a valid untitled agent section rather than
            // discarding learner-facing content because the model intentionally omitted presentation metadata.
            String cardKey = uniqueCardKey(card.cardKey(), index + 1, occupiedCardKeys);
            String title = sanitizeFormulaText(card.title());
            String summary = sanitizeFormulaText(card.summary());
            String renderMode = normalizeRenderMode(card.renderMode());
            List<String> items = normalizeTextItems(card.items());
            List<String> sourceUris = normalizeSourceUris(card.sourceUris(), allowedSourceUris);
            if (!summary.isBlank()) {
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

    /** Preserves agent-provided keys where possible and guarantees uniqueness for streamed UI reconciliation. */
    private static String uniqueCardKey(String candidate, int ordinal, Set<String> occupiedCardKeys) {
        String base = safe(candidate).strip().replaceAll("[^A-Za-z0-9_-]+", "_");
        if (base.isBlank()) {
            base = "agent_section_" + ordinal;
        }
        String key = base;
        int duplicate = 2;
        while (!occupiedCardKeys.add(key)) {
            key = base + "_" + duplicate++;
        }
        return key;
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
        return FormulaMarkupSanitizer.sanitizeFeishuMath(TextEncodingRepair.repairMojibake(safe(value)));
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
            String conversationTitle,
            List<StudentExplanationResponse.ExplanationCard> cards,
            StudentExplanationResponse.AiDraft aiDraft) {
    }

    /** One validated model decision: either a complete tool plan or a complete final answer. */
    public record ReactDecision(String kind, List<String> tools, String searchQuery, String message, AiCardDraft finalDraft) {
        static ReactDecision action(List<String> tools, String searchQuery) {
            return new ReactDecision("action", List.copyOf(tools), safe(searchQuery), "", null);
        }
        static ReactDecision recoveredAction(List<String> tools, String reason) {
            return new ReactDecision("action", List.copyOf(tools), "", "fallback: " + reason, null);
        }
        static ReactDecision finalAnswer(AiCardDraft finalDraft) {
            return new ReactDecision("final", List.of(), "", "", finalDraft);
        }
        static ReactDecision invalid(String message) {
            return new ReactDecision("invalid", List.of(), "", message, null);
        }

        /** Compatibility accessor for diagnostics that still display the first selected tool. */
        public String tool() { return tools.isEmpty() ? "" : tools.getFirst(); }
    }

    record ParsedAiCards(
            boolean structured,
            String conversationTitle,
            List<StudentExplanationResponse.ExplanationCard> cards,
            String parseError) {

        static ParsedAiCards failed(String parseError) {
            return new ParsedAiCards(false, "", List.of(), parseError);
        }
    }

    private record AiCardsJson(String conversationTitle, List<AiCardJson> cards) {
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
