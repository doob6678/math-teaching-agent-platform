package com.doob.mathagent.agent.service;

import com.doob.mathagent.agent.dto.AgentRunPlanRequest;
import com.doob.mathagent.agent.vo.AgentRunPlanResponse;
import com.doob.mathagent.infrastructure.ai.AiProviderCatalog;
import com.doob.mathagent.infrastructure.security.RequestSubject;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Builds audited AI agent execution plans without calling external model providers.
 */
@Service
public class AgentRunPlanService {

    private static final List<AgentDefinition> AGENTS = List.of(
            new AgentDefinition(
                    "StudentTutorAgent",
                    Set.of("student"),
                    Set.of("tool:search:textbook", "tool:student:progress:read"),
                    Set.of("PUBLIC_TEXTBOOK", "STUDENT_PRIVATE", "MATH_VIP"),
                    false),
            new AgentDefinition(
                    "TeacherAssistantAgent",
                    Set.of("teacher", "admin"),
                    Set.of("tool:search:textbook", "tool:search:private", "tool:student:progress:read"),
                    Set.of("PUBLIC_TEXTBOOK", "TEACHER_PRIVATE", "CLASS_AUTHORIZED"),
                    false),
            new AgentDefinition(
                    "CoursewareAgent",
                    Set.of("teacher", "admin"),
                    Set.of("tool:courseware:generate", "tool:search:private", "tool:search:textbook"),
                    Set.of("PUBLIC_TEXTBOOK", "TEACHER_PRIVATE", "CLASS_AUTHORIZED"),
                    true),
            new AgentDefinition(
                    "QualityCheckAgent",
                    Set.of("teacher", "admin"),
                    Set.of("tool:quality:check"),
                    Set.of("PUBLIC_TEXTBOOK", "TEACHER_PRIVATE", "CLASS_AUTHORIZED"),
                    false));

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
        AgentDefinition agent = resolveAgent(normalized, normalizedSubject);
        List<String> allowedTools = allowed(normalized.requestedToolScopes(), agent.allowedToolScopes());
        List<String> deniedTools = denied(normalized.requestedToolScopes(), agent.allowedToolScopes());
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
     * Resolves the requested agent and verifies that the backend subject role may use it.
     */
    private static AgentDefinition resolveAgent(AgentRunPlanRequest request, RequestSubject subject) {
        String requested = request.agentCode().isBlank() ? defaultAgent(request, subject) : request.agentCode();
        AgentDefinition agent = AGENTS.stream()
                .filter(candidate -> candidate.code().equals(requested))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unsupported agent code: " + requested));
        if (!agent.allowedRoles().contains(subject.subjectType())) {
            throw new IllegalArgumentException("Agent subject not allowed: " + subject.subjectType());
        }
        return agent;
    }

    /**
     * Selects a conservative default agent when callers omit an explicit agent code.
     */
    private static String defaultAgent(AgentRunPlanRequest request, RequestSubject subject) {
        if ("teacher".equals(subject.subjectType()) || "admin".equals(subject.subjectType())) {
            return request.taskType().contains("courseware") ? "CoursewareAgent" : "TeacherAssistantAgent";
        }
        return "StudentTutorAgent";
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
                : providerCatalog.defaultProvider();
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
        String reason = request.previousFailureCount() >= 2 && providers.size() > 1
                ? "fallback after repeated failures"
                : request.taskType() + " uses " + level + " model";
        return new RouteDecision(provider, level, reason);
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
     * Builds Redis-style concurrency keys for later executor stages.
     */
    private static List<String> concurrencyKeys(RequestSubject subject, String agentCode, String modelCode) {
        return List.of(
                "concurrent:user:" + subject.subjectId() + ":" + agentCode,
                "concurrent:tenant:" + subject.tenantId() + ":" + agentCode,
                "concurrent:model:" + modelCode);
    }

    /**
     * Static agent definition used until definitions move to MySQL.
     */
    private record AgentDefinition(
            String code,
            Set<String> allowedRoles,
            Set<String> allowedToolScopes,
            Set<String> allowedDataScopes,
            boolean highValueRequired) {
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
