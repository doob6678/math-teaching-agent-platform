package com.doob.mathagent.agent.service;

import com.doob.mathagent.agent.dto.AgentTraceQueryRequest;
import com.doob.mathagent.agent.vo.AgentRunExecuteResponse;
import com.doob.mathagent.agent.vo.AgentTraceResponse;
import com.doob.mathagent.agent.vo.AgentTraceUsageSummaryResponse;
import com.doob.mathagent.infrastructure.security.RequestSubject;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import org.springframework.stereotype.Service;

/**
 * Query service for agent traces with backend-subject data isolation.
 */
@Service
public class AgentTraceQueryService {

    private final AgentTraceStore traceStore;

    /**
     * Creates a trace query service.
     *
     * @param traceStore trace store
     */
    public AgentTraceQueryService(AgentTraceStore traceStore) {
        this.traceStore = traceStore;
    }

    /**
     * Lists traces visible to the backend subject.
     *
     * @param request query request without identity fields
     * @param subject backend subject
     * @return visible traces
     */
    public List<AgentTraceResponse> list(AgentTraceQueryRequest request, RequestSubject subject) {
        return visibleRecords(request, subject).stream()
                .map(AgentTraceQueryService::toResponse)
                .sorted(Comparator.comparing(AgentTraceResponse::traceId))
                .toList();
    }

    /**
     * Summarizes provider-reported usage for traces visible to the backend subject.
     *
     * @param request query request without identity fields
     * @param subject backend subject
     * @return aggregated usage summary
     */
    public AgentTraceUsageSummaryResponse usageSummary(AgentTraceQueryRequest request, RequestSubject subject) {
        AgentTraceQueryRequest normalizedRequest = request == null
                ? new AgentTraceQueryRequest(null, null, null).normalize()
                : request.normalize();
        RequestSubject normalizedSubject = subject.normalize();
        List<AgentTraceRecord> records = visibleRecords(normalizedRequest, normalizedSubject);
        int promptTokens = records.stream().mapToInt(trace -> trace.actualUsage().promptTokens()).sum();
        int completionTokens = records.stream().mapToInt(trace -> trace.actualUsage().completionTokens()).sum();
        int totalTokens = records.stream().mapToInt(trace -> trace.actualUsage().totalTokens()).sum();
        return new AgentTraceUsageSummaryResponse(
                normalizedSubject.tenantId(),
                normalizedSubject.subjectType(),
                normalizedSubject.subjectId(),
                normalizedRequest.agentCode(),
                normalizedRequest.status(),
                records.size(),
                new AgentRunExecuteResponse.TokenUsage(promptTokens, completionTokens, totalTokens),
                modelUsages(records));
    }

    /**
     * Searches records visible to the backend subject using the same scoping for list and summaries.
     */
    private List<AgentTraceRecord> visibleRecords(AgentTraceQueryRequest request, RequestSubject subject) {
        AgentTraceQueryRequest normalizedRequest = request == null
                ? new AgentTraceQueryRequest(null, null, null).normalize()
                : request.normalize();
        RequestSubject normalizedSubject = subject.normalize();
        requireAuthenticated(normalizedSubject);
        boolean admin = "admin".equals(normalizedSubject.subjectType());
        AgentTraceSearchCriteria criteria = new AgentTraceSearchCriteria(
                normalizedSubject.tenantId(),
                admin ? null : normalizedSubject.subjectType(),
                admin ? null : normalizedSubject.subjectId(),
                normalizedRequest.agentCode(),
                normalizedRequest.status(),
                normalizedRequest.planId(),
                normalizedRequest.limit());
        return traceStore.search(criteria);
    }

    /**
     * Finds one trace when the backend subject is allowed to see it.
     *
     * @param traceId trace id
     * @param subject backend subject
     * @return trace response when visible
     */
    public Optional<AgentTraceResponse> find(String traceId, RequestSubject subject) {
        RequestSubject normalizedSubject = subject.normalize();
        requireAuthenticated(normalizedSubject);
        return traceStore.find(traceId)
                .filter(trace -> canView(trace, normalizedSubject))
                .map(AgentTraceQueryService::toResponse);
    }

    /**
     * Requires a real backend subject id.
     */
    private static void requireAuthenticated(RequestSubject subject) {
        if (subject.subjectId() == null || subject.subjectId().isBlank()) {
            throw new IllegalArgumentException("Agent trace query requires authenticated subject");
        }
    }

    /**
     * Checks tenant and owner visibility for a trace.
     */
    private static boolean canView(AgentTraceRecord trace, RequestSubject subject) {
        if (!trace.tenantId().equals(subject.tenantId())) {
            return false;
        }
        return "admin".equals(subject.subjectType())
                || (trace.subjectType().equals(subject.subjectType()) && trace.subjectId().equals(subject.subjectId()));
    }

    /**
     * Converts a trace record to a safe response.
     */
    private static AgentTraceResponse toResponse(AgentTraceRecord trace) {
        return new AgentTraceResponse(
                trace.traceId(),
                trace.planId(),
                trace.createdAt(),
                trace.tenantId(),
                trace.subjectType(),
                trace.subjectId(),
                trace.agentCode(),
                trace.providerName(),
                trace.modelCode(),
                trace.status(),
                trace.estimatedCost(),
                trace.allowedToolScopes(),
                trace.allowedDataScopes(),
                trace.evidenceRefs(),
                trace.stageTimings(),
                trace.actualUsage(),
                trace.message(),
                trace.diagnosticEvents().stream()
                        .map(event -> new AgentTraceResponse.DiagnosticEvent(
                                event.eventType(),
                                event.providerName(),
                                event.modelCode(),
                                event.attemptNo(),
                                event.retryable(),
                                event.message()))
                        .toList());
    }

    /**
     * Aggregates usage by provider/model, sorted by largest token usage first for monitoring.
     */
    private static List<AgentTraceUsageSummaryResponse.ModelUsage> modelUsages(List<AgentTraceRecord> records) {
        Map<String, UsageAccumulator> buckets = new TreeMap<>();
        records.forEach(trace -> {
            String key = trace.providerName() + "\u0000" + trace.modelCode();
            buckets.computeIfAbsent(key, ignored -> new UsageAccumulator(trace.providerName(), trace.modelCode()))
                    .add(trace.actualUsage());
        });
        return buckets.values().stream()
                .map(UsageAccumulator::toResponse)
                .sorted(Comparator.comparingInt(AgentTraceUsageSummaryResponse.ModelUsage::totalTokens).reversed()
                        .thenComparing(AgentTraceUsageSummaryResponse.ModelUsage::providerName)
                        .thenComparing(AgentTraceUsageSummaryResponse.ModelUsage::modelCode))
                .toList();
    }

    /**
     * Mutable local counter used only while building one response.
     */
    private static final class UsageAccumulator {
        private final String providerName;
        private final String modelCode;
        private int runCount;
        private int promptTokens;
        private int completionTokens;
        private int totalTokens;

        /**
         * Creates a usage accumulator for one provider/model bucket.
         */
        private UsageAccumulator(String providerName, String modelCode) {
            this.providerName = providerName;
            this.modelCode = modelCode;
        }

        /**
         * Adds one provider-reported usage row.
         */
        private void add(AgentRunExecuteResponse.TokenUsage usage) {
            runCount += 1;
            promptTokens += usage.promptTokens();
            completionTokens += usage.completionTokens();
            totalTokens += usage.totalTokens();
        }

        /**
         * Converts the counter into a safe response row.
         */
        private AgentTraceUsageSummaryResponse.ModelUsage toResponse() {
            return new AgentTraceUsageSummaryResponse.ModelUsage(
                    providerName,
                    modelCode,
                    runCount,
                    promptTokens,
                    completionTokens,
                    totalTokens);
        }
    }
}
