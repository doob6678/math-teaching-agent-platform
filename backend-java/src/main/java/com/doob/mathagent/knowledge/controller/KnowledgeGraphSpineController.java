package com.doob.mathagent.knowledge.controller;

import com.doob.mathagent.infrastructure.security.RequestSubject;
import com.doob.mathagent.infrastructure.security.RequestSubjectResolver;
import com.doob.mathagent.knowledge.service.KnowledgeGraphSpineService;
import com.doob.mathagent.knowledge.vo.KnowledgeGraphSpineResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read-only API for the curated frontend display knowledge graph.
 */
@RestController
public class KnowledgeGraphSpineController {

    private final KnowledgeGraphSpineService service;
    private final RequestSubjectResolver subjectResolver;

    /**
     * Creates the controller.
     *
     * @param service display graph service
     * @param subjectResolver backend subject resolver; frontend-supplied subject ids are ignored
     */
    @Autowired
    public KnowledgeGraphSpineController(
            KnowledgeGraphSpineService service,
            RequestSubjectResolver subjectResolver) {
        this.service = service;
        this.subjectResolver = subjectResolver;
    }

    /**
     * Returns the visible curated graph spine for the backend-resolved viewer.
     */
    @GetMapping("/api/knowledge/graph/spine")
    public KnowledgeGraphSpineResponse displaySpine(HttpServletRequest request) {
        RequestSubject subject = subjectResolver.resolve(request).normalize();
        return service.displaySpine(subject.tenantId(), subject.subjectType(), subject.subjectId());
    }
}
