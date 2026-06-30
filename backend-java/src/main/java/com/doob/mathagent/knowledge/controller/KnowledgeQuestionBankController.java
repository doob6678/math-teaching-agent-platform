package com.doob.mathagent.knowledge.controller;

import com.doob.mathagent.infrastructure.security.RequestSubject;
import com.doob.mathagent.infrastructure.security.RequestSubjectResolver;
import com.doob.mathagent.knowledge.dto.KnowledgePointCreateRequest;
import com.doob.mathagent.knowledge.dto.QuestionBankItemCreateRequest;
import com.doob.mathagent.knowledge.service.KnowledgeQuestionBankCapabilityVerifier;
import com.doob.mathagent.knowledge.service.KnowledgeQuestionBankService;
import com.doob.mathagent.knowledge.vo.KnowledgePointResponse;
import com.doob.mathagent.knowledge.vo.QuestionBankItemResponse;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Teacher/admin API for standard knowledge points and question bank items.
 */
@RestController
public class KnowledgeQuestionBankController {

    private static final String KNOWLEDGE_POINT_CREATE_ACTION = "knowledge-point:create";
    private static final String QUESTION_BANK_CREATE_ACTION = "question-bank:create";
    private static final String KNOWLEDGE_POINTS_PATH = "/api/knowledge/points";
    private static final String QUESTION_BANK_PATH = "/api/question-bank/items";

    private final KnowledgeQuestionBankService service;
    private final RequestSubjectResolver subjectResolver;
    private final KnowledgeQuestionBankCapabilityVerifier capabilityVerifier;

    /**
     * Creates a controller.
     *
     * @param service knowledge/question bank service
     * @param subjectResolver backend subject resolver
     * @param capabilityVerifier high-value write verifier
     */
    public KnowledgeQuestionBankController(
            KnowledgeQuestionBankService service,
            RequestSubjectResolver subjectResolver,
            KnowledgeQuestionBankCapabilityVerifier capabilityVerifier) {
        this.service = service;
        this.subjectResolver = subjectResolver;
        this.capabilityVerifier = capabilityVerifier;
    }

    /**
     * Creates a knowledge point after capability and backend-role checks.
     */
    @PostMapping("/api/knowledge/points")
    public KnowledgePointResponse createKnowledgePoint(
            @RequestBody KnowledgePointCreateRequest request,
            HttpServletRequest httpRequest) {
        RequestSubject subject = subjectResolver.resolve(httpRequest).normalize();
        verifyCapability(KNOWLEDGE_POINT_CREATE_ACTION, KNOWLEDGE_POINTS_PATH, subject, httpRequest);
        try {
            return service.createKnowledgePoint(
                    subject.tenantId(),
                    subject.subjectType(),
                    subject.subjectId(),
                    request);
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, exception.getMessage(), exception);
        }
    }

    /**
     * Lists visible knowledge points for a teacher/admin.
     */
    @GetMapping("/api/knowledge/points")
    public List<KnowledgePointResponse> listKnowledgePoints(HttpServletRequest httpRequest) {
        RequestSubject subject = subjectResolver.resolve(httpRequest).normalize();
        try {
            return service.listKnowledgePoints(subject.tenantId(), subject.subjectType(), subject.subjectId());
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, exception.getMessage(), exception);
        }
    }

    /**
     * Creates a question item after capability and backend-role checks.
     */
    @PostMapping("/api/question-bank/items")
    public QuestionBankItemResponse createQuestion(
            @RequestBody QuestionBankItemCreateRequest request,
            HttpServletRequest httpRequest) {
        RequestSubject subject = subjectResolver.resolve(httpRequest).normalize();
        verifyCapability(QUESTION_BANK_CREATE_ACTION, QUESTION_BANK_PATH, subject, httpRequest);
        try {
            return service.createQuestion(
                    subject.tenantId(),
                    subject.subjectType(),
                    subject.subjectId(),
                    request);
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, exception.getMessage(), exception);
        }
    }

    /**
     * Searches visible question bank items.
     */
    @GetMapping("/api/question-bank/items")
    public List<QuestionBankItemResponse> searchQuestions(
            @RequestParam(defaultValue = "") String query,
            @RequestParam(defaultValue = "10") int limit,
            HttpServletRequest httpRequest) {
        RequestSubject subject = subjectResolver.resolve(httpRequest).normalize();
        try {
            return service.searchQuestions(
                    subject.tenantId(),
                    subject.subjectType(),
                    subject.subjectId(),
                    query,
                    limit);
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, exception.getMessage(), exception);
        }
    }

    /**
     * Verifies one-time capability tokens for write operations.
     */
    private void verifyCapability(
            String action,
            String path,
            RequestSubject subject,
            HttpServletRequest httpRequest) {
        if (!capabilityVerifier.verify(
                headerOrNull(httpRequest, "X-Capability-Token"),
                action,
                path,
                headerOrNull(httpRequest, "X-Request-Hash"),
                subject)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Capability token required for knowledge question bank write");
        }
    }

    /**
     * Reads a non-authoritative header used for capability token verification.
     */
    private static String headerOrNull(HttpServletRequest request, String name) {
        if (request == null) {
            return null;
        }
        String value = request.getHeader(name);
        return value == null || value.isBlank() ? null : value.strip();
    }
}
