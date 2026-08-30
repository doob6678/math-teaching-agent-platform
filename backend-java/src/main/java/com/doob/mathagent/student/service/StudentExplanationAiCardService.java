package com.doob.mathagent.student.service;

import com.doob.mathagent.agent.service.AiChatStreamDelta;
import com.doob.mathagent.agent.service.PythonMigratedWorkloadClient;
import com.doob.mathagent.infrastructure.text.FormulaMarkupSanitizer;
import com.doob.mathagent.infrastructure.text.TextEncodingRepair;
import com.doob.mathagent.student.dto.StudentExplanationRequest;
import com.doob.mathagent.student.vo.StudentExplanationResponse;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * 学生讲解的 Python facade 与 Java 结果校验边界。
 *
 * <p>Python 承担 ReAct、provider 路由、输出修复和 usage 记账。Java 仍限定可用工具、执行检索、复验引用，并投影既有
 * SSE 与公共响应。</p>
 */
@Service
public class StudentExplanationAiCardService {

    private static final int MAX_REACT_SEARCH_QUERIES = 6;
    private static final int MAX_REACT_QUERY_LENGTH = 80;

    private final PythonMigratedWorkloadClient workloadClient;

    public StudentExplanationAiCardService(PythonMigratedWorkloadClient workloadClient) {
        this.workloadClient = workloadClient;
    }

    /** Prepares a token-bounded conversation window before the existing V1 ReAct execution. */
    public PythonMigratedWorkloadClient.ConversationContextPreparation prepareConversationContext(
            String runId,
            String problem,
            List<StudentExplanationConversationContextMessage> messages,
            StudentExplanationContextSummary summary,
            int maxInputTokens,
            int reservedOutputTokens,
            int summaryTriggerTokens) {
        List<PythonMigratedWorkloadClient.ConversationContextMessage> context = messages == null ? List.of() : messages.stream()
                .map(message -> new PythonMigratedWorkloadClient.ConversationContextMessage(
                        safe(message.explanationId()), safe(message.questionText()), safe(message.answerText()),
                        message.createdAt() == null ? "" : message.createdAt().toString()))
                .toList();
        PythonMigratedWorkloadClient.ConversationContextSummary persistedSummary = summary == null ? null
                : new PythonMigratedWorkloadClient.ConversationContextSummary(
                        safe(summary.fromMessageId()), safe(summary.toMessageId()), summary.version(),
                        safe(summary.contentHash()), safe(summary.content()));
        return workloadClient.prepareStudentExplanationContext(
                safe(runId), safe(problem), context, persistedSummary,
                maxInputTokens, reservedOutputTokens, summaryTriggerTokens);
    }

    /** 兼容不含图片或流监听器的调用方。 */
    public ReactDecision nextReactDecision(
            String problem,
            List<StudentExplanationResponse.ExplanationSource> sources,
            List<String> observations,
            Set<String> availableTools) {
        return nextReactDecision(problem, sources, observations, availableTools, "", StudentExplanationAiStreamListener.NOOP);
    }

    /** 兼容不含图片的调用方。 */
    public ReactDecision nextReactDecision(
            String problem,
            List<StudentExplanationResponse.ExplanationSource> sources,
            List<String> observations,
            Set<String> availableTools,
            StudentExplanationAiStreamListener streamListener) {
        return nextReactDecision(problem, sources, observations, availableTools, "", streamListener);
    }

    /** 请求 Python ReAct planner，并以 Java 本轮 allow-list 复验工具、检索词和 final cards。 */
    public ReactDecision nextReactDecision(
            String problem,
            List<StudentExplanationResponse.ExplanationSource> sources,
            List<String> observations,
            Set<String> availableTools,
            String imageDataUrl,
            StudentExplanationAiStreamListener streamListener) {
        return nextReactDecision(problem, sources, observations, availableTools, imageDataUrl, streamListener,
                UUID.randomUUID().toString());
    }

    /** 使用父请求的确定性子运行标识调用 Python ReAct planner。 */
    public ReactDecision nextReactDecision(
            String problem,
            List<StudentExplanationResponse.ExplanationSource> sources,
            List<String> observations,
            Set<String> availableTools,
            String imageDataUrl,
            StudentExplanationAiStreamListener streamListener,
            String runId) {
        Set<String> allowed = availableTools == null ? Set.of() : Set.copyOf(availableTools);
        // 决策改走 Python 流式端点：final 轮的 title/summary JSON 增量实时交给投影层（StudentExplanationController
        // 只提取 title/summary/items 文本），学生首字从整包完成降到首个字段到达；action 轮没有这些字段不会泄漏工具名。
        PythonMigratedWorkloadClient.ExplanationDecision result = workloadClient.streamDecideStudentExplanation(
                safe(runId).isBlank() ? stableRunId(problem, imageDataUrl, "react") : safe(runId),
                safe(problem),
                evidence(sources),
                List.copyOf(allowed),
                observations == null ? List.of() : observations,
                safe(imageDataUrl),
                event -> {
                    if (!"delta".equals(event.eventName()) || safe(event.content()).isBlank()) {
                        return;
                    }
                    StudentExplanationAiStreamListener listener = streamListener == null
                            ? StudentExplanationAiStreamListener.NOOP : streamListener;
                    listener.onDelta(new AiChatStreamDelta(
                            event.providerName(), event.modelCode(), event.content(), "", 0, 0, 0), List.of());
                });
        if ("final".equals(result.decision())) {
            List<StudentExplanationResponse.ExplanationCard> cards = normalizeCards(result.cards(), sources);
            if (cards.isEmpty()) {
                return ReactDecision.invalid("Python ReAct final omitted valid cards.");
            }
            StudentExplanationResponse.AiDraft draft = aiDraft(result.usage(), result.providerName(), result.modelCode());
            StudentExplanationAiStreamListener listener = streamListener == null
                    ? StudentExplanationAiStreamListener.NOOP : streamListener;
            listener.onDelta(delta(result), cards);
            return ReactDecision.finalAnswer(new AiCardDraft(result.conversationTitle(), cards, draft));
        }
        List<String> tools = result.tools().stream().filter(allowed::contains).distinct().limit(3).toList();
        if (tools.isEmpty()) {
            return fallbackDecision(allowed, "Python ReAct returned no permitted tool.");
        }
        return ReactDecision.action(tools, normalizeSearchQueries(result.queries()));
    }

    /** 兼容旧调用方的最终卡片入口。 */
    public AiCardDraft generate(
            StudentExplanationRequest request,
            String query,
            String imageStatus,
            List<StudentExplanationResponse.ExplanationSource> sources,
            List<StudentExplanationHistorySummary> recentHistory,
            List<String> longTermMemories,
            List<StudentExplanationResponse.WorkflowStage> stages) {
        return generate(request, query, imageStatus, sources, recentHistory, longTermMemories, stages, "", StudentExplanationAiStreamListener.NOOP);
    }

    /** 兼容旧调用方的最终卡片入口。 */
    public AiCardDraft generate(
            StudentExplanationRequest request,
            String query,
            String imageStatus,
            List<StudentExplanationResponse.ExplanationSource> sources,
            List<StudentExplanationHistorySummary> recentHistory,
            List<StudentExplanationResponse.WorkflowStage> stages) {
        return generate(request, query, imageStatus, sources, recentHistory, List.of(), stages, "", StudentExplanationAiStreamListener.NOOP);
    }

    /** 兼容旧调用方的最终卡片入口。 */
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

    /** 调用 Python compose runtime，并按 Java 已授权 evidence 复验每一张卡。 */
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
        return generate(request, query, "", imageStatus, sources, recentHistory, longTermMemories, stages,
                imageDataUrl, streamListener);
    }

    /** Uses a prepared bounded conversation context without changing the V1 evidence and citation boundary. */
    public AiCardDraft generate(
            StudentExplanationRequest request,
            String query,
            String conversationContext,
            String imageStatus,
            List<StudentExplanationResponse.ExplanationSource> sources,
            List<StudentExplanationHistorySummary> recentHistory,
            List<String> longTermMemories,
            List<StudentExplanationResponse.WorkflowStage> stages,
            String imageDataUrl,
            StudentExplanationAiStreamListener streamListener) {
        String problem = composeProblem(request, query, conversationContext);
        PythonMigratedWorkloadClient.ExplanationResult result = workloadClient.streamStudentExplanation(
                composeRunId(request, problem, imageDataUrl),
                problem,
                evidence(sources),
                safe(imageDataUrl),
                event -> {
                    if (!"delta".equals(event.eventName()) || safe(event.content()).isBlank()) {
                        return;
                    }
                    StudentExplanationAiStreamListener listener = streamListener == null
                            ? StudentExplanationAiStreamListener.NOOP : streamListener;
                    // 注意 AiChatStreamDelta 的字段顺序是 (provider, model, reasoningDelta, contentDelta)：
                    // worker 的可见 JSON 增量必须进 contentDelta；此前传到 reasoning 槽位导致投影层全部丢弃，
                    // 学生端首字退化成整包完成（9 秒级）。
                    listener.onDelta(new AiChatStreamDelta(
                            event.providerName(), event.modelCode(), "", event.content(), 0, 0, 0), List.of());
                });
        List<StudentExplanationResponse.ExplanationCard> cards = normalizeCards(result.cards(), sources);
        if (cards.isEmpty()) {
            throw new IllegalStateException("Python student explanation returned no valid cards");
        }
        StudentExplanationAiStreamListener listener = streamListener == null
                ? StudentExplanationAiStreamListener.NOOP : streamListener;
        listener.onDelta(new AiChatStreamDelta(
                result.providerName(), result.modelCode(), "", "", result.usage().promptTokens(),
                result.usage().completionTokens(), result.usage().totalTokens()), cards);
        return new AiCardDraft(result.conversationTitle(), cards,
                aiDraft(result.usage(), result.providerName(), result.modelCode()));
    }

    private static StudentExplanationResponse.AiDraft aiDraft(
            PythonMigratedWorkloadClient.Usage usage, String provider, String model) {
        return new StudentExplanationResponse.AiDraft(
                true, safe(provider), safe(model), usage.promptTokens(), usage.completionTokens(), usage.totalTokens(),
                true, "Python student explanation completed.",
                List.of(
                        event("PYTHON_MODEL_CALL_SUCCEEDED", provider, model, false, true,
                                "Python worker completed model execution."),
                        event("PYTHON_CITATION_VALIDATED", provider, model, true, false,
                                "Java validated returned source URIs.")));
    }

    private static AiChatStreamDelta delta(PythonMigratedWorkloadClient.ExplanationDecision result) {
        return new AiChatStreamDelta(result.providerName(), result.modelCode(), "", "", result.usage().promptTokens(),
                result.usage().completionTokens(), result.usage().totalTokens());
    }

    private static List<PythonMigratedWorkloadClient.ExplanationEvidence> evidence(
            List<StudentExplanationResponse.ExplanationSource> sources) {
        if (sources == null || sources.isEmpty()) return List.of();
        return sources.stream()
                .filter(source -> source != null && !safe(source.sourceUri()).isBlank())
                .map(source -> new PythonMigratedWorkloadClient.ExplanationEvidence(
                        source.sourceUri(), safe(source.title()), safe(source.snippet())))
                .limit(24)
                .toList();
    }

    private static List<String> normalizeSearchQueries(List<String> values) {
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        if (values != null) {
            for (String raw : values) {
                String query = safe(raw).replaceAll("\\s+", " ").strip();
                if (query.length() > MAX_REACT_QUERY_LENGTH) query = query.substring(0, MAX_REACT_QUERY_LENGTH).strip();
                if (!query.isBlank()) normalized.add(query);
                if (normalized.size() >= MAX_REACT_SEARCH_QUERIES) break;
            }
        }
        return List.copyOf(normalized);
    }

    private static ReactDecision fallbackDecision(Set<String> allowed, String reason) {
        return allowed.stream().findFirst()
                .map(tool -> ReactDecision.recoveredAction(List.of(tool), reason))
                .orElseGet(() -> ReactDecision.invalid(reason));
    }

    private static List<StudentExplanationResponse.ExplanationCard> normalizeCards(
            List<PythonMigratedWorkloadClient.ExplanationCard> cards,
            List<StudentExplanationResponse.ExplanationSource> sources) {
        Set<String> allowedUris = new LinkedHashSet<>();
        if (sources != null) {
            sources.stream().filter(source -> source != null).map(StudentExplanationResponse.ExplanationSource::sourceUri)
                    .map(StudentExplanationAiCardService::safe).filter(uri -> !uri.isBlank()).forEach(allowedUris::add);
        }
        List<StudentExplanationResponse.ExplanationCard> normalized = new ArrayList<>();
        for (PythonMigratedWorkloadClient.ExplanationCard card
                : cards == null ? List.<PythonMigratedWorkloadClient.ExplanationCard>of() : cards) {
            String summary = sanitize(card.summary());
            if (summary.isBlank()) continue;
            List<String> uris = card.sourceUris().stream().map(StudentExplanationAiCardService::safe)
                    .filter(allowedUris::contains).distinct().limit(24).toList();
            List<String> items = card.items().stream().map(StudentExplanationAiCardService::sanitize)
                    .filter(value -> !value.isBlank()).limit(16).toList();
            String mode = switch (safe(card.renderMode())) {
                case "formula", "source_list" -> card.renderMode();
                default -> "text";
            };
            normalized.add(new StudentExplanationResponse.ExplanationCard(
                    safe(card.cardKey()).isBlank() ? "explanation" : safe(card.cardKey()),
                    sanitize(card.title()), summary, items, uris, mode));
            if (normalized.size() >= 12) break;
        }
        return List.copyOf(normalized);
    }

    private static String composeProblem(StudentExplanationRequest request, String query) {
        return composeProblem(request, query, "");
    }

    private static String composeProblem(StudentExplanationRequest request, String query, String conversationContext) {
        String problem = request == null ? "" : safe(request.questionText());
        String context = safe(query);
        String conversation = safe(conversationContext);
        String base = problem.isBlank() ? context : problem + (context.isBlank() ? "" : "\n\n检索上下文：" + context);
        return conversation.isBlank() ? base : conversation + (base.isBlank() ? "" : "\n\n本轮检索提示：" + base);
    }

    private static String composeRunId(StudentExplanationRequest request, String query, String imageDataUrl) {
        String base = request == null ? "" : safe(request.clientRequestId());
        if (base.isBlank()) {
            base = stableRunId(composeProblem(request, query), imageDataUrl, "compose");
        }
        return base + ":compose";
    }

    private static String stableRunId(String problem, String imageDataUrl, String stage) {
        return UUID.nameUUIDFromBytes((stage + "\\n" + safe(problem) + "\\n" + safe(imageDataUrl))
                .getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
    }

    private static StudentExplanationResponse.AiRecoveryEvent event(
            String type, String provider, String model, boolean structured, boolean retryable, String message) {
        return new StudentExplanationResponse.AiRecoveryEvent(type, safe(provider), safe(model), 0,
                structured, retryable, safe(message));
    }

    private static String sanitize(String value) {
        return FormulaMarkupSanitizer.sanitizeFeishuMath(TextEncodingRepair.repairMojibake(safe(value)));
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    public record AiCardDraft(
            String conversationTitle,
            List<StudentExplanationResponse.ExplanationCard> cards,
            StudentExplanationResponse.AiDraft aiDraft) {
    }

    /** Python 回传的 action 只是请求，Java 仍是授权检索的执行者。 */
    public record ReactDecision(String kind, List<String> tools, List<String> searchQueries, String message, AiCardDraft finalDraft) {
        static ReactDecision action(List<String> tools, List<String> searchQueries) {
            return new ReactDecision("action", List.copyOf(tools), List.copyOf(searchQueries), "", null);
        }
        static ReactDecision recoveredAction(List<String> tools, String reason) {
            return new ReactDecision("action", List.copyOf(tools), List.of(), "fallback: " + reason, null);
        }
        static ReactDecision finalAnswer(AiCardDraft finalDraft) {
            return new ReactDecision("final", List.of(), List.of(), "", finalDraft);
        }
        static ReactDecision invalid(String message) {
            return new ReactDecision("invalid", List.of(), List.of(), message, null);
        }
        public String tool() { return tools.isEmpty() ? "" : tools.getFirst(); }
        public String searchQuery() { return searchQueries.isEmpty() ? "" : searchQueries.getFirst(); }
        public boolean isFinal() { return "final".equals(kind) && finalDraft != null; }
        public boolean isAction() { return "action".equals(kind) && !tools.isEmpty(); }
    }
}
