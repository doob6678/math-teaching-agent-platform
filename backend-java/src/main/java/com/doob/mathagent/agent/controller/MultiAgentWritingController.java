package com.doob.mathagent.agent.controller;

import com.doob.mathagent.agent.dto.AgentTraceQueryRequest;
import com.doob.mathagent.agent.dto.MultiAgentWritingRequest;
import com.doob.mathagent.agent.service.AgentTraceQueryService;
import com.doob.mathagent.agent.service.HandoutTaskFacade;
import com.doob.mathagent.agent.service.MultiAgentWritingArtifact;
import com.doob.mathagent.agent.service.MultiAgentWritingArtifactExportService;
import com.doob.mathagent.agent.vo.AgentRunExecuteResponse;
import com.doob.mathagent.agent.vo.AgentTraceResponse;
import com.doob.mathagent.agent.service.MultiAgentWritingService;
import com.doob.mathagent.agent.vo.MultiAgentWritingArtifactExportResponse;
import com.doob.mathagent.agent.vo.MultiAgentWritingResponse;
import com.doob.mathagent.agent.vo.MultiAgentWritingTraceResponse;
import com.doob.mathagent.infrastructure.security.RequestSubject;
import com.doob.mathagent.infrastructure.security.RequestSubjectResolver;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.Comparator;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
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
    private static final String RESUME_PATH = "/api/agents/writing/{workflowId}/resume";
    private static final List<String> CONTROLLED_STAGE_CODES = List.of(
            "resource_curation", "template_selection", "outline_planning",
            "teacher_writer", "student_writer", "lecture_writer",
            "source_review", "student_safety_review", "layout_review", "merge_coordinator");
    private static final int UNKNOWN_STAGE_ORDER = 99;

    private final MultiAgentWritingService writingService;
    private final MultiAgentWritingArtifactExportService artifactExportService;
    private final AgentTraceQueryService traceQueryService;
    private final RequestSubjectResolver subjectResolver;
    /** Production compatibility traffic is projected from one teaching task, never a second workflow row. */
    @Autowired
    private HandoutTaskFacade handoutTaskFacade;

    /**
     * Creates the controller.
     *
     * @param writingService multi-agent writing service
     * @param traceQueryService trace query service for workflow recovery
     * @param subjectResolver backend subject resolver
     */
    @Autowired
    public MultiAgentWritingController(
            MultiAgentWritingService writingService,
            MultiAgentWritingArtifactExportService artifactExportService,
            AgentTraceQueryService traceQueryService,
            RequestSubjectResolver subjectResolver) {
        this.writingService = writingService;
        this.artifactExportService = artifactExportService;
        this.traceQueryService = traceQueryService;
        this.subjectResolver = subjectResolver;
    }

    /**
     * Backward-compatible constructor for direct controller tests.
     */
    public MultiAgentWritingController(
            MultiAgentWritingService writingService,
            AgentTraceQueryService traceQueryService,
            RequestSubjectResolver subjectResolver) {
        this(
                writingService,
                new MultiAgentWritingArtifactExportService(writingService, 30),
                traceQueryService,
                subjectResolver);
    }

    /**
     * Runs protected multi-agent writing through backend-owned identity.
     *
     * @param request writing request
     * @param httpRequest HTTP request used only for the backend-resolved request subject
     * @return writing workflow result
     */
    @PostMapping(PATH)
    public MultiAgentWritingResponse run(
            @Valid @RequestBody MultiAgentWritingRequest request,
            HttpServletRequest httpRequest) {
        RequestSubject subject = subjectResolver.resolve(httpRequest);
        try {
            return handoutTaskFacade == null
                    ? writingService.run(request, subject)
                    : handoutTaskFacade.submit(request, subject);
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
     * @param httpRequest HTTP request used only for the backend-resolved request subject
     * @return initial workflow status
     */
    @PostMapping(ASYNC_PATH)
    public MultiAgentWritingResponse startAsync(
            @Valid @RequestBody MultiAgentWritingRequest request,
            HttpServletRequest httpRequest) {
        RequestSubject subject = subjectResolver.resolve(httpRequest);
        try {
            return handoutTaskFacade == null
                    ? writingService.startAsync(request, subject)
                    : handoutTaskFacade.startAsync(request, subject);
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
            if (handoutTaskFacade != null) {
                return handoutTaskFacade.get(normalizedWorkflowId(workflowId), subject);
            }
            return writingService.find(normalizedWorkflowId(workflowId), subject)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Multi-agent writing workflow not found"));
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, exception.getMessage(), exception);
        }
    }

    /**
     * Reads owner-visible generated content for review and frontend preview.
     *
     * @param workflowId workflow id returned by the write endpoint
     * @param httpRequest HTTP request used only for trusted backend subject
     * @return merged and per-stage generated content
     */
    @GetMapping("/api/agents/writing/{workflowId}/artifact")
    public MultiAgentWritingArtifact artifact(
            @PathVariable String workflowId,
            HttpServletRequest httpRequest) {
        RequestSubject subject = subjectResolver.resolve(httpRequest);
        try {
            if (handoutTaskFacade != null) {
                return handoutTaskFacade.artifact(normalizedWorkflowId(workflowId), subject);
            }
            return writingService.artifact(normalizedWorkflowId(workflowId), subject);
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, exception.getMessage(), exception);
        }
    }

    /**
     * Exports owner-visible generated content as a temporary payload for download.
     *
     * @param workflowId workflow id returned by the write endpoint
     * @param format markdown, latex, zip, or one of the three audience-specific PDF variants
     * @param httpRequest HTTP request used only for trusted backend subject
     * @return base64 encoded export payload with checksum and expiration
     */
    @GetMapping("/api/agents/writing/{workflowId}/artifact/export")
    public MultiAgentWritingArtifactExportResponse exportArtifact(
            @PathVariable String workflowId,
            @RequestParam(defaultValue = "markdown") String format,
            @RequestParam(defaultValue = "") String headerText,
            @RequestParam(defaultValue = "") String footerText,
            HttpServletRequest httpRequest) {
        RequestSubject subject = subjectResolver.resolve(httpRequest);
        try {
            return artifactExportService.export(
                    normalizedWorkflowId(workflowId), format, headerText, footerText, subject);
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, exception.getMessage(), exception);
        }
    }

    /**
     * Resumes a failed protected multi-agent writing workflow from its first missing stage.
     *
     * @param workflowId workflow id returned by the write endpoint
     * @param request latest writing request for remaining stages
     * @param httpRequest HTTP request used only for the backend-resolved request subject
     * @return resumed workflow status
     */
    @PostMapping(RESUME_PATH)
    public MultiAgentWritingResponse resume(
            @PathVariable String workflowId,
            @Valid @RequestBody MultiAgentWritingRequest request,
            HttpServletRequest httpRequest) {
        RequestSubject subject = subjectResolver.resolve(httpRequest);
        try {
            return handoutTaskFacade == null
                    ? writingService.resume(workflowId, request, subject)
                    : handoutTaskFacade.resume(normalizedWorkflowId(workflowId), subject);
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, exception.getMessage(), exception);
        } catch (IllegalStateException exception) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, exception.getMessage(), exception);
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
            if (handoutTaskFacade != null) {
                return handoutTaskFacade.traces(normalizedWorkflowId(workflowId), subject);
            }
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
        for (int index = 0; index < CONTROLLED_STAGE_CODES.size(); index += 1) {
            if (planId.endsWith(":" + CONTROLLED_STAGE_CODES.get(index))) {
                return index;
            }
        }
        if (planId.endsWith(":draft")) {
            return CONTROLLED_STAGE_CODES.size();
        }
        if (planId.endsWith(":review")) {
            return CONTROLLED_STAGE_CODES.size() + 1;
        }
        if (planId.endsWith(":format")) {
            return CONTROLLED_STAGE_CODES.size() + 2;
        }
        return UNKNOWN_STAGE_ORDER;
    }

}
