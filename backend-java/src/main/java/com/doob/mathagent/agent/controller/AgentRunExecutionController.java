package com.doob.mathagent.agent.controller;

import com.doob.mathagent.agent.dto.AgentRunExecuteRequest;
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
 * API for executing planned AI agent runs through backend identity and user authorization checks.
 */
@RestController
public class AgentRunExecutionController {

    /** Stable route identity shared by Spring mapping and downstream audit metadata. */
    private static final String AGENT_EXECUTE_PATH = "/api/agents/execute";

    private final AgentRunExecutionService executionService;
    private final RequestSubjectResolver subjectResolver;

    /**
     * Creates the controller.
     *
     * @param executionService live execution service
     * @param subjectResolver trusted backend subject resolver
     */
    public AgentRunExecutionController(
            AgentRunExecutionService executionService,
            RequestSubjectResolver subjectResolver) {
        this.executionService = executionService;
        this.subjectResolver = subjectResolver;
    }

    /**
     * Executes a planned agent run after server-side owner and role checks.
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
        if (normalized.dryRun()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Agent dryRun is disabled in production");
        }
        try {
            return executionService.execute(normalized, subject);
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, exception.getMessage(), exception);
        } catch (IllegalStateException exception) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, exception.getMessage(), exception);
        }
    }

}
