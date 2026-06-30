package com.doob.mathagent.agent.controller;

import com.doob.mathagent.agent.dto.AgentTraceQueryRequest;
import com.doob.mathagent.agent.service.AgentTraceQueryService;
import com.doob.mathagent.agent.vo.AgentTraceDiagnosticSummaryResponse;
import com.doob.mathagent.agent.vo.AgentTraceResponse;
import com.doob.mathagent.agent.vo.AgentTraceUsageSummaryResponse;
import com.doob.mathagent.infrastructure.security.RequestSubject;
import com.doob.mathagent.infrastructure.security.RequestSubjectResolver;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * API for recovering and monitoring agent execution traces.
 */
@RestController
public class AgentTraceController {

    private final AgentTraceQueryService traceQueryService;
    private final RequestSubjectResolver subjectResolver;

    /**
     * Creates the trace controller.
     *
     * @param traceQueryService trace query service
     * @param subjectResolver backend subject resolver
     */
    public AgentTraceController(
            AgentTraceQueryService traceQueryService,
            RequestSubjectResolver subjectResolver) {
        this.traceQueryService = traceQueryService;
        this.subjectResolver = subjectResolver;
    }

    /**
     * Lists traces visible to the backend subject.
     *
     * @param query optional query filters
     * @param httpRequest HTTP request used only for backend subject resolution
     * @return visible trace rows
     */
    @GetMapping("/api/agents/traces")
    public List<AgentTraceResponse> list(
            @ModelAttribute AgentTraceQueryRequest query,
            HttpServletRequest httpRequest) {
        RequestSubject subject = subjectResolver.resolve(httpRequest);
        try {
            return traceQueryService.list(query, subject);
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, exception.getMessage(), exception);
        }
    }

    /**
     * Summarizes provider-reported token usage for traces visible to the backend subject.
     *
     * @param query optional query filters
     * @param httpRequest HTTP request used only for backend subject resolution
     * @return visible usage summary
     */
    @GetMapping("/api/agents/traces/usage-summary")
    public AgentTraceUsageSummaryResponse usageSummary(
            @ModelAttribute AgentTraceQueryRequest query,
            HttpServletRequest httpRequest) {
        RequestSubject subject = subjectResolver.resolve(httpRequest);
        try {
            return traceQueryService.usageSummary(query, subject);
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, exception.getMessage(), exception);
        }
    }

    /**
     * Summarizes safe retry/fallback/parse diagnostics for traces visible to the backend subject.
     *
     * @param query optional query filters
     * @param httpRequest HTTP request used only for backend subject resolution
     * @return visible diagnostic summary
     */
    @GetMapping("/api/agents/traces/diagnostic-summary")
    public AgentTraceDiagnosticSummaryResponse diagnosticSummary(
            @ModelAttribute AgentTraceQueryRequest query,
            HttpServletRequest httpRequest) {
        RequestSubject subject = subjectResolver.resolve(httpRequest);
        try {
            return traceQueryService.diagnosticSummary(query, subject);
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, exception.getMessage(), exception);
        }
    }

    /**
     * Reads one trace when it is visible to the backend subject.
     *
     * @param traceId trace id
     * @param httpRequest HTTP request used only for backend subject resolution
     * @return visible trace
     */
    @GetMapping("/api/agents/traces/{traceId}")
    public AgentTraceResponse get(
            @PathVariable String traceId,
            HttpServletRequest httpRequest) {
        RequestSubject subject = subjectResolver.resolve(httpRequest);
        try {
            return traceQueryService.find(traceId, subject)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Agent trace not found"));
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, exception.getMessage(), exception);
        }
    }
}
