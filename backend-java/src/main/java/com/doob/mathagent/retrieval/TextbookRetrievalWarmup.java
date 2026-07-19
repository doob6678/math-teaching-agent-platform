package com.doob.mathagent.retrieval;

import com.doob.mathagent.resources.TextbookResourceProperties;
import com.doob.mathagent.vector.service.VectorIndexService;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Preloads the local processed-books snapshot after application startup.
 *
 * <p>Retrieval remains correct without this component, but opening thousands of local textbook chunks during the
 * first teacher request violates the interactive latency target. It therefore warms the immutable source snapshot and
 * each local worker model route once. These calls do not persist search results or write corpus data; they only make
 * model initialization an application-start cost instead of a teacher-visible request cost.</p>
 */
@Component
public class TextbookRetrievalWarmup {

    private static final Logger log = LoggerFactory.getLogger(TextbookRetrievalWarmup.class);

    private final TextbookRetrievalService retrievalService;
    private final TextbookResourceProperties resourceProperties;
    private final TextbookPageTextSearchService pageTextSearchService;
    private final TextbookPageImageSearchService pageImageSearchService;
    private final VectorIndexService vectorIndexService;

    public TextbookRetrievalWarmup(
            TextbookRetrievalService retrievalService,
            TextbookResourceProperties resourceProperties,
            TextbookPageTextSearchService pageTextSearchService,
            TextbookPageImageSearchService pageImageSearchService,
            VectorIndexService vectorIndexService) {
        this.retrievalService = retrievalService;
        this.resourceProperties = resourceProperties;
        this.pageTextSearchService = pageTextSearchService;
        this.pageImageSearchService = pageImageSearchService;
        this.vectorIndexService = vectorIndexService;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void warmCorpusAfterStartup() {
        try {
            retrievalService.warmCorpus(resourceProperties.processedBooksRoot());
            // A generic non-user query initializes the BGE page index reader. It is deliberately not inserted into
            // Redis/audits, so warmup cannot affect ranking or introduce hidden test data.
            pageTextSearchService.search(new TextbookPageTextSearchRequest("数学教材", 1, List.of()));
            // Image retrieval remains a separate route for diagrams and scanned pages, so it needs its own CLIP load.
            pageImageSearchService.search(new TextbookPageImageSearchRequest("数学教材", null, 1, List.of()));
            // The cross-encoder is the final page/block authority and must be loaded before the first user search.
            vectorIndexService.rerankTexts("数学教材", List.of("数学教材"));
            log.info("textbook_retrieval_corpus_warmed root={}", resourceProperties.processedBooksRoot());
        } catch (RuntimeException exception) {
            // A temporary local file failure must not prevent unrelated backend APIs from becoming available.
            log.warn("textbook_retrieval_corpus_warmup_failed root={} message={}",
                    resourceProperties.processedBooksRoot(), exception.getMessage());
        }
    }
}
