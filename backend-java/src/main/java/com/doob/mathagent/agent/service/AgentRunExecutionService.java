package com.doob.mathagent.agent.service;

import com.doob.mathagent.agent.dto.AgentRunExecuteRequest;
import com.doob.mathagent.agent.vo.AgentRunExecuteResponse;
import com.doob.mathagent.agent.vo.AgentRunPlanResponse;
import com.doob.mathagent.infrastructure.ai.AiProviderCatalog;
import com.doob.mathagent.infrastructure.security.RequestSubject;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Executes a planned AI agent run through the configured live model gateway and records a safe trace.
 */
@Service
public class AgentRunExecutionService {

    private static final String COMPLETED = "COMPLETED";
    private static final Duration CONCURRENCY_LEASE_TIME = Duration.ofMinutes(10);
    private static final int JSON_REPAIR_MAX_RETRIES = 2;
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final AgentTraceStore traceStore;
    private final AgentConcurrencyGuard concurrencyGuard;
    private final AiChatGateway aiChatGateway;
    private final AiProviderCatalog providerCatalog;
    private final Clock clock;

    /**
     * Creates the production execution service.
     *
     * @param traceStore trace storage boundary
     */
    @Autowired
    public AgentRunExecutionService(
            AgentTraceStore traceStore,
            AgentConcurrencyGuard concurrencyGuard,
            AiChatGateway aiChatGateway,
            AiProviderCatalog providerCatalog) {
        this(traceStore, concurrencyGuard, aiChatGateway, providerCatalog, Clock.systemUTC());
    }

    /**
     * Creates a testable execution service with explicit dependencies.
     *
     * @param traceStore trace storage boundary
     * @param concurrencyGuard concurrency guard
     * @param aiChatGateway model gateway
     * @param providerCatalog provider catalog used for fallback rotation
     * @param clock clock used for trace timestamps
     */
    public AgentRunExecutionService(
            AgentTraceStore traceStore,
            AgentConcurrencyGuard concurrencyGuard,
            AiChatGateway aiChatGateway,
            AiProviderCatalog providerCatalog,
            Clock clock) {
        this.traceStore = traceStore;
        this.concurrencyGuard = concurrencyGuard;
        this.aiChatGateway = aiChatGateway;
        this.providerCatalog = providerCatalog;
        this.clock = clock;
    }

    /**
     * Returns whether the request needs a capability token according to server-side policy.
     *
     * @param request execution request
     * @return true when a capability token is required
     */
    public boolean requiresCapability(AgentRunExecuteRequest request) {
        AgentRunPlanResponse plan = request.normalize().plan();
        return plan.capabilityRequired() || AgentRunPolicy.agentByCode(safeText(plan.agentCode())).highValueRequired();
    }

    /**
     * Returns the server-side capability action for an execution plan.
     *
     * @param plan plan snapshot
     * @return stable capability action
     */
    public String capabilityAction(AgentRunPlanResponse plan) {
        String agentCode = safeText(plan.agentCode());
        if (agentCode.isBlank()) {
            throw new IllegalArgumentException("Agent code is required");
        }
        return "agent-run:" + agentCode;
    }

    /**
     * Executes the run by validating ownership, calling the model gateway, and writing a trace record.
     *
     * @param request execution request
     * @param subject backend authenticated subject
     * @return safe trace response
     */
    public AgentRunExecuteResponse execute(AgentRunExecuteRequest request, RequestSubject subject) {
        StageTimer timer = new StageTimer();
        AgentRunExecuteRequest normalized = request.normalize();
        AgentRunPlanResponse plan = normalized.plan();
        RequestSubject normalizedSubject = subject.normalize();
        validateSubject(plan, normalizedSubject);
        validatePlanPolicy(plan, normalizedSubject);
        timer.mark("capability_guard");

        String traceId = UUID.randomUUID().toString();
        List<String> concurrencyKeys = safeList(plan.concurrencyKeys());
        AgentConcurrencyLease lease = concurrencyGuard.tryAcquire(concurrencyKeys, traceId, CONCURRENCY_LEASE_TIME)
                .orElseThrow(() -> new IllegalStateException("Agent concurrency limit exceeded"));
        timer.mark("concurrency_guard");

        AgentTraceRecord record = new AgentTraceRecord(
                traceId,
                safeText(plan.planId()),
                Instant.now(clock),
                normalizedSubject.tenantId(),
                normalizedSubject.subjectType(),
                normalizedSubject.subjectId(),
                safeText(plan.agentCode()),
                safeText(plan.providerName()),
                safeText(plan.modelCode()),
                COMPLETED,
                plan.estimatedCost(),
                safeList(plan.allowedToolScopes()),
                safeList(plan.allowedDataScopes()),
                safeList(normalized.evidenceRefs()),
                List.of(),
                new AgentRunExecuteResponse.TokenUsage(0, 0, 0),
                "");
        try {
            timer.mark("trace_start");

            if (normalized.dryRun()) {
                throw new IllegalArgumentException("Agent dryRun is disabled in production");
            }
            ExecutionOutcome outcome = callModelWithFallback(normalized, record);
            timer.mark("model_call");
            timer.mark("trace_finish");
            AgentTraceRecord finalRecord = new AgentTraceRecord(
                    record.traceId(),
                    record.planId(),
                    record.createdAt(),
                    record.tenantId(),
                    record.subjectType(),
                    record.subjectId(),
                    record.agentCode(),
                    outcome.providerName(),
                    outcome.modelCode(),
                    record.status(),
                    record.estimatedCost(),
                    record.allowedToolScopes(),
                    record.allowedDataScopes(),
                    record.evidenceRefs(),
                    timer.timings(),
                    new AgentRunExecuteResponse.TokenUsage(
                            outcome.promptTokens(),
                            outcome.completionTokens(),
                            outcome.totalTokens()),
                    outcome.message(),
                    outcome.diagnosticEvents());
            traceStore.save(finalRecord);

            return new AgentRunExecuteResponse(
                    finalRecord.traceId(),
                    finalRecord.planId(),
                    finalRecord.tenantId(),
                    finalRecord.subjectType(),
                    finalRecord.subjectId(),
                    finalRecord.agentCode(),
                    finalRecord.providerName(),
                    finalRecord.modelCode(),
                    finalRecord.status(),
                    finalRecord.estimatedCost(),
                    finalRecord.allowedToolScopes(),
                    finalRecord.allowedDataScopes(),
                    concurrencyKeys,
                    finalRecord.stageTimings(),
                    finalRecord.actualUsage(),
                    finalRecord.message(),
                    outcome.generatedContent());
        } finally {
            lease.close();
        }
    }

    /**
     * Calls the selected model and rotates to configured fallback providers when the primary call fails.
     */
    private ExecutionOutcome callModelWithFallback(AgentRunExecuteRequest request, AgentTraceRecord record) {
        RuntimeException lastFailure = null;
        List<AgentTraceRecord.DiagnosticEvent> diagnosticEvents = new ArrayList<>();
        List<AiProviderCatalog.Provider> providers = fallbackProviders(record.providerName(), record.modelCode());
        for (int index = 0; index < providers.size(); index++) {
            AiProviderCatalog.Provider provider = providers.get(index);
            boolean canRotateProvider = index < providers.size() - 1;
            String nextInputSummary = request.userInputSummary();
            int accumulatedPromptTokens = 0;
            int accumulatedCompletionTokens = 0;
            int accumulatedTotalTokens = 0;
            int maxAttempts = request.plan().requiredJsonSchema() ? JSON_REPAIR_MAX_RETRIES : 0;
            for (int attempt = 0; attempt <= maxAttempts; attempt++) {
                boolean canRetryJson = attempt < maxAttempts;
                try {
                    AiChatResult result = aiChatGateway.call(new AiChatRequest(
                            provider.name(),
                            provider.chatModel(),
                            record.agentCode(),
                            nextInputSummary,
                            request.evidenceRefs()));
                    accumulatedPromptTokens += Math.max(0, result.promptTokens());
                    accumulatedCompletionTokens += Math.max(0, result.completionTokens());
                    accumulatedTotalTokens += Math.max(0, result.totalTokens());
                    diagnosticEvents.add(diagnosticEvent(
                            "MODEL_CALL_SUCCEEDED",
                            result.providerName(),
                            result.modelCode(),
                            attempt,
                            false,
                            result.safeMessage()));
                    JsonValidationResult validation = validateJsonIfRequired(request.plan(), result.generatedContent());
                    if (validation.valid()) {
                        if (request.plan().requiredJsonSchema()) {
                            diagnosticEvents.add(diagnosticEvent(
                                    "JSON_PARSE_SUCCEEDED",
                                    result.providerName(),
                                    result.modelCode(),
                                    attempt,
                                    false,
                                    "Agent output parsed as a JSON object."));
                        }
                        return new ExecutionOutcome(
                                result.providerName(),
                                result.modelCode(),
                                accumulatedPromptTokens,
                                accumulatedCompletionTokens,
                                accumulatedTotalTokens,
                                result.safeMessage(),
                                safeGeneratedContent(result.generatedContent()),
                                List.copyOf(diagnosticEvents));
                    }
                    diagnosticEvents.add(diagnosticEvent(
                            "JSON_PARSE_FAILED",
                            result.providerName(),
                            result.modelCode(),
                            attempt,
                            canRetryJson || canRotateProvider,
                            validation.error()));
                    if (!canRetryJson) {
                        lastFailure = new IllegalStateException(validation.error());
                        break;
                    }
                    diagnosticEvents.add(diagnosticEvent(
                            "RETRY_SCHEDULED",
                            provider.name(),
                            provider.chatModel(),
                            attempt + 1,
                            true,
                            "Retrying model output repair after JSON parse failure."));
                    nextInputSummary = jsonRepairPrompt(request.userInputSummary(), result.generatedContent(), validation.error());
                } catch (RuntimeException exception) {
                    lastFailure = exception;
                    diagnosticEvents.add(diagnosticEvent(
                            "MODEL_CALL_FAILED",
                            provider.name(),
                            provider.chatModel(),
                            attempt,
                            canRetryJson || canRotateProvider,
                            exception.getClass().getSimpleName()));
                    if (!canRetryJson) {
                        break;
                    }
                    diagnosticEvents.add(diagnosticEvent(
                            "RETRY_SCHEDULED",
                            provider.name(),
                            provider.chatModel(),
                            attempt + 1,
                            true,
                            "Retrying after transient model gateway failure."));
                }
            }
            if (canRotateProvider) {
                AiProviderCatalog.Provider nextProvider = providers.get(index + 1);
                diagnosticEvents.add(diagnosticEvent(
                        "PROVIDER_ROTATED",
                        nextProvider.name(),
                        nextProvider.chatModel(),
                        index + 1,
                        true,
                        "Switching to next enabled provider after failed attempts."));
            }
        }
        throw new IllegalStateException("All configured AI providers failed", lastFailure);
    }

    /**
     * Validates required structured output as a single JSON object.
     */
    private static JsonValidationResult validateJsonIfRequired(AgentRunPlanResponse plan, String generatedContent) {
        if (!plan.requiredJsonSchema()) {
            return JsonValidationResult.ok();
        }
        String content = safeText(generatedContent);
        if (content.isBlank()) {
            return JsonValidationResult.failed("empty model content");
        }
        String json = extractJsonObject(stripCodeFence(content));
        try {
            if (!OBJECT_MAPPER.readTree(json).isObject()) {
                return JsonValidationResult.failed("model output is not a JSON object");
            }
            return JsonValidationResult.ok();
        } catch (JsonProcessingException exception) {
            return JsonValidationResult.failed(exception.getClass().getSimpleName() + ": " + safeText(exception.getOriginalMessage()));
        }
    }

    /**
     * Builds a concise repair prompt without storing raw prompts in traces.
     */
    private static String jsonRepairPrompt(String originalSummary, String previousContent, String parseError) {
        return """
                The previous model output failed backend JSON validation.
                Return exactly one JSON object. Do not wrap it in Markdown fences. Do not add explanations.
                Parse error: %s
                Original task: %s
                Previous output: %s
                """.formatted(
                safeText(parseError),
                safeText(originalSummary),
                safeText(previousContent));
    }

    /**
     * Removes a Markdown code fence before JSON object extraction.
     */
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

    /**
     * Extracts the outermost object span from text that may include provider boilerplate.
     */
    private static String extractJsonObject(String content) {
        int start = content.indexOf('{');
        int end = content.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return content;
        }
        return content.substring(start, end + 1);
    }

    /**
     * Builds provider rotation order with the plan-selected provider first and remaining enabled providers after it.
     */
    private List<AiProviderCatalog.Provider> fallbackProviders(String providerName, String modelCode) {
        List<AiProviderCatalog.Provider> enabled = providerCatalog.enabledProviders();
        AiProviderCatalog.Provider primary = new AiProviderCatalog.Provider(providerName, "", modelCode);
        List<AiProviderCatalog.Provider> ordered = new ArrayList<>();
        ordered.add(primary);
        enabled.stream()
                .filter(provider -> !provider.name().equals(providerName))
                .forEach(ordered::add);
        return ordered;
    }

    /**
     * Ensures the server-side subject owns the supplied plan.
     */
    private static void validateSubject(AgentRunPlanResponse plan, RequestSubject subject) {
        if (!safeText(plan.tenantId()).equals(subject.tenantId())
                || !safeText(plan.subjectType()).equals(subject.subjectType())
                || !safeText(plan.subjectId()).equals(safeText(subject.subjectId()))) {
            throw new IllegalArgumentException("Agent plan subject mismatch");
        }
        if (subject.subjectId() == null || subject.subjectId().isBlank()) {
            throw new IllegalArgumentException("Agent execution requires authenticated subject");
        }
    }

    /**
     * Rechecks the frontend-returned plan against server-side agent policy before tracing execution.
     */
    private static void validatePlanPolicy(AgentRunPlanResponse plan, RequestSubject subject) {
        AgentRunPolicy.AgentDefinition agent = AgentRunPolicy.agentByCode(safeText(plan.agentCode()));
        if (!agent.allowedRoles().contains(subject.subjectType())) {
            throw new IllegalArgumentException("Agent subject not allowed: " + subject.subjectType());
        }
        List<String> toolViolations = safeList(plan.allowedToolScopes()).stream()
                .filter(scope -> !agent.allowedToolScopes().contains(scope))
                .toList();
        if (!toolViolations.isEmpty()) {
            throw new IllegalArgumentException("Agent plan tool scope not allowed: " + toolViolations);
        }
        Set<String> disabledByUser = safeToolDecisions(plan).stream()
                .filter(decision -> "DISABLED_BY_USER".equals(decision.decision()))
                .map(AgentRunPlanResponse.ToolPolicyDecision::scope)
                .collect(java.util.stream.Collectors.toSet());
        List<String> disabledViolations = safeList(plan.allowedToolScopes()).stream()
                .filter(disabledByUser::contains)
                .toList();
        if (!disabledViolations.isEmpty()) {
            throw new IllegalArgumentException("Agent plan tool scope disabled by user: " + disabledViolations);
        }
        List<String> dataViolations = safeList(plan.allowedDataScopes()).stream()
                .filter(scope -> !agent.allowedDataScopes().contains(scope))
                .toList();
        if (!dataViolations.isEmpty()) {
            throw new IllegalArgumentException("Agent plan data scope not allowed: " + dataViolations);
        }
    }

    /**
     * Returns stripped text or an empty string.
     */
    private static String safeText(String value) {
        return value == null || value.isBlank() ? "" : value.strip();
    }

    /**
     * Normalizes owner-facing generated content without writing it to generic trace rows.
     */
    private static String safeGeneratedContent(String value) {
        return value == null || value.isBlank() ? "" : value.strip();
    }

    /**
     * Returns a null-safe stripped immutable list.
     */
    private static List<String> safeList(List<String> values) {
        return values == null ? List.of() : values.stream().map(AgentRunExecutionService::safeText).toList();
    }

    /**
     * Returns null-safe tool policy decisions from a frontend-returned plan snapshot.
     */
    private static List<AgentRunPlanResponse.ToolPolicyDecision> safeToolDecisions(AgentRunPlanResponse plan) {
        return plan.toolPolicyDecisions() == null ? List.of() : plan.toolPolicyDecisions();
    }

    /**
     * Builds one safe model-call diagnostic event without raw prompt or generated output.
     */
    private static AgentTraceRecord.DiagnosticEvent diagnosticEvent(
            String eventType,
            String providerName,
            String modelCode,
            int attemptNo,
            boolean retryable,
            String message) {
        return new AgentTraceRecord.DiagnosticEvent(
                eventType,
                providerName,
                modelCode,
                attemptNo,
                retryable,
                safeText(message));
    }

    /**
     * Lightweight execution timer for trace-level monitoring.
     */
    private static final class StageTimer {

        private final List<AgentRunExecuteResponse.StageTiming> timings = new ArrayList<>();
        private long lastNanos = System.nanoTime();

        /**
         * Records elapsed time since the previous marker.
         */
        void mark(String stage) {
            long now = System.nanoTime();
            timings.add(new AgentRunExecuteResponse.StageTiming(stage, Math.max(0L, (now - lastNanos) / 1_000_000L)));
            lastNanos = now;
        }

        /**
         * Returns immutable timing rows.
         */
        List<AgentRunExecuteResponse.StageTiming> timings() {
            return List.copyOf(timings);
        }
    }

    /**
     * Internal execution outcome used to keep response construction uniform.
     */
    private record ExecutionOutcome(
            String providerName,
            String modelCode,
            int promptTokens,
            int completionTokens,
            int totalTokens,
            String message,
            String generatedContent,
            List<AgentTraceRecord.DiagnosticEvent> diagnosticEvents) {

    }

    /**
     * JSON validation result for model-output repair loops.
     */
    private record JsonValidationResult(boolean valid, String error) {

        /**
         * Successful validation.
         */
        private static JsonValidationResult ok() {
            return new JsonValidationResult(true, "");
        }

        /**
         * Failed validation with a safe short reason.
         */
        private static JsonValidationResult failed(String error) {
            return new JsonValidationResult(false, safeText(error));
        }
    }
}
