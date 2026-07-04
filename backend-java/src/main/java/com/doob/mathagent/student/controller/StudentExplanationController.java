package com.doob.mathagent.student.controller;

import com.doob.mathagent.infrastructure.security.RequestSubject;
import com.doob.mathagent.infrastructure.security.RequestSubjectResolver;
import com.doob.mathagent.student.dto.StudentExplanationRequest;
import com.doob.mathagent.student.service.StudentExplanationHistoryStore;
import com.doob.mathagent.student.service.StudentExplanationHistorySummary;
import com.doob.mathagent.student.service.StudentExplanationImageStoreService;
import com.doob.mathagent.student.service.StudentExplanationService;
import com.doob.mathagent.student.vo.StudentExplanationHistoryResponse;
import com.doob.mathagent.student.vo.StudentExplanationImageUploadResponse;
import com.doob.mathagent.student.vo.StudentExplanationResponse;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.multipart.MultipartFile;

/**
 * Student question explanation API with backend-owned identity and resource visibility.
 */
@RestController
public class StudentExplanationController {

    private final StudentExplanationService explanationService;
    private final StudentExplanationImageStoreService imageStoreService;
    private final StudentExplanationHistoryStore historyStore;
    private final RequestSubjectResolver subjectResolver;

    /**
     * Creates the controller.
     *
     * @param explanationService explanation orchestration service
     * @param subjectResolver backend request subject resolver
     */
    public StudentExplanationController(
            StudentExplanationService explanationService,
            StudentExplanationImageStoreService imageStoreService,
            StudentExplanationHistoryStore historyStore,
            RequestSubjectResolver subjectResolver) {
        this.explanationService = explanationService;
        this.imageStoreService = imageStoreService;
        this.historyStore = historyStore;
        this.subjectResolver = subjectResolver;
    }

    /**
     * Lists recent explanation history visible to the backend-resolved subject.
     */
    @GetMapping("/api/students/explanations/history")
    public StudentExplanationHistoryResponse history(
            @RequestParam(value = "conversationId", required = false) String conversationId,
            @RequestParam(value = "limit", defaultValue = "20") int limit,
            HttpServletRequest httpRequest) {
        RequestSubject subject = subjectResolver.resolve(httpRequest);
        List<StudentExplanationHistorySummary> items = historyStore.findRecent(
                subject.tenantId(),
                subject.subjectType(),
                subject.subjectId(),
                conversationId,
                limit);
        return new StudentExplanationHistoryResponse(items.stream()
                .map(item -> new StudentExplanationHistoryResponse.Item(
                        item.explanationId(),
                        item.conversationId(),
                        item.questionText(),
                        item.imageStatus(),
                        item.imageProblemText(),
                        item.aiProviderName(),
                        item.aiModelCode(),
                        item.totalTokens(),
                        item.totalElapsedMs(),
                        item.createdAt()))
                .toList());
    }

    /**
     * Stores a temporary image for a later explanation request.
     *
     * @param file uploaded image file
     * @param httpRequest HTTP request used only for backend identity resolution
     * @return temporary upload metadata
     */
    @PostMapping(value = "/api/students/explanations/images", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public StudentExplanationImageUploadResponse uploadImage(
            @RequestPart("file") MultipartFile file,
            HttpServletRequest httpRequest) {
        RequestSubject subject = subjectResolver.resolve(httpRequest);
        try {
            return imageStoreService.save(file, subject);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage(), e);
        }
    }

    /**
     * Builds evidence-backed student explanation cards.
     *
     * @param request explanation request body
     * @param httpRequest HTTP request used only for backend identity resolution
     * @return student explanation cards
     */
    @PostMapping("/api/students/explanations")
    public StudentExplanationResponse explain(
            @RequestBody StudentExplanationRequest request,
            HttpServletRequest httpRequest) {
        RequestSubject subject = subjectResolver.resolve(httpRequest);
        try {
            return explanationService.explain(request, subject);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage(), e);
        }
    }
}
