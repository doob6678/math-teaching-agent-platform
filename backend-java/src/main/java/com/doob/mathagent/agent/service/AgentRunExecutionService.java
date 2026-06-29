package com.doob.mathagent.agent.service;

import com.doob.mathagent.agent.dto.AgentRunExecuteRequest;
import com.doob.mathagent.agent.vo.AgentRunExecuteResponse;
import com.doob.mathagent.agent.vo.AgentRunPlanResponse;
import com.doob.mathagent.infrastructure.security.RequestSubject;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Records a baseline AI agent execution trace without invoking external model providers yet.
 */
@Service
public class AgentRunExecutionService {

    private static final String COMPLETED = "COMPLETED";
    private static final Duration CONCURRENCY_LEASE_TIME = Duration.ofMinutes(10);

    private final AgentTraceStore traceStore;
    private final AgentConcurrencyGuard concurrencyGuard;
    private final Clock clock;

    /**
     * Creates the production execution service.
     *
     * @param traceStore trace storage boundary
     */
    @Autowired
    public AgentRunExecutionService(AgentTraceStore traceStore, AgentConcurrencyGuard concurrencyGuard) {
        this(traceStore, concurrencyGuard, Clock.systemUTC());
    }

    /**
     * Creates a service with local concurrency protection for tests that only provide a trace store.
     *
     * @param traceStore trace storage boundary
     */
    public AgentRunExecutionService(AgentTraceStore traceStore) {
        this(traceStore, new InMemoryAgentConcurrencyGuard(), Clock.systemUTC());
    }

    /**
     * Creates a testable execution service.
     *
     * @param traceStore trace storage boundary
     * @param clock clock used for trace timestamps
     */
    public AgentRunExecutionService(AgentTraceStore traceStore, Clock clock) {
        this(traceStore, new InMemoryAgentConcurrencyGuard(), clock);
    }

    /**
     * Creates a testable execution service with explicit dependencies.
     *
     * @param traceStore trace storage boundary
     * @param concurrencyGuard concurrency guard
     * @param clock clock used for trace timestamps
     */
    public AgentRunExecutionService(
            AgentTraceStore traceStore,
            AgentConcurrencyGuard concurrencyGuard,
            Clock clock) {
        this.traceStore = traceStore;
        this.concurrencyGuard = concurrencyGuard;
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
     * Executes the baseline run by validating ownership and writing a trace record.
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
                safeList(normalized.evidenceRefs()));
        try {
            timer.mark("trace_start");

            traceStore.save(record);
            timer.mark("baseline_execute");
            timer.mark("trace_finish");

            return new AgentRunExecuteResponse(
                    record.traceId(),
                    record.planId(),
                    record.tenantId(),
                    record.subjectType(),
                    record.subjectId(),
                    record.agentCode(),
                    record.providerName(),
                    record.modelCode(),
                    record.status(),
                    record.estimatedCost(),
                    record.allowedToolScopes(),
                    record.allowedDataScopes(),
                    concurrencyKeys,
                    timer.timings(),
                    "Baseline trace recorded; external model execution is not enabled yet.");
        } finally {
            lease.close();
        }
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
     * Returns a null-safe stripped immutable list.
     */
    private static List<String> safeList(List<String> values) {
        return values == null ? List.of() : values.stream().map(AgentRunExecutionService::safeText).toList();
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
}
