package com.doob.mathagent.memory.controller;

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

    /**
     * Creates a student memory controller.
     *
     * @param memoryReuseService memory reuse service
     */
    public StudentMemoryController(StudentMemoryReuseService memoryReuseService) {
        this.memoryReuseService = memoryReuseService;
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
        return memoryReuseService.reuse(enrich(request, httpRequest));
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
        return memoryReuseService.remember(enrich(request, httpRequest));
    }

    /**
     * Merges request body with request headers.
     *
     * @param request request body
     * @param httpRequest HTTP request
     * @return enriched request
     */
    private static StudentMemoryRequest enrich(StudentMemoryRequest request, HttpServletRequest httpRequest) {
        return new StudentMemoryRequest(
                headerOrDefault(httpRequest, "X-Tenant-Id", request.tenantId()),
                headerOrDefault(httpRequest, "X-Subject-Type", request.viewerRole()),
                headerOrDefault(httpRequest, "X-Subject-Id", request.studentId()),
                request.questionText(),
                request.answerText(),
                request.knowledgePointName(),
                request.memoryScope(),
                request.bypassReuse());
    }

    /**
     * Reads a header and falls back when blank.
     *
     * @param request HTTP request
     * @param name header name
     * @param defaultValue fallback value
     * @return header value or fallback
     */
    private static String headerOrDefault(HttpServletRequest request, String name, String defaultValue) {
        if (request == null) {
            return defaultValue;
        }
        String value = request.getHeader(name);
        return value == null || value.isBlank() ? defaultValue : value.strip();
    }
}
