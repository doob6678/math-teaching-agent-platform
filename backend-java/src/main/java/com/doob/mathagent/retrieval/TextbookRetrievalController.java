package com.doob.mathagent.retrieval;

import com.doob.mathagent.infrastructure.security.RequestSubject;
import com.doob.mathagent.infrastructure.security.RequestSubjectResolver;
import com.doob.mathagent.resources.TextbookResourceProperties;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class TextbookRetrievalController {

    private final TextbookRetrievalService retrievalService;
    private final TextbookResourceProperties resourceProperties;
    private final RequestSubjectResolver subjectResolver;

    public TextbookRetrievalController(
            TextbookRetrievalService retrievalService,
            TextbookResourceProperties resourceProperties,
            RequestSubjectResolver subjectResolver) {
        this.retrievalService = retrievalService;
        this.resourceProperties = resourceProperties;
        this.subjectResolver = subjectResolver;
    }

    @GetMapping("/api/retrieval/textbooks/search")
    public TextbookSearchResponse search(
            @RequestParam String query,
            @RequestParam(defaultValue = "10") int limit,
            @RequestParam(value = "documentId", required = false) List<String> documentIds,
            HttpServletRequest httpRequest) {
        return retrievalService.search(
                resourceProperties.processedBooksRoot(),
                new TextbookSearchRequest(query, limit, documentIds),
                requestContext(httpRequest, subjectResolver.resolve(httpRequest)));
    }

    TextbookSearchResponse search(String query, int limit) {
        return search(query, limit, null, null);
    }

    /** Accepts formula text and image data that cannot safely fit in a GET query string. */
    @PostMapping("/api/retrieval/textbooks/search")
    public TextbookSearchResponse searchWithConfiguration(
            @RequestBody TextbookSearchRequest request,
            HttpServletRequest httpRequest) {
        TextbookSearchRequest normalized = request == null ? new TextbookSearchRequest("", 10) : request;
        return retrievalService.search(
                resourceProperties.processedBooksRoot(),
                normalized,
                requestContext(httpRequest, subjectResolver.resolve(httpRequest)));
    }

    private static RetrievalRequestContext requestContext(HttpServletRequest httpRequest, RequestSubject subject) {
        RequestSubject normalized = subject.normalize();
        if (httpRequest == null) {
            return RetrievalRequestContext.defaultTextbookSearch();
        }
        return new RetrievalRequestContext(
                normalized.tenantId(),
                normalized.subjectType(),
                normalized.subjectId(),
                clientIp(httpRequest),
                normalized.deviceId(),
                httpRequest.getHeader("User-Agent"),
                httpRequest.getRequestURI());
    }

    private static String clientIp(HttpServletRequest httpRequest) {
        String forwardedFor = headerOrNull(httpRequest, "X-Forwarded-For");
        if (forwardedFor != null) {
            return forwardedFor.split(",")[0].strip();
        }
        return httpRequest.getRemoteAddr();
    }

    private static String headerOrNull(HttpServletRequest httpRequest, String name) {
        String value = httpRequest.getHeader(name);
        return value == null || value.isBlank() ? null : value.strip();
    }
}
