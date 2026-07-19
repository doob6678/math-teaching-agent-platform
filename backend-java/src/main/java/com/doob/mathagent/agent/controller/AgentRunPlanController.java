package com.doob.mathagent.agent.controller;

import com.doob.mathagent.agent.dto.AgentRunPlanRequest;
import com.doob.mathagent.agent.service.AgentRunPlanService;
import com.doob.mathagent.agent.vo.AgentRunPlanResponse;
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
 * API for planning AI agent execution before expensive model/tool calls.
 */
@RestController
public class AgentRunPlanController {

    private final AgentRunPlanService planService;
    private final RequestSubjectResolver subjectResolver;

    /**
     * Creates the controller.
     *
     * @param planService agent planning service
     * @param subjectResolver trusted backend subject resolver
     */
    public AgentRunPlanController(
            AgentRunPlanService planService,
            RequestSubjectResolver subjectResolver) {
        this.planService = planService;
        this.subjectResolver = subjectResolver;
    }

    /**
     * Builds a safe agent run plan from backend identity and requested task signals.
     *
     * @param request planning request
     * @param httpRequest HTTP request used only for trusted backend subject resolution
     * @return safe execution plan
     */
    @PostMapping("/api/agents/run-plan")
    public AgentRunPlanResponse plan(
            @Valid @RequestBody AgentRunPlanRequest request,
            HttpServletRequest httpRequest) {
        RequestSubject subject = subjectResolver.resolve(httpRequest);
        try {
            return planService.plan(request, subject);
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, exception.getMessage(), exception);
        } catch (IllegalStateException exception) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, exception.getMessage(), exception);
        }
    }
}
