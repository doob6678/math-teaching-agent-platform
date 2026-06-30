package com.doob.mathagent.agent.controller;

import com.doob.mathagent.agent.dto.AgentTraceQueryRequest;
import com.doob.mathagent.agent.dto.MultiAgentWritingRequest;
import com.doob.mathagent.agent.service.AgentRunCapabilityVerifier;
import com.doob.mathagent.agent.service.AgentTraceQueryService;
import com.doob.mathagent.agent.vo.AgentRunExecuteResponse;
import com.doob.mathagent.agent.vo.AgentTraceResponse;
import com.doob.mathagent.agent.service.MultiAgentWritingService;
import com.doob.mathagent.agent.vo.MultiAgentWritingResponse;
import com.doob.mathagent.agent.vo.MultiAgentWritingTraceResponse;
import com.doob.mathagent.infrastructure.security.RequestSubject;
import com.doob.mathagent.infrastructure.security.RequestSubjectResolver;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.Comparator;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * API for protected multi-agent handout writing orchestration.
 */
@RestController
public class MultiAgentWritingController {

    private static final String PATH = "/api/agents/writing/courseware";
    private static final String ASYNC_PATH = "/api/agents/writing/courseware/async";

    private final MultiAgentWritingService writingService;
    private final AgentTraceQueryService traceQueryService;
    private final RequestSubjectResolver subjectResolver;
    private final AgentRunCapabilityVerifier capabilityVerifier;

    /**
     * Creates the controller.
     *
     * @param writingService multi-agent writing service
     * @param traceQueryService trace query service for workflow recovery
     * @param subjectResolver backend subject resolver
     * @param capabilityVerifier capability verifier for high-value writing
     */
    public MultiAgentWritingController(
            MultiAgentWritingService writingService,
            AgentTraceQueryService traceQueryService,
            RequestSubjectResolver subjectResolver,
            AgentRunCapabilityVerifier capabilityVerifier) {
        this.writingService = writingService;
        this.traceQueryService = traceQueryService;
        this.subjectResolver = subjectResolver;
        this.capabilityVerifier = capabilityVerifier;
    }

    /**
     * Runs protected multi-agent writing through backend-owned identity.
     *
     * @param request writing request
     * @param httpRequest HTTP request used only for trusted backend subject and capability headers
     * @return writing workflow result
     */
    @PostMapping(PATH)
    public MultiAgentWritingResponse run(
            @Valid @RequestBody MultiAgentWritingRequest request,
            HttpServletRequest httpRequest) {
        RequestSubject subject = subjectResolver.resolve(httpRequest);
        if (!capabilityVerifier.verify(
                headerOrEmpty(httpRequest, "X-Capability-Token"),
                writingService.capabilityAction(),
                PATH,
                headerOrEmpty(httpRequest, "X-Request-Hash"),
                subject)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Capability token required for multi-agent writing");
        }
        try {
            return writingService.run(request, subject);
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, exception.getMessage(), exception);
        } catch (IllegalStateException exception) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, exception.getMessage(), exception);
        }
    }

    /**
     * Starts protected multi-agent writing in the background and returns a recoverable workflow id immediately.
     *
     * @param request writing request
     * @param httpRequest HTTP request used only for trusted backend subject and capability headers
     * @return initial workflow status
     */
    @PostMapping(ASYNC_PATH)
    public MultiAgentWritingResponse startAsync(
            @Valid @RequestBody MultiAgentWritingRequest request,
            HttpServletRequest httpRequest) {
        RequestSubject subject = subjectResolver.resolve(httpRequest);
        if (!capabilityVerifier.verify(
                headerOrEmpty(httpRequest, "X-Capability-Token"),
                writingService.capabilityAction(),
                ASYNC_PATH,
                headerOrEmpty(httpRequest, "X-Request-Hash"),
                subject)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Capability token required for async multi-agent writing");
        }
        try {
            return writingService.startAsync(request, subject);
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, exception.getMessage(), exception);
        } catch (IllegalStateException exception) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, exception.getMessage(), exception);
        }
    }

    /**
     * Reads the latest safe workflow status for the backend subject.
     *
     * @param workflowId workflow id returned by the write endpoint
     * @param httpRequest HTTP request used only for trusted backend subject
     * @return latest workflow response
     */
    @GetMapping("/api/agents/writing/{workflowId}")
    public MultiAgentWritingResponse get(
            @PathVariable String workflowId,
            HttpServletRequest httpRequest) {
        RequestSubject subject = subjectResolver.resolve(httpRequest);
        try {
            return writingService.find(normalizedWorkflowId(workflowId), subject)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Multi-agent writing workflow not found"));
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, exception.getMessage(), exception);
        }
    }

    /**
     * Recovers safe stage traces for one visible multi-agent writing workflow.
     *
     * @param workflowId workflow id returned by the write endpoint
     * @param httpRequest HTTP request used only for trusted backend subject
     * @return safe ordered workflow trace response
     */
    @GetMapping("/api/agents/writing/{workflowId}/traces")
    public MultiAgentWritingTraceResponse traces(
            @PathVariable String workflowId,
            HttpServletRequest httpRequest) {
        RequestSubject subject = subjectResolver.resolve(httpRequest);
        try {
            return workflowTrace(workflowId, subject);
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, exception.getMessage(), exception);
        }
    }

    /**
     * Builds an ordered safe workflow trace response.
     */
    private MultiAgentWritingTraceResponse workflowTrace(String workflowId, RequestSubject subject) {
        String normalizedWorkflowId = normalizedWorkflowId(workflowId);
        List<AgentTraceResponse> stages = traceQueryService.list(
                        new AgentTraceQueryRequest(null, null, null, normalizedWorkflowId + ":", 20),
                        subject)
                .stream()
                .sorted(Comparator.comparingInt(MultiAgentWritingController::stageOrder)
                        .thenComparing(AgentTraceResponse::createdAt))
                .toList();
        if (stages.isEmpty()) {
            throw new IllegalArgumentException("Multi-agent writing workflow trace not found");
        }
        int promptTokens = stages.stream().mapToInt(stage -> stage.actualUsage().promptTokens()).sum();
        int completionTokens = stages.stream().mapToInt(stage -> stage.actualUsage().completionTokens()).sum();
        int totalTokens = stages.stream().mapToInt(stage -> stage.actualUsage().totalTokens()).sum();
        RequestSubject normalizedSubject = subject.normalize();
        return new MultiAgentWritingTraceResponse(
                normalizedWorkflowId,
                normalizedSubject.tenantId(),
                normalizedSubject.subjectType(),
                normalizedSubject.subjectId(),
                stages.size(),
                new AgentRunExecuteResponse.TokenUsage(promptTokens, completionTokens, totalTokens),
                stages);
    }

    /**
     * Validates workflow id shape before using it as a prefix query.
     */
    private static String normalizedWorkflowId(String workflowId) {
        if (workflowId == null || workflowId.isBlank()) {
            throw new IllegalArgumentException("workflowId is required");
        }
        String normalized = workflowId.strip();
        if (!normalized.matches("[A-Za-z0-9._:-]{8,80}")) {
            throw new IllegalArgumentException("workflowId is invalid");
        }
        return normalized;
    }

    /**
     * Sorts known writing stages into the execution order.
     */
    private static int stageOrder(AgentTraceResponse trace) {
        String planId = trace.planId() == null ? "" : trace.planId();
        if (planId.endsWith(":draft")) {
            return 0;
        }
        if (planId.endsWith(":review")) {
            return 1;
        }
        if (planId.endsWith(":format")) {
            return 2;
        }
        return 99;
    }

    /**
     * Reads a capability-related header.
     */
    private static String headerOrEmpty(HttpServletRequest request, String name) {
        if (request == null) {
            return "";
        }
        String value = request.getHeader(name);
        return value == null || value.isBlank() ? "" : value.strip();
    }
}
