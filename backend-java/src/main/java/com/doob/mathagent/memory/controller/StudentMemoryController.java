package com.doob.mathagent.memory.controller;

import com.doob.mathagent.infrastructure.security.RequestSubject;
import com.doob.mathagent.infrastructure.security.RequestSubjectResolver;
import com.doob.mathagent.memory.dto.StudentMemoryRequest;
import com.doob.mathagent.memory.service.StudentMemoryReuseService;
import com.doob.mathagent.memory.vo.StudentMemoryResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Student memory API for similar question reuse and answer remembering.
 */
@RestController
public class StudentMemoryController {

    private final StudentMemoryReuseService memoryReuseService;
    private final RequestSubjectResolver subjectResolver;

    /**
     * Creates a student memory controller.
     *
     * @param memoryReuseService memory reuse service
     * @param subjectResolver backend subject resolver
     */
    public StudentMemoryController(
            StudentMemoryReuseService memoryReuseService,
            RequestSubjectResolver subjectResolver) {
        this.memoryReuseService = memoryReuseService;
        this.subjectResolver = subjectResolver;
    }

    /**
     * Checks whether a similar previous answer can be reused.
     *
     * @param request memory reuse request
     * @param httpRequest HTTP request containing tenant and subject headers
     * @return reuse decision
     */
    @PostMapping("/api/students/memory/reuse")
    public StudentMemoryResponse reuse(@RequestBody StudentMemoryRequest request, HttpServletRequest httpRequest) {
        return memoryReuseService.reuse(enrich(request, subjectResolver.resolve(httpRequest)));
    }

    /**
     * Stores a generated answer as private or public memory.
     *
     * @param request memory remember request
     * @param httpRequest HTTP request containing tenant and subject headers
     * @return memory write result
     */
    @PostMapping("/api/students/memory/remember")
    public StudentMemoryResponse remember(@RequestBody StudentMemoryRequest request, HttpServletRequest httpRequest) {
        return memoryReuseService.remember(enrich(request, subjectResolver.resolve(httpRequest)));
    }

    /**
     * Merges request body with request headers.
     *
     * @param request request body
     * @param httpRequest HTTP request
     * @return enriched request
     */
    private static StudentMemoryRequest enrich(StudentMemoryRequest request, RequestSubject subject) {
        RequestSubject normalized = subject.normalize();
        return new StudentMemoryRequest(
                normalized.tenantId(),
                normalized.subjectType(),
                normalized.subjectId(),
                request.questionText(),
                request.answerText(),
                request.knowledgePointName(),
                request.memoryScope(),
                request.bypassReuse());
    }
}
