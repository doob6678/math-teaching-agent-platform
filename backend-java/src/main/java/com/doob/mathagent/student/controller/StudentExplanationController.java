package com.doob.mathagent.student.controller;

import com.doob.mathagent.infrastructure.security.RequestSubject;
import com.doob.mathagent.infrastructure.security.RequestSubjectResolver;
import com.doob.mathagent.student.dto.StudentExplanationRequest;
import com.doob.mathagent.student.service.StudentExplanationHistoryStore;
import com.doob.mathagent.student.service.StudentExplanationHistorySummary;
import com.doob.mathagent.student.service.StudentExplanationConversationDetail;
import com.doob.mathagent.student.service.StudentExplanationConversationSummary;
import com.doob.mathagent.student.service.StudentExplanationImageStoreService;
import com.doob.mathagent.student.service.StudentExplanationProgressListener;
import com.doob.mathagent.student.service.StudentExplanationService;
import com.doob.mathagent.student.vo.StudentExplanationConversationListResponse;
import com.doob.mathagent.student.vo.StudentExplanationConversationResponse;
import com.doob.mathagent.student.vo.StudentExplanationHistoryResponse;
import com.doob.mathagent.student.vo.StudentExplanationImageUploadResponse;
import com.doob.mathagent.student.vo.StudentExplanationResponse;
import com.doob.mathagent.student.vo.StudentExplanationStreamEvent;
import com.doob.mathagent.student.vo.StudentExplanationStreamProgress;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.web.bind.annotation.PathVariable;
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

    private static final Logger log = LoggerFactory.getLogger(StudentExplanationController.class);

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
                        item.title(),
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
     * Lists durable explanation conversations for the sidebar.
     */
    @GetMapping("/api/students/explanations/conversations")
    public StudentExplanationConversationListResponse conversations(
            @RequestParam(value = "limit", defaultValue = "20") int limit,
            HttpServletRequest httpRequest) {
        RequestSubject subject = subjectResolver.resolve(httpRequest);
        List<StudentExplanationConversationSummary> items = historyStore.listConversations(
                subject.tenantId(),
                subject.subjectType(),
                subject.subjectId(),
                limit);
        return new StudentExplanationConversationListResponse(items.stream()
                .map(item -> new StudentExplanationConversationListResponse.Item(
                        item.conversationId(),
                        item.title(),
                        item.lastQuestionText(),
                        item.viewerRole(),
                        item.totalMessages(),
                        item.createdAt(),
                        item.updatedAt()))
                .toList());
    }

    /**
     * Loads one durable explanation conversation with its persisted turns.
     */
    @GetMapping("/api/students/explanations/conversations/{conversationId}")
    public StudentExplanationConversationResponse conversation(
            @PathVariable String conversationId,
            @RequestParam(value = "limit", defaultValue = "20") int limit,
            HttpServletRequest httpRequest) {
        RequestSubject subject = subjectResolver.resolve(httpRequest);
        StudentExplanationConversationDetail detail = historyStore.loadConversation(
                subject.tenantId(),
                subject.subjectType(),
                subject.subjectId(),
                conversationId,
                limit);
        if (detail == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Conversation not found");
        }
        return new StudentExplanationConversationResponse(
                detail.conversationId(),
                detail.title(),
                detail.viewerRole(),
                detail.totalMessages(),
                detail.createdAt(),
                detail.updatedAt(),
                detail.messages().stream()
                        .map(message -> new StudentExplanationConversationResponse.Message(
                                message.explanationId(),
                                message.questionText(),
                                message.imageStatus(),
                                message.imageProblemText(),
                                message.imageFileName(),
                                message.createdAt(),
                                message.response()))
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

    /**
     * Streams real student explanation progress so the frontend can render real stages immediately.
     */
    @PostMapping(value = "/api/students/explanations/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter explainStream(
            @RequestBody StudentExplanationRequest request,
            HttpServletRequest httpRequest) {
        RequestSubject subject = subjectResolver.resolve(httpRequest);
        String requestPath = httpRequest.getRequestURI();
        SseEmitter emitter = new SseEmitter(TimeUnit.MINUTES.toMillis(5));
        CompletableFuture.runAsync(() -> {
            try {
                explanationService.explain(request, subject, new StudentExplanationProgressListener() {
                    @Override
                    public void onProgress(StudentExplanationStreamProgress progress, String message) {
                        sendEvent(emitter, "progress", new StudentExplanationStreamEvent(
                                "progress",
                                message,
                                progress,
                                null,
                                null,
                                null,
                                null,
                                null,
                                List.of()));
                    }

                    @Override
                    public void onAiDelta(
                            com.doob.mathagent.agent.service.AiChatStreamDelta delta,
                            List<StudentExplanationResponse.ExplanationCard> cards) {
                        sendEvent(emitter, "ai_delta", new StudentExplanationStreamEvent(
                                "ai_delta", "收到模型实时输出。", null, null, null, null,
                                delta.contentDelta(), delta.reasoningDelta(), cards));
                    }

                    @Override
                    public void onCompleted(StudentExplanationResponse response) {
                        sendEvent(emitter, "completed", new StudentExplanationStreamEvent(
                                "completed",
                                "讲解已完成。",
                                null,
                                response,
                                null,
                                null,
                                null,
                                null,
                                List.of()));
                    }
                });
                emitter.complete();
            } catch (IllegalArgumentException exception) {
                String traceId = UUID.randomUUID().toString();
                log.warn("student_explanation_stream_bad_request traceId={} path={} message={}",
                        traceId,
                        requestPath,
                        exception.getMessage(),
                        exception);
                sendEvent(emitter, "error", new StudentExplanationStreamEvent(
                        "error",
                        exception.getMessage(),
                        null,
                        null,
                        "BAD_REQUEST",
                        traceId,
                        null,
                        null,
                        List.of()));
                emitter.complete();
            } catch (RuntimeException exception) {
                String traceId = UUID.randomUUID().toString();
                log.error("student_explanation_stream_failed traceId={} path={} type={} message={}",
                        traceId,
                        requestPath,
                        exception.getClass().getSimpleName(),
                        exception.getMessage(),
                        exception);
                sendEvent(emitter, "error", new StudentExplanationStreamEvent(
                        "error",
                        "讲解过程中出现错误，请稍后重试。",
                        null,
                        null,
                        "STREAM_FAILED",
                        traceId,
                        null,
                        null,
                        List.of()));
                emitter.complete();
            }
        });
        return emitter;
    }

    /**
     * Sends one SSE event and converts IO failures into controller-level runtime exceptions.
     */
    private static void sendEvent(SseEmitter emitter, String eventName, StudentExplanationStreamEvent event) {
        try {
            emitter.send(SseEmitter.event().name(eventName).data(event));
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to send student explanation stream event", exception);
        }
    }
}
