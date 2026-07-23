package com.doob.mathagent.agent.controller;

import com.doob.mathagent.agent.service.AgentRegistryService;
import com.doob.mathagent.agent.vo.AgentRegistryResponse;
import com.doob.mathagent.infrastructure.security.RequestSubjectResolver;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Exposes the subject-filtered agent marketplace catalog for the React plaza and future MCP discovery projection. */
@RestController
public class AgentRegistryController {
    private final AgentRegistryService registryService;
    private final RequestSubjectResolver subjectResolver;

    public AgentRegistryController(AgentRegistryService registryService, RequestSubjectResolver subjectResolver) {
        this.registryService = registryService;
        this.subjectResolver = subjectResolver;
    }

    /** Returns only agents visible to the authenticated backend subject. */
    @GetMapping("/api/agents/registry")
    public AgentRegistryResponse registry(HttpServletRequest request) {
        return registryService.visibleAgents(subjectResolver.resolve(request));
    }
}
