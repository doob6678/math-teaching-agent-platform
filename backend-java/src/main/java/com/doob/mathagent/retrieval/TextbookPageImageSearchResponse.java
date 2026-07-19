package com.doob.mathagent.retrieval;

import java.util.List;

/**
 * Response contract for public textbook page-image retrieval.
 *
 * @param query original text query when present
 * @param limit normalized limit
 * @param provider worker-side embedding provider
 * @param model worker-side model path or model code
 * @param hitCount returned hit count
 * @param hits ranked textbook page-image hits
 */
public record TextbookPageImageSearchResponse(
        String query,
        int limit,
        String provider,
        String model,
        int hitCount,
        List<TextbookPageImageSearchHit> hits) {
}
