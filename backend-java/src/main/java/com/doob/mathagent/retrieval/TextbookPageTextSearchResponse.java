package com.doob.mathagent.retrieval;

import java.util.List;

/** Worker response for BGE page-text coarse recall. */
public record TextbookPageTextSearchResponse(
        String query,
        int limit,
        String provider,
        String model,
        int hitCount,
        List<TextbookPageTextSearchHit> hits) {
}
