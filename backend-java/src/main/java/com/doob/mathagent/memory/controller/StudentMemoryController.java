package com.doob.mathagent.memory.controller;

import com.doob.mathagent.infrastructure.security.RequestSubject;
import com.doob.mathagent.infrastructure.security.RequestSubjectResolver;
import com.doob.mathagent.memory.dto.StudentMemoryRequest;
import com.doob.mathagent.memory.service.StudentMemoryCapabilityVerifier;
import com.doob.mathagent.memory.service.StudentMemoryCommand;
import com.doob.mathagent.memory.service.StudentMemoryReuseService;
import com.doob.mathagent.memory.vo.StudentMemoryResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Student memory API for similar question reuse and answer remembering.
 */
@RestController
public class StudentMemoryController {

    private static final String REMEMBER_ACTION = "student-memory:remember";
    private static final String REMEMBER_PATH = "/api/students/memory/remember";

    private final StudentMemoryReuseService memoryReuseService;
    private final RequestSubjectResolver subjectResolver;
    private final StudentMemoryCapabilityVerifier capabilityVerifier;

    /**
     * Creates a student memory controller.
     *
     * @param memoryReuseService memory reuse service
     * @param subjectResolver backend subject resolver
     * @param capabilityVerifier high-value memory write verifier
     */
    public StudentMemoryController(
            StudentMemoryReuseService memoryReuseService,
            RequestSubjectResolver subjectResolver,
            StudentMemoryCapabilityVerifier capabilityVerifier) {
        this.memoryReuseService = memoryReuseService;
        this.subjectResolver = subjectResolver;
        this.capabilityVerifier = capabilityVerifier;
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
        RequestSubject subject = subjectResolver.resolve(httpRequest);
        if (!capabilityVerifier.verify(
                headerOrNull(httpRequest, "X-Capability-Token"),
                REMEMBER_ACTION,
                REMEMBER_PATH,
                headerOrNull(httpRequest, "X-Request-Hash"),
                subject)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Capability token required for student memory remember");
        }
        return memoryReuseService.remember(enrich(request, subject));
    }

    /**
     * Merges request body with backend-resolved subject identity.
     *
     * @param request request body
     * @param subject backend resolved subject
     * @return server-side memory command
     */
    private static StudentMemoryCommand enrich(StudentMemoryRequest request, RequestSubject subject) {
        RequestSubject normalized = subject.normalize();
        return StudentMemoryCommand.fromRequest(
                normalized.tenantId(),
                normalized.subjectType(),
                normalized.subjectId(),
                request);
    }

    /**
     * Reads a non-authoritative request header used for capability token verification.
     *
     * @param request HTTP request
     * @param name header name
     * @return stripped header value or null
     */
    private static String headerOrNull(HttpServletRequest request, String name) {
        if (request == null) {
            return null;
        }
        String value = request.getHeader(name);
        return value == null || value.isBlank() ? null : value.strip();
    }
}
