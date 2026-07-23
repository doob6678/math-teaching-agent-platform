package com.doob.mathagent.learning.controller;

import com.doob.mathagent.infrastructure.security.RequestSubject;
import com.doob.mathagent.infrastructure.security.RequestSubjectResolver;
import com.doob.mathagent.learning.StudentKnowledgeMastery;
import com.doob.mathagent.learning.StudentLearningLoopService;
import com.doob.mathagent.learning.dto.StudentAttemptRequest;
import com.doob.mathagent.learning.dto.TargetedExplanationRequest;
import com.doob.mathagent.learning.vo.StudentLearningResponse;
import com.doob.mathagent.student.dto.StudentExplanationRequest;
import com.doob.mathagent.student.service.StudentExplanationService;
import com.doob.mathagent.student.vo.StudentExplanationResponse;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** HTTP boundary for answer facts, mastery, recommendations, and teacher weak-point views. */
@RestController
public class StudentLearningController {
    private final StudentLearningLoopService service;
    private final RequestSubjectResolver subjectResolver;
    private final StudentExplanationService explanationService;

    public StudentLearningController(StudentLearningLoopService service, RequestSubjectResolver subjectResolver) {
        this(service, subjectResolver, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public StudentLearningController(
            StudentLearningLoopService service,
            RequestSubjectResolver subjectResolver,
            StudentExplanationService explanationService) {
        this.service = service;
        this.subjectResolver = subjectResolver;
        this.explanationService = explanationService;
    }

    /** Records one real answer and immediately returns updated diagnostic rows. */
    @PostMapping("/api/students/learning/attempts")
    public StudentLearningResponse.Attempt record(@RequestBody StudentAttemptRequest request, HttpServletRequest httpRequest) {
        RequestSubject subject = subjectResolver.resolve(httpRequest).normalize(); requireStudent(subject);
        try {
            var result = service.recordAttempt(subject.tenantId(), subject.subjectId(), subject.subjectType(), request.questionId(), request.questionText(),
                    request.knowledgePointIds(), request.correct(), request.responseTimeMs());
            return new StudentLearningResponse.Attempt(result.attempt().attemptId(), result.updatedMastery(), result.weakPoints());
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage(), exception);
        }
    }

    /** Returns the current student's mastery, ordered weak first. */
    @GetMapping("/api/students/learning/mastery")
    public List<StudentKnowledgeMastery> mastery(HttpServletRequest httpRequest) {
        RequestSubject subject = subjectResolver.resolve(httpRequest).normalize(); requireStudent(subject);
        return service.mastery(subject.tenantId(), subject.subjectId());
    }

    /** Returns targeted visible question-bank candidates for the current student's weak points. */
    @GetMapping("/api/students/learning/recommendations")
    public List<StudentLearningResponse.Recommendation> recommendations(@RequestParam(defaultValue = "10") int limit,
            HttpServletRequest httpRequest) {
        RequestSubject subject = subjectResolver.resolve(httpRequest).normalize(); requireStudent(subject);
        return service.recommendations(subject.tenantId(), subject.subjectId(), subject.subjectType(), limit).stream()
                .map(item -> new StudentLearningResponse.Recommendation(item.question(), item.knowledgePointId(), item.weaknessLevel())).toList();
    }

    /**
     * Starts the existing evidence-backed explanation flow with the student's current weak-point context attached.
     * The learning endpoint never generates an answer itself; it only supplies diagnosis metadata to the established
     * student explanation service, so textbook retrieval, graph matching, audit and model fallback stay centralized.
     */
    @PostMapping("/api/students/learning/explanations")
    public StudentExplanationResponse targetedExplanation(
            @RequestBody TargetedExplanationRequest request,
            HttpServletRequest httpRequest) {
        RequestSubject subject = subjectResolver.resolve(httpRequest).normalize();
        requireStudent(subject);
        if (explanationService == null) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Student explanation service is unavailable");
        }
        if (request == null || request.questionText() == null || request.questionText().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "questionText is required");
        }
        try {
            List<StudentKnowledgeMastery> mastery = service.mastery(subject.tenantId(), subject.subjectId());
            StudentKnowledgeMastery selected = mastery.stream()
                    .filter(item -> request.knowledgePointId() != null
                            && request.knowledgePointId().equals(item.knowledgePointId()))
                    .findFirst()
                    .orElseGet(() -> mastery.stream().filter(item -> item.weaknessLevel() > 0).findFirst().orElse(null));
            String context = selected == null
                    ? "未找到已记录的薄弱知识点；请只根据题目和教材证据讲解。"
                    : "本题针对知识点 " + selected.knowledgePointId() + "；当前掌握度 "
                            + selected.masteryPercent() + "%；错误次数 " + selected.incorrectCount()
                            + "；薄弱等级 " + selected.weaknessLevel() + "。请优先修复该知识点。";
            String questionText = context + "\n原题：" + request.questionText().strip();
            StudentExplanationRequest explanationRequest = new StudentExplanationRequest(
                    null, questionText, null, null, null, null,
                    true, true, false, 5, 3, false);
            return explanationService.explain(explanationRequest, subject);
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage(), exception);
        }
    }

    /** Teacher/admin view of weak points for one student or the whole tenant. */
    @GetMapping("/api/teachers/learning/weak-points")
    public List<StudentKnowledgeMastery> weakPoints(@RequestParam(required = false) String studentId, HttpServletRequest httpRequest) {
        RequestSubject subject = subjectResolver.resolve(httpRequest).normalize();
        if (!("teacher".equalsIgnoreCase(subject.subjectType()) || "admin".equalsIgnoreCase(subject.subjectType()))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Teacher or admin role required");
        }
        return service.tenantWeakPoints(subject.tenantId(), studentId);
    }

    private static void requireStudent(RequestSubject subject) {
        if (!"student".equalsIgnoreCase(subject.subjectType()) || subject.subjectId() == null || subject.subjectId().isBlank()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Student role required");
        }
    }
}
