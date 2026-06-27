package com.doob.mathagent.retrieval;

import com.doob.mathagent.resources.TextbookResourceProperties;
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
            @RequestParam(defaultValue = "10") int limit) {
        return retrievalService.search(
                resourceProperties.processedBooksRoot(),
                new TextbookSearchRequest(query, limit));
    }
}
