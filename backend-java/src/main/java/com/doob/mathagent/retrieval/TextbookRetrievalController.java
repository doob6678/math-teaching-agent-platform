package com.doob.mathagent.retrieval;

import com.doob.mathagent.resources.TextbookResourceProperties;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class TextbookRetrievalController {

    private final TextbookRetrievalService retrievalService;
    private final TextbookResourceProperties resourceProperties;

    public TextbookRetrievalController(
            TextbookRetrievalService retrievalService,
            TextbookResourceProperties resourceProperties) {
        this.retrievalService = retrievalService;
        this.resourceProperties = resourceProperties;
    }

    @GetMapping("/api/retrieval/textbooks/search")
    public TextbookSearchResponse search(
            @RequestParam String query,
            @RequestParam(defaultValue = "10") int limit,
            HttpServletRequest httpRequest) {
        return retrievalService.search(
                resourceProperties.processedBooksRoot(),
                new TextbookSearchRequest(query, limit),
                requestContext(httpRequest));
    }

    TextbookSearchResponse search(String query, int limit) {
        return search(query, limit, null);
    }

    private static RetrievalRequestContext requestContext(HttpServletRequest httpRequest) {
        if (httpRequest == null) {
            return RetrievalRequestContext.defaultTextbookSearch();
        }
        return new RetrievalRequestContext(
                headerOrDefault(httpRequest, "X-Tenant-Id", "default"),
                headerOrNull(httpRequest, "X-Subject-Type"),
                headerOrNull(httpRequest, "X-Subject-Id"),
                clientIp(httpRequest),
                headerOrNull(httpRequest, "X-Device-Id"),
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

    private static String headerOrDefault(HttpServletRequest httpRequest, String name, String defaultValue) {
        String value = headerOrNull(httpRequest, name);
        return value == null ? defaultValue : value;
    }

    private static String headerOrNull(HttpServletRequest httpRequest, String name) {
        String value = httpRequest.getHeader(name);
        return value == null || value.isBlank() ? null : value.strip();
    }
}
