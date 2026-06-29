package com.doob.mathagent.agent.service;

import com.doob.mathagent.agent.dto.AgentTraceQueryRequest;
import com.doob.mathagent.agent.vo.AgentTraceResponse;
import com.doob.mathagent.infrastructure.security.RequestSubject;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
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
                normalizedRequest.limit());
        return traceStore.search(criteria).stream()
                .map(AgentTraceQueryService::toResponse)
                .sorted(Comparator.comparing(AgentTraceResponse::traceId))
                .toList();
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
                trace.message());
    }
}
