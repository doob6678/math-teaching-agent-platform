package com.doob.mathagent.retrieval;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

/**
 * HTTP contract for public textbook CLIP page-image search.
 */
@RestController
public class TextbookPageImageSearchController {

    private final TextbookPageImageSearchService searchService;

    public TextbookPageImageSearchController(TextbookPageImageSearchService searchService) {
        this.searchService = searchService;
    }

    /**
     * Searches page images from the reused processed_books CLIP index.
     */
    @PostMapping("/api/retrieval/textbooks/page-search")
    public TextbookPageImageSearchResponse search(
            @RequestBody TextbookPageImageSearchRequest request) {
        try {
            return searchService.search(request);
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage(), exception);
        } catch (IllegalStateException exception) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, exception.getMessage(), exception);
        }
    }
}
