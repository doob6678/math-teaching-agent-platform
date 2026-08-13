package com.doob.mathagent.agent.service;

import com.doob.mathagent.agent.dto.AgentRunPlanRequest;
import com.doob.mathagent.agent.vo.AgentRunPlanResponse;
import com.doob.mathagent.infrastructure.ai.AiProviderCatalog;
import com.doob.mathagent.infrastructure.ai.AiModelPriceCatalog;
import com.doob.mathagent.infrastructure.security.RequestSubject;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Builds audited AI agent execution plans without calling external model providers.
 */
@Service
public class AgentRunPlanService {

    /** Free-tier output cap keeps interactive tutoring bounded. */
    private static final int FREE_MAX_OUTPUT_TOKENS = 900;
    /** Default paid output cap for short answers and student/projection variants. */
    private static final int DEFAULT_MAX_OUTPUT_TOKENS = 4000;
    /** A four-question teacher handout needs a larger but still signed and auditable completion budget. */
    private static final int TEACHER_HANDOUT_MAX_OUTPUT_TOKENS = 8000;
    /**
     * A concise teacher question branch can still report provider-side reasoning tokens in addition to its short
     * visible answer. This allowance is deliberately below the full handout cap, but above the standard interactive
     * cap so an audited Terra response is not discarded solely because the relay counts those reasoning tokens.
     */
    private static final int TEACHER_QUESTION_REASONING_MAX_OUTPUT_TOKENS = 6000;

    private final AiProviderCatalog providerCatalog;
    private final AiModelPriceCatalog priceCatalog;

    /**
     * Creates an agent run planner.
     *
     * @param providerCatalog configured provider catalog
     */
    public AgentRunPlanService(AiProviderCatalog providerCatalog) {
        this(providerCatalog, AiModelPriceCatalog.empty());
    }

    /** Creates a planner with deployment-owned model pricing. */
    @org.springframework.beans.factory.annotation.Autowired
    public AgentRunPlanService(AiProviderCatalog providerCatalog, AiModelPriceCatalog priceCatalog) {
        this.providerCatalog = providerCatalog;
        this.priceCatalog = priceCatalog;
    }

    /**
     * Builds a safe run plan from backend subject, agent policy, model route, and budget guard.
     *
     * @param request frontend planning request
     * @param subject backend authenticated subject
     * @return execution plan safe to show to the frontend
     */
    public AgentRunPlanResponse plan(AgentRunPlanRequest request, RequestSubject subject) {
        StageTimer timer = new StageTimer();
        AgentRunPlanRequest normalized = request.normalize();
        RequestSubject normalizedSubject = subject.normalize();
        AgentRunPolicy.AgentDefinition agent = AgentRunPolicy.resolveAgent(normalized, normalizedSubject);
        Set<String> disabledTools = new HashSet<>(normalized.disabledToolScopes());
        List<AgentRunPlanResponse.ToolPolicyDecision> toolDecisions =
                toolPolicyDecisions(normalized.requestedToolScopes(), agent.allowedToolScopes(), disabledTools);
        List<String> allowedTools = toolDecisions.stream()
                .filter(decision -> "ALLOWED".equals(decision.decision()))
                .map(AgentRunPlanResponse.ToolPolicyDecision::scope)
                .toList();
        List<String> deniedTools = toolDecisions.stream()
                .filter(decision -> !"ALLOWED".equals(decision.decision()))
                .map(AgentRunPlanResponse.ToolPolicyDecision::scope)
                .toList();
        List<String> allowedData = allowed(normalized.requestedDataScopes(), agent.allowedDataScopes());
        List<String> deniedData = denied(normalized.requestedDataScopes(), agent.allowedDataScopes());
        timer.mark("agent_policy");

        RouteDecision route = route(normalized);
        timer.mark("model_route");

        TokenBudget budget = budget(normalized, route.provider());
        timer.mark("budget_guard");

        return new AgentRunPlanResponse(
                UUID.randomUUID().toString(),
                normalizedSubject.tenantId(),
                normalizedSubject.subjectType(),
                normalizedSubject.subjectId(),
                agent.code(),
                route.provider().name(),
                route.provider().chatModel(),
                route.modelLevel(),
                allowedTools,
                deniedTools,
                toolDecisions,
                allowedData,
                deniedData,
                budget.maxInputTokens(),
                budget.maxOutputTokens(),
                budget.estimatedTotalTokens(),
                budget.estimatedCost(),
                budget.withinBudget(),
                route.reason(),
                timer.timings(),
                concurrencyKeys(normalizedSubject, agent.code(), route.provider().chatModel()),
                normalized.requiredJsonSchema());
    }

    /**
     * Routes to a provider and model capability level from task signals and fallback state.
     */
    private RouteDecision route(AgentRunPlanRequest request) {
        List<AiProviderCatalog.Provider> providers = providerCatalog.enabledProviders();
        if (providers.isEmpty()) {
            throw new IllegalStateException("No AI provider is enabled by environment variables");
        }
        boolean writing = isWritingRequest(request);
        AiProviderCatalog.Provider primary = writing
                ? writingProvider(request)
                : providerCatalog.preferredProvider(request.preferredProviderName(), request.preferredModelCode())
                        .orElseGet(providerCatalog::defaultProvider);
        AiProviderCatalog.Provider provider = !writing && request.previousFailureCount() >= 2 && providers.size() > 1
                ? fallbackAfter(primary, providers)
                : primary;
        String level;
        if (request.requiredJsonSchema()) {
            level = "json_stable";
        } else if (request.hasImage()) {
            level = "multimodal";
        } else if ("hard".equals(request.difficulty()) || request.hasFormula()) {
            level = "reasoning";
        } else {
            level = "fast_text";
        }
        String reason = routeReason(request, providers, level, provider);
        return new RouteDecision(provider, level, reason);
    }

    /** Identifies document-generation routes that use the default writing route unless explicitly overridden. */
    private static boolean isWritingRequest(AgentRunPlanRequest request) {
        String taskType = request.taskType() == null ? "" : request.taskType().toLowerCase(java.util.Locale.ROOT);
        String agentCode = request.agentCode() == null ? "" : request.agentCode();
        return taskType.contains("writing")
                || taskType.contains("handout")
                || "CoursewareAgent".equals(agentCode)
                || "HandoutFormatterAgent".equals(agentCode);
    }

    /**
     * Keeps every writing stage on one validated route for the whole workflow. An explicit model is honored only
     * after the provider catalog allow-list accepts it; an unknown explicit choice fails closed instead of silently
     * changing cost, quality, or reproducibility. The deployment Luna route remains the default when no preference
     * was supplied.
     *
     * @param request normalized agent plan request
     * @return the approved writing provider
     */
    private AiProviderCatalog.Provider writingProvider(AgentRunPlanRequest request) {
        if (!request.preferredProviderName().isBlank() || !request.preferredModelCode().isBlank()) {
            return providerCatalog.preferredProvider(request.preferredProviderName(), request.preferredModelCode())
                    .orElseThrow(() -> new IllegalArgumentException(
                            "Requested writing provider/model is not enabled or allow-listed"));
        }
        return providerCatalog.lunaWritingProvider();
    }

    /**
     * Selects the next configured provider after the provider that just failed.
     */
    private static AiProviderCatalog.Provider fallbackAfter(
            AiProviderCatalog.Provider primary,
            List<AiProviderCatalog.Provider> providers) {
        int primaryIndex = -1;
        for (int index = 0; index < providers.size(); index++) {
            if (providers.get(index).name().equals(primary.name())) {
                primaryIndex = index;
                break;
            }
        }
        if (primaryIndex < 0) {
            return providers.getFirst();
        }
        return providers.get((primaryIndex + 1) % providers.size());
    }

    /**
     * Builds a route reason without echoing untrusted model preference into SQL or logs.
     */
    private String routeReason(
            AgentRunPlanRequest request,
            List<AiProviderCatalog.Provider> providers,
            String level,
            AiProviderCatalog.Provider provider) {
        if (request.previousFailureCount() >= 2 && providers.size() > 1) {
            return "fallback after repeated failures";
        }
        if (provider.name().equals(request.preferredProviderName())
                && provider.chatModel().equals(request.preferredModelCode())) {
            return request.taskType() + " uses preferred model " + provider.name() + "/" + provider.chatModel();
        }
        if (!request.preferredProviderName().isBlank() || !request.preferredModelCode().isBlank()) {
            return request.taskType() + " ignored preferred model and uses " + level + " model";
        }
        return request.taskType() + " uses " + level + " model";
    }

    /**
     * Applies token ceilings and cost estimation before execution.
     */
    private TokenBudget budget(AgentRunPlanRequest request, AiProviderCatalog.Provider provider) {
        int maxInput = "free".equals(request.userVipLevel()) ? 2400 : 12000;
        int maxOutput = "free".equals(request.userVipLevel())
                ? FREE_MAX_OUTPUT_TOKENS
                : isTeacherHandoutRequest(request) ? TEACHER_HANDOUT_MAX_OUTPUT_TOKENS
                : isTeacherQuestionSolvingRequest(request) ? TEACHER_QUESTION_REASONING_MAX_OUTPUT_TOKENS
                : DEFAULT_MAX_OUTPUT_TOKENS;
        boolean inputWithinLimit = request.estimatedInputTokens() <= maxInput;
        boolean outputWithinLimit = request.estimatedOutputTokens() <= maxOutput;
        long clippedInput = Math.min(request.estimatedInputTokens(), maxInput);
        long clippedOutput = Math.min(request.estimatedOutputTokens(), maxOutput);
        long total = clippedInput + clippedOutput;
        double cost = priceCatalog.estimate(
                provider.name(), provider.chatModel(), (int) clippedInput, (int) clippedOutput);
        // Unknown relay pricing never disables token protection, but it also never masquerades as a real currency cost.
        boolean costWithinLimit = cost < 0.0d || cost <= request.costBudget();
        return new TokenBudget(
                maxInput,
                maxOutput,
                total,
                cost,
                inputWithinLimit && outputWithinLimit && costWithinLimit);
    }

    /**
     * Identifies the only writing branch allowed to use the expanded output ceiling.
     * Student and lecture variants remain on the normal paid cap so a malformed prompt cannot multiply spend.
     */
    private static boolean isTeacherHandoutRequest(AgentRunPlanRequest request) {
        return isWritingRequest(request)
                && "CoursewareAgent".equals(request.agentCode())
                && "teacher".equals(request.userVipLevel());
    }

    /**
     * Keeps the extra budget scoped to one evidence-isolated teacher branch rather than expanding every paid
     * question-answering request. The Java signature remains the Worker provider limit and the post-run audit gate.
     */
    private static boolean isTeacherQuestionSolvingRequest(AgentRunPlanRequest request) {
        return "question_solving".equals(request.taskType())
                && "TeacherAssistantAgent".equals(request.agentCode())
                && "teacher".equals(request.userVipLevel());
    }

    /**
     * Returns requested scopes accepted by policy.
     */
    private static List<String> allowed(List<String> requested, Set<String> allowed) {
        return requested.stream().filter(allowed::contains).toList();
    }

    /**
     * Returns requested scopes rejected by policy.
     */
    private static List<String> denied(List<String> requested, Set<String> allowed) {
        return requested.stream().filter(scope -> !allowed.contains(scope)).toList();
    }

    /**
     * Explains how every requested tool scope is handled before dynamic tool injection.
     */
    private static List<AgentRunPlanResponse.ToolPolicyDecision> toolPolicyDecisions(
            List<String> requested,
            Set<String> allowed,
            Set<String> disabled) {
        return requested.stream()
                .map(scope -> toolPolicyDecision(scope, allowed, disabled))
                .toList();
    }

    /**
     * Resolves one tool decision without trusting frontend role or identity.
     */
    private static AgentRunPlanResponse.ToolPolicyDecision toolPolicyDecision(
            String scope,
            Set<String> allowed,
            Set<String> disabled) {
        if (!allowed.contains(scope)) {
            return new AgentRunPlanResponse.ToolPolicyDecision(
                    scope,
                    "DENIED_BY_AGENT_POLICY",
                    "Tool is not allowed for the backend-selected agent and subject role");
        }
        if (disabled.contains(scope)) {
            return new AgentRunPlanResponse.ToolPolicyDecision(
                    scope,
                    "DISABLED_BY_USER",
                    "Tool was removed by this request's user preference");
        }
        return new AgentRunPlanResponse.ToolPolicyDecision(
                scope,
                "ALLOWED",
                "Tool is allowed by agent policy and not disabled by request preference");
    }

    /**
     * Builds Redis-style concurrency keys for later executor stages.
     */
    private static List<String> concurrencyKeys(RequestSubject subject, String agentCode, String modelCode) {
        return List.of(
                "concurrent:user:" + subject.subjectId() + ":" + agentCode,
                "concurrent:tenant:" + subject.tenantId() + ":" + agentCode,
                "concurrent:model:" + modelCode);
    }

    /**
     * Provider and model-level route decision.
     */
    private record RouteDecision(AiProviderCatalog.Provider provider, String modelLevel, String reason) {
    }

    /**
     * Token and cost budget after policy clipping.
     */
    private record TokenBudget(
            int maxInputTokens,
            int maxOutputTokens,
            long estimatedTotalTokens,
            double estimatedCost,
            boolean withinBudget) {
    }

    /**
     * Lightweight planning timer for stage-level monitoring.
     */
    private static final class StageTimer {

        private final List<AgentRunPlanResponse.StageTiming> timings = new ArrayList<>();
        private long lastNanos = System.nanoTime();

        /**
         * Records elapsed time since the previous marker.
         */
        void mark(String stage) {
            long now = System.nanoTime();
            timings.add(new AgentRunPlanResponse.StageTiming(stage, Math.max(0L, (now - lastNanos) / 1_000_000L)));
            lastNanos = now;
        }

        /**
         * Returns immutable timing rows.
         */
        List<AgentRunPlanResponse.StageTiming> timings() {
            return List.copyOf(timings);
        }
    }
}
