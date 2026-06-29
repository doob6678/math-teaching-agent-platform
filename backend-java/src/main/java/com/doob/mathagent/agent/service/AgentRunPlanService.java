package com.doob.mathagent.agent.service;

import com.doob.mathagent.agent.dto.AgentRunPlanRequest;
import com.doob.mathagent.agent.vo.AgentRunPlanResponse;
import com.doob.mathagent.infrastructure.ai.AiProviderCatalog;
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

    private final AiProviderCatalog providerCatalog;

    /**
     * Creates an agent run planner.
     *
     * @param providerCatalog configured provider catalog
     */
    public AgentRunPlanService(AiProviderCatalog providerCatalog) {
        this.providerCatalog = providerCatalog;
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

        TokenBudget budget = budget(normalized);
        timer.mark("budget_guard");

        boolean capabilityRequired = normalized.highValueOperation() || agent.highValueRequired();
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
                capabilityRequired,
                capabilityRequired ? "agent-run:" + agent.code() : "",
                budget.maxInputTokens(),
                budget.maxOutputTokens(),
                budget.estimatedTotalTokens(),
                budget.estimatedCost(),
                budget.withinBudget(),
                route.reason(),
                timer.timings(),
                concurrencyKeys(normalizedSubject, agent.code(), route.provider().chatModel()));
    }

    /**
     * Routes to a provider and model capability level from task signals and fallback state.
     */
    private RouteDecision route(AgentRunPlanRequest request) {
        List<AiProviderCatalog.Provider> providers = providerCatalog.enabledProviders();
        if (providers.isEmpty()) {
            throw new IllegalStateException("No AI provider is enabled by environment variables");
        }
        AiProviderCatalog.Provider provider = request.previousFailureCount() >= 2 && providers.size() > 1
                ? providers.get(1)
                : providerCatalog.preferredProvider(request.preferredProviderName(), request.preferredModelCode())
                .orElseGet(providerCatalog::defaultProvider);
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
    private static TokenBudget budget(AgentRunPlanRequest request) {
        int maxInput = "free".equals(request.userVipLevel()) ? 2400 : 12000;
        int maxOutput = "free".equals(request.userVipLevel()) ? 900 : 4000;
        long clippedInput = Math.min(request.estimatedInputTokens(), maxInput);
        long clippedOutput = Math.min(request.estimatedOutputTokens(), maxOutput);
        long total = clippedInput + clippedOutput;
        double cost = Math.round((total / 10000.0d) * 10000.0d) / 10000.0d;
        return new TokenBudget(maxInput, maxOutput, total, cost, cost <= request.costBudget());
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
