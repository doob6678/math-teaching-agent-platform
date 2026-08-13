package com.doob.mathagent.agent.service;

import com.doob.mathagent.agent.dto.AgentRunExecuteRequest;
import com.doob.mathagent.agent.vo.AgentRunExecuteResponse;
import com.doob.mathagent.agent.vo.AgentRunPlanResponse;
import com.doob.mathagent.infrastructure.ai.AiProviderCatalog;
import com.doob.mathagent.infrastructure.security.RequestSubject;
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
 * 执行已签发的 Agent 运行。
 *
 * <p>Java 保留身份、策略、预算、并发 lease 和 trace；模型调用、provider 回退、输出修复及 usage 记账只经
 * {@link AgentRunClient} 交给 Python Worker 执行。</p>
 */
@Service
public class AgentRunExecutionService {

    private static final String COMPLETED = "COMPLETED";
    private static final String RUNNING = "RUNNING";
    private static final Duration CONCURRENCY_LEASE_TIME = Duration.ofMinutes(10);

    private final AgentTraceStore traceStore;
    private final AgentConcurrencyGuard concurrencyGuard;
    private final AgentRunClient agentRunClient;
    private final AiProviderCatalog providerCatalog;
    private final Clock clock;

    /** 创建生产执行服务，默认注入唯一的 Python Worker 协议实现。 */
    @Autowired
    public AgentRunExecutionService(
            AgentTraceStore traceStore,
            AgentConcurrencyGuard concurrencyGuard,
            AgentRunClient agentRunClient,
            AiProviderCatalog providerCatalog) {
        this(traceStore, concurrencyGuard, agentRunClient, providerCatalog, Clock.systemUTC());
    }

    /** 为受控 facade 合同测试提供可注入时钟。 */
    public AgentRunExecutionService(
            AgentTraceStore traceStore,
            AgentConcurrencyGuard concurrencyGuard,
            AgentRunClient agentRunClient,
            AiProviderCatalog providerCatalog,
            Clock clock) {
        this.traceStore = traceStore;
        this.concurrencyGuard = concurrencyGuard;
        this.agentRunClient = agentRunClient;
        this.providerCatalog = providerCatalog;
        this.clock = clock;
    }

    /**
     * 执行一次已计划的运行，所有身份字段均由 Java 认证主体复验。
     */
    public AgentRunExecuteResponse execute(AgentRunExecuteRequest request, RequestSubject subject) {
        StageTimer timer = new StageTimer();
        AgentRunExecuteRequest normalized = request.normalize();
        AgentRunPlanResponse plan = normalized.plan();
        RequestSubject normalizedSubject = subject.normalize();
        validateSubject(plan, normalizedSubject);
        validatePlanPolicy(plan, normalizedSubject);
        if (!plan.withinBudget()) {
            throw new AgentBudgetExceededException(
                    "Agent execution rejected before provider call: token or configured cost budget exceeded");
        }
        timer.mark("subject_policy_guard");

        String traceId = UUID.randomUUID().toString();
        List<String> concurrencyKeys = safeList(plan.concurrencyKeys());
        AgentConcurrencyLease lease = concurrencyGuard.tryAcquire(concurrencyKeys, traceId, CONCURRENCY_LEASE_TIME)
                .orElseThrow(() -> new IllegalStateException("Agent concurrency limit exceeded"));
        timer.mark("concurrency_guard");
        try {
            timer.mark("trace_start");
            // Persist authorization before the Worker may request a protected Java tool.  The broker resolves the
            // subject from this opaque trace id, so neither the model nor the Worker needs tenant/user fields.
            traceStore.save(runningTrace(traceId, plan, normalizedSubject, normalized, concurrencyKeys));
            if (normalized.dryRun()) {
                throw new IllegalArgumentException("Agent dryRun is disabled in production");
            }
            AgentRunClient.Result outcome = agentRunClient.execute(traceId, normalized, plan);
            enforceActualUsageBudget(plan, outcome.actualUsage());
            timer.mark("python_ai_run");
            timer.mark("trace_finish");

            AgentTraceRecord finalRecord = new AgentTraceRecord(
                    traceId,
                    safeText(plan.planId()),
                    Instant.now(clock),
                    normalizedSubject.tenantId(),
                    normalizedSubject.subjectType(),
                    normalizedSubject.subjectId(),
                    safeText(plan.agentCode()),
                    safeText(outcome.providerName()),
                    safeText(outcome.modelCode()),
                    COMPLETED,
                    plan.estimatedCost(),
                    safeList(plan.allowedToolScopes()),
                    safeList(plan.allowedDataScopes()),
                    safeList(normalized.evidenceRefs()),
                    timer.timings(),
                    outcome.actualUsage(),
                    safeText(outcome.message()),
                    List.of(new AgentTraceRecord.DiagnosticEvent(
                            "PYTHON_AI_RUN_SUCCEEDED",
                            safeText(outcome.providerName()),
                            safeText(outcome.modelCode()),
                            0,
                            false,
                            "Python AI worker completed the versioned generic run.")),
                    outcome.actualCost(),
                    outcome.costKnown());
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
                    safeText(outcome.generatedContent()),
                    finalRecord.actualCost(),
                    finalRecord.costKnown());
        } finally {
            lease.close();
        }
    }

    /** Creates the short-lived durable authorization record consumed by the internal Java tool broker. */
    private AgentTraceRecord runningTrace(
            String traceId,
            AgentRunPlanResponse plan,
            RequestSubject subject,
            AgentRunExecuteRequest request,
            List<String> concurrencyKeys) {
        return new AgentTraceRecord(
                traceId,
                safeText(plan.planId()),
                Instant.now(clock),
                subject.tenantId(),
                subject.subjectType(),
                subject.subjectId(),
                safeText(plan.agentCode()),
                safeText(plan.providerName()),
                safeText(plan.modelCode()),
                RUNNING,
                plan.estimatedCost(),
                safeList(plan.allowedToolScopes()),
                safeList(plan.allowedDataScopes()),
                safeList(request.evidenceRefs()),
                List.of(),
                new AgentRunExecuteResponse.TokenUsage(0, 0, 0),
                "Agent run is authorized and awaiting the Python Worker.",
                List.of(new AgentTraceRecord.DiagnosticEvent(
                        "PYTHON_AI_RUN_STARTED",
                        safeText(plan.providerName()),
                        safeText(plan.modelCode()),
                        0,
                        false,
                        "The backend persisted the subject authorization before any Worker tool call.")),
                -1.0d,
                false);
    }

    /** Python 返回的实际 usage 仍受 Java 已签发 token 限额约束。 */
    private static void enforceActualUsageBudget(
            AgentRunPlanResponse plan, AgentRunExecuteResponse.TokenUsage usage) {
        if (usage == null || usage.promptTokens() < 0 || usage.completionTokens() < 0 || usage.totalTokens() < 0
                || usage.promptTokens() > plan.maxInputTokens() || usage.completionTokens() > plan.maxOutputTokens()) {
            throw new AgentBudgetExceededException(
                    "Agent execution stopped after Python usage exceeded the signed token budget");
        }
    }

    /** 确保服务端身份拥有前端携带的 plan 快照。 */
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

    /** 在发出 Worker 请求前重新验证 agent、scope 和 provider/model allow-list。 */
    private void validatePlanPolicy(AgentRunPlanResponse plan, RequestSubject subject) {
        AgentRunPolicy.AgentDefinition agent = AgentRunPolicy.agentByCode(safeText(plan.agentCode()));
        if (!agent.allowedRoles().contains(subject.subjectType())) {
            throw new IllegalArgumentException("Agent subject not allowed: " + subject.subjectType());
        }
        if (providerCatalog.preferredProvider(plan.providerName(), plan.modelCode()).isEmpty()) {
            throw new IllegalArgumentException("Agent plan provider/model is not enabled");
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

    private static String safeText(String value) {
        return value == null || value.isBlank() ? "" : value.strip();
    }

    private static List<String> safeList(List<String> values) {
        return values == null ? List.of() : values.stream().map(AgentRunExecutionService::safeText).toList();
    }

    private static List<AgentRunPlanResponse.ToolPolicyDecision> safeToolDecisions(AgentRunPlanResponse plan) {
        return plan.toolPolicyDecisions() == null ? List.of() : plan.toolPolicyDecisions();
    }

    /** 记录 trace 各阶段的相对耗时。 */
    private static final class StageTimer {

        private final List<AgentRunExecuteResponse.StageTiming> timings = new ArrayList<>();
        private long lastNanos = System.nanoTime();

        void mark(String stage) {
            long now = System.nanoTime();
            timings.add(new AgentRunExecuteResponse.StageTiming(
                    stage, Math.max(0L, (now - lastNanos) / 1_000_000L)));
            lastNanos = now;
        }

        List<AgentRunExecuteResponse.StageTiming> timings() {
            return List.copyOf(timings);
        }
    }
}
