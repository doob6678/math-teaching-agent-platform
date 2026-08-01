package com.doob.mathagent.student.controller;

import com.doob.mathagent.agent.service.AiProviderUnavailableException;
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
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern JSON_TEXT_FIELD = Pattern.compile(
            "\\\"(?:title|summary)\\\"\\s*:\\s*\\\"((?:\\\\.|[^\\\"\\\\])*)\\\"");
    private static final Pattern JSON_PARTIAL_TEXT_FIELD = Pattern.compile(
            "\\\"(?:title|summary)\\\"\\s*:\\s*\\\"((?:\\\\.|[^\\\"\\\\])*)$");
    private static final Pattern JSON_ITEMS_ARRAY = Pattern.compile(
            "\\\"items\\\"\\s*:\\s*\\[([^\\]]*)");
    private static final Pattern JSON_STRING = Pattern.compile("\\\"((?:\\\\.|[^\\\"\\\\])*)\\\"");

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
                StringBuilder streamedProviderContent = new StringBuilder();
                String[] sentVisibleContent = {""};
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
                        // Providers return a strict JSON envelope for validation. Extract only learner-facing title,
                        // summary, and item text before putting anything into SSE; braces and property names never
                        // cross the API boundary. Complete cards remain in the structured cards field below.
                        String streamedDelta = delta == null ? "" : delta.contentDelta();
                        String visibleDelta = visibleProviderDelta(
                                streamedProviderContent, sentVisibleContent, streamedDelta);
                        List<StudentExplanationResponse.ExplanationCard> safeCards = cards == null
                                ? List.of() : List.copyOf(cards);
                        if (visibleDelta.isBlank() && safeCards.isEmpty()) {
                            return;
                        }
                        sendEvent(emitter, "ai_delta", new StudentExplanationStreamEvent(
                                "ai_delta", "收到模型实时输出。", null, null, null, null,
                                visibleDelta, "", safeCards));
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
            } catch (AiProviderUnavailableException exception) {
                String traceId = UUID.randomUUID().toString();
                log.warn("student_explanation_model_unavailable traceId={} path={} status={} message={}",
                        traceId,
                        requestPath,
                        exception.statusCode(),
                        exception.getMessage());
                // Keep authentication and the current conversation intact: provider capacity is recoverable and is
                // unrelated to the user's session. The trace id links the UI failure to the bounded provider log.
                sendEvent(emitter, "error", new StudentExplanationStreamEvent(
                        "error",
                        "当前讲解模型暂时没有可用通道，系统已自动重试，请稍后再次提交。",
                        null,
                        null,
                        "MODEL_UNAVAILABLE",
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

    /**
     * Converts one cumulative provider JSON stream into a text-only incremental update for learners.
     *
     * <p>The provider may split a JSON string across many SSE packets. Keeping the cumulative wire content here lets
     * us expose completed or safe partial prose without duplicating text on every packet. Plain-text provider output
     * is preserved as-is for compatibility with providers that do not use the card envelope.</p>
     *
     * @param cumulativeProviderContent all content deltas received for this model call
     * @param sentVisibleContent previously emitted learner-facing text
     * @param contentDelta latest provider content delta
     * @return only the new learner-facing text, never JSON punctuation or field names
     */
    private static String visibleProviderDelta(
            StringBuilder cumulativeProviderContent,
            String[] sentVisibleContent,
            String contentDelta) {
        if (contentDelta == null || contentDelta.isBlank()) {
            return "";
        }
        cumulativeProviderContent.append(contentDelta);
        String cumulative = cumulativeProviderContent.toString();
        String candidate = extractVisibleProviderText(cumulative);
        if (candidate.isBlank()) {
            return "";
        }
        String previouslySent = sentVisibleContent[0];
        String next;
        if (candidate.startsWith(previouslySent)) {
            next = candidate.substring(previouslySent.length());
        } else {
            // A provider can repair an incomplete escape sequence. Do not repeat the whole answer when the prefix
            // changes; emit only the changed text after the longest stable prefix.
            int commonLength = commonPrefixLength(previouslySent, candidate);
            next = candidate.substring(commonLength);
        }
        sentVisibleContent[0] = candidate;
        return next;
    }

    /** Extracts ordered prose fields from the provider's cumulative JSON envelope. */
    private static String extractVisibleProviderText(String content) {
        if (content == null || content.isBlank()) {
            return "";
        }
        String trimmed = content.stripLeading();
        if (!trimmed.startsWith("{") && !trimmed.startsWith("[")) {
            return content;
        }
        List<String> values = new ArrayList<>();
        Matcher fieldMatcher = JSON_TEXT_FIELD.matcher(content);
        while (fieldMatcher.find()) {
            addDecoded(values, fieldMatcher.group(1));
        }
        Matcher partialFieldMatcher = JSON_PARTIAL_TEXT_FIELD.matcher(content);
        while (partialFieldMatcher.find()) {
            addDecoded(values, partialFieldMatcher.group(1));
        }
        Matcher itemsMatcher = JSON_ITEMS_ARRAY.matcher(content);
        while (itemsMatcher.find()) {
            Matcher itemMatcher = JSON_STRING.matcher(itemsMatcher.group(1));
            while (itemMatcher.find()) {
                addDecoded(values, itemMatcher.group(1));
            }
        }
        return String.join("\n", new LinkedHashSet<>(values));
    }

    /** Decodes one JSON string value while tolerating a provider packet ending mid-escape. */
    private static void addDecoded(List<String> values, String encoded) {
        if (encoded == null || encoded.isBlank()) {
            return;
        }
        String decoded;
        try {
            decoded = JSON.readValue("\"" + encoded + "\"", String.class);
        } catch (Exception ignored) {
            decoded = encoded.replace("\\n", "\n").replace("\\r", "\r").replace("\\t", "\t")
                    .replace("\\\"", "\"").replace("\\\\", "\\");
        }
        if (!decoded.isBlank()) {
            values.add(decoded.strip());
        }
    }

    /** Returns the stable prefix shared by two cumulative provider strings. */
    private static int commonPrefixLength(String left, String right) {
        int length = Math.min(left.length(), right.length());
        int index = 0;
        while (index < length && left.charAt(index) == right.charAt(index)) {
            index++;
        }
        return index;
    }
}
