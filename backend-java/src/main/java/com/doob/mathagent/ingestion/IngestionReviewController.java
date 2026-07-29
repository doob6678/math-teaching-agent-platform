package com.doob.mathagent.ingestion;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** Browser adapter for the minimal human review loop; it carries no AI decision endpoint. */
@RestController
@RequestMapping("/api/ingestion/review-queue")
public final class IngestionReviewController {
    private final IngestionReviewQueueService service;

    public IngestionReviewController(IngestionReviewQueueService service) {
        this.service = service;
    }

    @GetMapping
    public Map<String, Object> queue() throws Exception {
        return service.queue();
    }

    @PostMapping("/decisions")
    public Map<String, Object> recordDecision(@RequestBody IngestionReviewDecisionRequest request) throws Exception {
        return service.recordDecision(request);
    }
}
