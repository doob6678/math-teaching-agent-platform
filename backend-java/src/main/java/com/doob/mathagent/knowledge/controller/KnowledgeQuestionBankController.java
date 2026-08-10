package com.doob.mathagent.knowledge.controller;

import com.doob.mathagent.infrastructure.security.RequestSubject;
import com.doob.mathagent.infrastructure.security.RequestSubjectResolver;
import com.doob.mathagent.knowledge.dto.KnowledgePointCreateRequest;
import com.doob.mathagent.knowledge.dto.QuestionBankItemCreateRequest;
import com.doob.mathagent.knowledge.service.KnowledgeQuestionBankService;
import com.doob.mathagent.knowledge.service.TeacherBlockQuestionImportService;
import com.doob.mathagent.knowledge.vo.KnowledgePointResponse;
import com.doob.mathagent.knowledge.vo.KnowledgeRelationResponse;
import com.doob.mathagent.knowledge.vo.QuestionBankItemResponse;
import com.doob.mathagent.knowledge.vo.TeacherBlockQuestionImportResponse;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
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

    private final KnowledgeQuestionBankService service;
    private final TeacherBlockQuestionImportService teacherBlockQuestionImportService;
    private final RequestSubjectResolver subjectResolver;

    /**
     * Creates a controller.
     *
     * @param service knowledge/question bank service
     * @param teacherBlockQuestionImportService teacher resource import service
     * @param subjectResolver backend subject resolver
     */
    @Autowired
    public KnowledgeQuestionBankController(
            KnowledgeQuestionBankService service,
            TeacherBlockQuestionImportService teacherBlockQuestionImportService,
            RequestSubjectResolver subjectResolver) {
        this.service = Objects.requireNonNull(service, "service");
        this.teacherBlockQuestionImportService = Objects.requireNonNull(
                teacherBlockQuestionImportService,
                "teacherBlockQuestionImportService");
        this.subjectResolver = Objects.requireNonNull(subjectResolver, "subjectResolver");
    }

    /**
     * Creates a knowledge point after backend-role checks.
     */
    @PostMapping("/api/knowledge/points")
    public KnowledgePointResponse createKnowledgePoint(
            @RequestBody KnowledgePointCreateRequest request,
            HttpServletRequest httpRequest) {
        RequestSubject subject = subjectResolver.resolve(httpRequest).normalize();
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
     * Lists visible knowledge graph relations for the backend-resolved teacher/admin.
     */
    @GetMapping("/api/knowledge/relations")
    public List<KnowledgeRelationResponse> listKnowledgeRelations(HttpServletRequest httpRequest) {
        RequestSubject subject = subjectResolver.resolve(httpRequest).normalize();
        try {
            return service.listKnowledgeRelations(subject.tenantId(), subject.subjectType(), subject.subjectId());
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, exception.getMessage(), exception);
        }
    }

    /**
     * Creates a question item after backend-role checks.
     */
    @PostMapping("/api/question-bank/items")
    public QuestionBankItemResponse createQuestion(
            @RequestBody QuestionBankItemCreateRequest request,
            HttpServletRequest httpRequest) {
        RequestSubject subject = subjectResolver.resolve(httpRequest).normalize();
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
     * Imports real parsed teacher resource blocks into the question bank.
     */
    @PostMapping("/api/question-bank/import/teacher-resources/{documentId}")
    public TeacherBlockQuestionImportResponse importTeacherResourceQuestions(
            @PathVariable String documentId,
            HttpServletRequest httpRequest) {
        RequestSubject subject = subjectResolver.resolve(httpRequest).normalize();
        try {
            return teacherBlockQuestionImportService.importFromTeacherResource(
                    subject.tenantId(),
                    subject.subjectType(),
                    subject.subjectId(),
                    documentId);
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

}
