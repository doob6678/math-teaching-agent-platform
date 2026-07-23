package com.doob.mathagent.agent.controller;

import com.doob.mathagent.agent.dto.KnowledgeRetrievalAgentRequest;
import com.doob.mathagent.agent.service.KnowledgeRetrievalAgentService;
import com.doob.mathagent.agent.vo.KnowledgeEvidencePackResponse;
import com.doob.mathagent.infrastructure.security.RequestSubjectResolver;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** HTTP adapter for the real retrieval specialist in the agent marketplace. */
@RestController
public class KnowledgeRetrievalAgentController {
    private final KnowledgeRetrievalAgentService service;
    private final RequestSubjectResolver subjectResolver;
    public KnowledgeRetrievalAgentController(KnowledgeRetrievalAgentService service, RequestSubjectResolver subjectResolver) {
        this.service = service; this.subjectResolver = subjectResolver;
    }
    @PostMapping("/api/agents/knowledge-retrieval")
    public KnowledgeEvidencePackResponse retrieve(@RequestBody KnowledgeRetrievalAgentRequest request, HttpServletRequest httpRequest) {
        return service.retrieve(request, subjectResolver.resolve(httpRequest));
    }
}
