package com.doob.mathagent.resources;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class TextbookResourceController {

    private final TextbookResourceService textbookResourceService;
    private final TextbookResourceProperties textbookResourceProperties;

    public TextbookResourceController(
            TextbookResourceService textbookResourceService,
            TextbookResourceProperties textbookResourceProperties) {
        this.textbookResourceService = textbookResourceService;
        this.textbookResourceProperties = textbookResourceProperties;
    }

    @GetMapping("/api/resources/textbooks/summary")
    public TextbookResourceSummary summary() {
        return textbookResourceService.summarize(textbookResourceProperties.processedBooksRoot());
    }
}
