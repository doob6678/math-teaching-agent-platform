package com.doob.mathagent.agent.controller;

import com.doob.mathagent.agent.dto.AgentRunExecuteRequest;
import com.doob.mathagent.agent.service.AgentRunCapabilityVerifier;
import com.doob.mathagent.agent.service.AgentRunExecutionService;
import com.doob.mathagent.agent.vo.AgentRunExecuteResponse;
import com.doob.mathagent.infrastructure.security.RequestSubject;
import com.doob.mathagent.infrastructure.security.RequestSubjectResolver;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * API for executing planned AI agent runs through backend identity and capability checks.
 */
@RestController
public class AgentRunExecutionController {

    private static final String AGENT_EXECUTE_PATH = "/api/agents/execute";

    private final AgentRunExecutionService executionService;
    private final RequestSubjectResolver subjectResolver;
    private final AgentRunCapabilityVerifier capabilityVerifier;

    /**
     * Creates the controller.
     *
     * @param executionService baseline execution service
     * @param subjectResolver trusted backend subject resolver
     * @param capabilityVerifier one-time capability verifier
     */
    public AgentRunExecutionController(
            AgentRunExecutionService executionService,
            RequestSubjectResolver subjectResolver,
            AgentRunCapabilityVerifier capabilityVerifier) {
        this.executionService = executionService;
        this.subjectResolver = subjectResolver;
        this.capabilityVerifier = capabilityVerifier;
    }

    /**
     * Executes a planned agent run after server-side owner and capability checks.
     *
     * @param request execution request
     * @param httpRequest HTTP request used only for trusted headers and backend subject resolution
     * @return safe execution trace response
     */
    @PostMapping(AGENT_EXECUTE_PATH)
    public AgentRunExecuteResponse execute(
            @Valid @RequestBody AgentRunExecuteRequest request,
            HttpServletRequest httpRequest) {
        RequestSubject subject = subjectResolver.resolve(httpRequest);
        AgentRunExecuteRequest normalized = request.normalize();
        if (executionService.requiresCapability(normalized)
                && !capabilityVerifier.verify(
                        headerOrEmpty(httpRequest, "X-Capability-Token"),
                        executionService.capabilityAction(normalized.plan()),
                        AGENT_EXECUTE_PATH,
                        headerOrEmpty(httpRequest, "X-Request-Hash"),
                        subject)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Capability token required for agent execution");
        }
        try {
            return executionService.execute(normalized, subject);
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, exception.getMessage(), exception);
        }
    }

    /**
     * Reads a request header used for capability verification.
     */
    private static String headerOrEmpty(HttpServletRequest request, String name) {
        if (request == null) {
            return "";
        }
        String value = request.getHeader(name);
        return value == null || value.isBlank() ? "" : value.strip();
    }
}
