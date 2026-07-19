package com.doob.mathagent.resources;

import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

/**
 * Public textbook page image endpoint backed by processed_books.
 */
@RestController
public class TextbookPageImageController {

    private final TextbookPageImageService pageImageService;
    private final TextbookResourceProperties textbookResourceProperties;

    public TextbookPageImageController(
            TextbookPageImageService pageImageService,
            TextbookResourceProperties textbookResourceProperties) {
        this.pageImageService = pageImageService;
        this.textbookResourceProperties = textbookResourceProperties;
    }

    /**
     * Streams one textbook page image without exposing its local processed_books path.
     */
    @GetMapping("/api/resources/textbooks/{docId}/pages/{pageNo}/image")
    public ResponseEntity<Resource> readPageImage(
            @PathVariable String docId,
            @PathVariable int pageNo) {
        try {
            TextbookPageImageService.VisibleTextbookPageImage image = pageImageService.openPageImage(
                    textbookResourceProperties.processedBooksRoot(),
                    docId,
                    pageNo);
            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType(image.mimeType()))
                    .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline()
                            .filename(image.fileName())
                            .build()
                            .toString())
                    .body(image.resource());
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Textbook page image not found", exception);
        }
    }
}
